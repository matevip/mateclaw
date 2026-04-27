package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalPlaceholderUtil;
import vip.mate.approval.MetadataDecision;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.conversation.vo.ConversationVO;
import vip.mate.workspace.conversation.vo.MessageVO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话管理服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    public static final String SYSTEM_USER = "system";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取用户的会话列表（返回 VO，包含 agentName/agentIcon/status）
     */
    public List<ConversationVO> listConversations(String username) {
        return listConversations(username, null);
    }

    /**
     * 获取用户的会话列表（按工作区过滤）
     */
    public List<ConversationVO> listConversations(String username, Long workspaceId) {
        // 同时返回当前用户的会话 和 定时任务（system）产生的会话
        // 排除子会话（委派产生的子会话不在侧边栏显示）
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<ConversationEntity>()
                .in(ConversationEntity::getUsername, username, SYSTEM_USER)
                .isNull(ConversationEntity::getParentConversationId)
                .orderByDesc(ConversationEntity::getLastActiveTime);
        if (workspaceId != null) {
            wrapper.eq(ConversationEntity::getWorkspaceId, workspaceId);
        }
        List<ConversationEntity> entities = conversationMapper.selectList(wrapper);

        if (entities.isEmpty()) {
            return List.of();
        }

        // 批量查询关联的 Agent 信息，避免 N+1 查询
        List<Long> agentIds = entities.stream()
                .filter(e -> e.getAgentId() != null)
                .map(ConversationEntity::getAgentId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, AgentEntity> agentMap = agentIds.isEmpty()
                ? Map.of()
                : agentMapper.selectBatchIds(agentIds).stream()
                        .collect(Collectors.toMap(AgentEntity::getId, a -> a));

        // 转换为 VO，补充 agentName/agentIcon/status
        return entities.stream()
                .map(entity -> {
                    AgentEntity agent = entity.getAgentId() != null
                            ? agentMap.get(entity.getAgentId())
                            : null;
                    String agentName = agent != null ? agent.getName() : null;
                    String agentIcon = agent != null ? agent.getIcon() : null;
                    return ConversationVO.from(entity, agentName, agentIcon);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取或创建会话（向后兼容，默认 workspace 1）
     */
    @Transactional
    public ConversationEntity getOrCreateConversation(String conversationId, Long agentId, String username) {
        return getOrCreateConversation(conversationId, agentId, username, 1L);
    }

    /**
     * 获取或创建会话（workspace 感知）
     */
    @Transactional
    public ConversationEntity getOrCreateConversation(String conversationId, Long agentId,
                                                       String username, Long workspaceId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            conv = new ConversationEntity();
            conv.setConversationId(conversationId);
            conv.setAgentId(agentId);
            conv.setUsername(username != null ? username : "anonymous");
            conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
            conv.setTitle("新对话");
            conv.setMessageCount(0);
            conv.setLastActiveTime(LocalDateTime.now());
            conversationMapper.insert(conv);
        } else if (!conv.getUsername().equals(username)) {
            throw new IllegalArgumentException("无权操作该会话");
        }
        return conv;
    }

    /**
     * 创建子会话（委派场景），关联父会话 ID。
     */
    @Transactional
    public ConversationEntity createChildConversation(String childConversationId, Long agentId,
                                                        String username, Long workspaceId,
                                                        String parentConversationId) {
        ConversationEntity conv = getOrCreateConversation(childConversationId, agentId, username, workspaceId);
        conv.setParentConversationId(parentConversationId);
        conv.setTitle("子任务");
        conversationMapper.updateById(conv);
        return conv;
    }

    /**
     * 获取或创建共享渠道会话。
     * <p>
     * IM 渠道（飞书/钉钉/企微等）的会话需要在控制台中对登录用户可见，
     * 因此统一使用 system 作为 owner。对于历史上已写成发送者昵称/open_id 的会话，
     * 这里会自动修正为 system，避免控制台列表和消息接口因权限校验而不可见。
     */
    @Transactional
    public ConversationEntity getOrCreateSharedConversation(String conversationId, Long agentId) {
        return getOrCreateSharedConversation(conversationId, agentId, null);
    }

    /**
     * 获取或创建共享渠道会话（workspace 感知）
     */
    @Transactional
    public ConversationEntity getOrCreateSharedConversation(String conversationId, Long agentId, Long workspaceId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            conv = new ConversationEntity();
            conv.setConversationId(conversationId);
            conv.setAgentId(agentId);
            conv.setUsername(SYSTEM_USER);
            conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
            conv.setTitle("新对话");
            conv.setMessageCount(0);
            conv.setLastActiveTime(LocalDateTime.now());
            try {
                conversationMapper.insert(conv);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发插入：另一个线程已创建，回退到查询
                conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId));
                if (conv == null) {
                    throw new IllegalStateException("Conversation vanished after duplicate key: " + conversationId, e);
                }
                // 继续走下面的 owner 修正逻辑
            }
        }

        boolean changed = false;
        if (!SYSTEM_USER.equals(conv.getUsername())) {
            conv.setUsername(SYSTEM_USER);
            changed = true;
        }
        if (conv.getAgentId() == null && agentId != null) {
            conv.setAgentId(agentId);
            changed = true;
        }
        if (changed) {
            conversationMapper.updateById(conv);
        }
        return conv;
    }

    /**
     * 保存消息并更新会话统计
     */
    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content) {
        return saveMessage(conversationId, role, content, null, "completed");
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content, List<MessageContentPart> parts) {
        return saveMessage(conversationId, role, content, parts, "completed");
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content,
            List<MessageContentPart> parts, String status) {
        return saveMessage(conversationId, role, content, parts, status, 0, 0, null, null);
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content,
            List<MessageContentPart> parts, String status,
            int promptTokens, int completionTokens,
            String runtimeModel, String runtimeProvider) {
        return saveMessage(conversationId, role, content, parts, status,
                promptTokens, completionTokens, runtimeModel, runtimeProvider, null);
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content,
            List<MessageContentPart> parts, String status,
            int promptTokens, int completionTokens,
            String runtimeModel, String runtimeProvider, String metadata) {
        MessageEntity message = new MessageEntity();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setContentParts(serializeParts(parts));
        message.setStatus(status != null ? status : "completed");
        message.setTokenUsage(promptTokens + completionTokens);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setRuntimeModel(runtimeModel);
        message.setRuntimeProvider(runtimeProvider);
        message.setMetadata(metadata != null ? metadata : "{}");  // 初始化为空对象
        messageMapper.insert(message);

        // 更新会话信息
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setLastActiveTime(LocalDateTime.now());
            String summary = summarizeMessage(content, parts);
            // 用第一条用户消息作为会话标题
            if ("user".equals(role) && "新对话".equals(conv.getTitle())) {
                conv.setTitle(summary.length() > 20 ? summary.substring(0, 20) + "..." : summary);
            }
            // 保存最后一条 AI 回复摘要
            if ("assistant".equals(role)) {
                conv.setLastMessage(summary.length() > 50 ? summary.substring(0, 50) + "..." : summary);
            }
            conversationMapper.updateById(conv);
        }
        return message;
    }

    /**
     * 更新消息的元数据（toolCalls, plan, currentPhase 等）
     */
    @Transactional
    public void updateMessageMetadata(Long messageId, String metadata) {
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setMetadata(metadata);
        messageMapper.updateById(message);
    }

    /**
     * 重命名会话
     */
    @Transactional
    public void renameConversation(String conversationId, String title) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setTitle(title);
            conversationMapper.updateById(conv);
        }
    }

    /**
     * 更新会话的流状态（running / idle）
     */
    @Transactional
    public void updateStreamStatus(String conversationId, String streamStatus) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setStreamStatus(streamStatus);
            conversationMapper.updateById(conv);
        }
    }

    /**
     * 获取会话最后一条消息内容（用于 rate limit 防护等场景）
     */
    public String getLastMessage(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        return conv != null ? conv.getLastMessage() : null;
    }

    /**
     * 获取会话的消息数量
     */
    public int getMessageCount(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        return conv != null && conv.getMessageCount() != null ? conv.getMessageCount() : 0;
    }

    /**
     * 获取会话的消息历史
     */
    public List<MessageEntity> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .orderByAsc(MessageEntity::getCreateTime)
                .orderByAsc(MessageEntity::getId));
    }

    /**
     * 加载最近 N 条消息（倒序取出后翻转为正序）。
     * 利用复合索引 (conversation_id, create_time) 高效分页。
     */
    public List<MessageEntity> listRecentMessages(String conversationId, int lastN) {
        List<MessageEntity> recent = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByDesc(MessageEntity::getCreateTime)
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT " + lastN));
        Collections.reverse(recent);
        return recent;
    }

    /**
     * 分页加载指定 ID 之前的消息（用于前端上拉加载更早消息）。
     * 返回倒序结果，调用方需自行 reverse。
     */
    public List<MessageEntity> listMessagesBefore(String conversationId, Long beforeId, int limit) {
        List<MessageEntity> results = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .lt(MessageEntity::getId, beforeId)
                        .orderByDesc(MessageEntity::getCreateTime)
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT " + limit));
        Collections.reverse(results);
        return results;
    }

    /**
     * 查询会话消息总数。
     */
    public long countMessages(String conversationId) {
        return messageMapper.selectCount(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId));
    }

    /**
     * 将压缩摘要持久化为 role=system 的特殊消息。
     * 下次加载历史时识别此消息，跳过它之前的已压缩消息。
     */
    public void saveCompressionSummary(String conversationId, String summary, int compressedCount) {
        MessageEntity entity = new MessageEntity();
        entity.setConversationId(conversationId);
        entity.setRole("system");
        entity.setContent(summary);
        entity.setStatus("completed");
        entity.setMetadata("{\"type\":\"compression_summary\",\"compressedCount\":" + compressedCount + "}");
        messageMapper.insert(entity);
        log.info("[Conversation] Saved compression summary for conv={}, compressedCount={}", conversationId, compressedCount);
    }

    public List<MessageVO> listMessageViews(String conversationId) {
        return listMessages(conversationId).stream()
                .map(message -> MessageVO.from(message, parseMessageParts(message), renderMessageContent(message)))
                .toList();
    }

    /**
     * 删除会话（同时删除消息和附件文件）
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        conversationMapper.delete(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId));
        cleanAttachmentFiles(conversationId);
    }

    /**
     * 清空会话消息（同时清理附件文件）
     */
    @Transactional
    public void clearMessages(String conversationId) {
        messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId));
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setMessageCount(0);
            conv.setLastMessage(null);
            conversationMapper.updateById(conv);
        }
        cleanAttachmentFiles(conversationId);
    }

    public List<MessageContentPart> parseMessageParts(MessageEntity message) {
        if (message == null || message.getContentParts() == null || message.getContentParts().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(message.getContentParts(), new TypeReference<List<MessageContentPart>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse content_parts for message {}: {}", message.getId(), e.getMessage());
            return List.of(MessageContentPart.parseError(
                    message.getId() != null ? message.getId().toString() : "unknown",
                    e.getMessage() != null ? e.getMessage() : "unknown error"));
        }
    }

    public String renderMessageContent(MessageEntity message) {
        List<MessageContentPart> parts = parseMessageParts(message);
        if (parts.isEmpty()) {
            return message.getContent() != null ? message.getContent() : "";
        }

        StringBuilder text = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null || part.getType() == null) {
                continue;
            }
            switch (part.getType()) {
                case "text" -> appendSegment(text, part.getText());
                case "thinking", "tool_call", "parse_error" -> { /* skip — frontend reads these from contentParts directly */ }
                case "file" -> appendSegment(text, "[附件] " + safe(part.getFileName()));
                default -> appendSegment(text, part.getText());
            }
        }
        return text.toString().trim();
    }

    private String serializeParts(List<MessageContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize message parts", e);
        }
    }

    private String summarizeMessage(String content, List<MessageContentPart> parts) {
        String rendered = content;
        if ((rendered == null || rendered.isBlank()) && parts != null && !parts.isEmpty()) {
            rendered = parts.stream()
                    .map(part -> {
                        if (part == null || part.getType() == null) {
                            return "";
                        }
                        return switch (part.getType()) {
                            case "text", "thinking" -> safe(part.getText());
                            case "tool_call" -> "";
                            case "file" -> "[附件] " + safe(part.getFileName());
                            default -> safe(part.getText());
                        };
                    })
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining(" "));
        }
        if (rendered == null || rendered.isBlank()) {
            return "新消息";
        }
        return rendered;
    }

    private void appendSegment(StringBuilder builder, String text) {
        String safeText = safe(text);
        if (safeText.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(safeText);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    /**
     * 删除指定会话中所有审批占位 assistant 消息
     * <p>
     * 在 replay 前调用，确保 LLM 上下文中不包含任何审批相关文本。
     */
    /**
     * Reconcile persisted assistant-message state when one or more pending approvals
     * leave the {@code pending} status (approve / deny / timeout / superseded / consumed).
     * <p>
     * For each assistant message in the conversation whose
     * {@code metadata.pendingApproval.pendingId} appears in {@code resolvedPendingIds}
     * and whose {@code metadata.pendingApproval.status == "pending_approval"}, this
     * method updates three fields atomically (within a single transaction):
     * <ol>
     *   <li>{@code metadata.pendingApproval.status} → {@code decision.pendingApprovalStatus}</li>
     *   <li>{@code metadata.currentPhase} flips {@code awaiting_approval} → {@code resolved}</li>
     *   <li>{@code MessageEntity.status} flips {@code awaiting_approval}
     *       → {@code decision.messageStatus} (one of the existing terminal states the
     *       frontend Message.status union supports)</li>
     * </ol>
     * Without this synchronization, a page refresh re-hydrates the stale
     * {@code pending_approval} status from message metadata and the UI pops a ghost
     * approval banner for an approval the user already settled. See RFC-067 §4.1.5.
     * <p>
     * Idempotent: messages whose metadata does not match, or whose status already moved
     * off {@code pending_approval}, are left untouched. Timeout / superseded callers
     * pass {@link MetadataDecision#DENIED}; the more specific terminal status lives
     * on {@code mate_tool_approval.status} for audit (see RFC-067 §4.4.1).
     *
     * @param conversationId target conversation
     * @param resolvedPendingIds pendingIds whose owning message metadata should be reconciled
     * @param decision the metadata-layer decision to apply
     * @return number of messages whose state was rewritten
     */
    @Transactional
    public int markPendingApprovalsResolved(String conversationId,
                                            java.util.Set<String> resolvedPendingIds,
                                            MetadataDecision decision) {
        if (conversationId == null || resolvedPendingIds == null || resolvedPendingIds.isEmpty()) {
            return 0;
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        List<MessageEntity> messages = listMessages(conversationId);
        int rewritten = 0;
        for (MessageEntity msg : messages) {
            if (!"assistant".equals(msg.getRole())) continue;
            String raw = msg.getMetadata();
            if (raw == null || raw.isBlank() || !raw.contains("pendingApproval")) continue;

            try {
                java.util.Map<String, Object> meta = objectMapper.readValue(raw, new TypeReference<>() {});
                Object pa = meta.get("pendingApproval");
                if (!(pa instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> pendingApproval = (java.util.Map<String, Object>) pa;
                Object pid = pendingApproval.get("pendingId");
                if (pid == null || !resolvedPendingIds.contains(String.valueOf(pid))) continue;
                Object pendingStatus = pendingApproval.get("status");
                if (!"pending_approval".equals(String.valueOf(pendingStatus))) continue;

                pendingApproval.put("status", decision.pendingApprovalStatus);
                meta.put("pendingApproval", pendingApproval);

                Object phase = meta.get("currentPhase");
                if ("awaiting_approval".equals(String.valueOf(phase))) {
                    meta.put("currentPhase", "resolved");
                }

                msg.setMetadata(objectMapper.writeValueAsString(meta));
                if ("awaiting_approval".equals(msg.getStatus())) {
                    msg.setStatus(decision.messageStatus);
                }
                messageMapper.updateById(msg);
                rewritten++;
            } catch (Exception e) {
                log.warn("[ConversationService] Failed to rewrite pendingApproval status for message {}: {}",
                        msg.getId(), e.getMessage());
            }
        }
        if (rewritten > 0) {
            log.info("[ConversationService] Reconciled {} message(s) in conversation {} " +
                            "to decision={} (cleared {} ghost pendings)",
                    rewritten, conversationId, decision, resolvedPendingIds.size());
        }
        return rewritten;
    }

    @Transactional
    public void removeApprovalPlaceholders(String conversationId) {
        List<MessageEntity> messages = listMessages(conversationId);
        int removed = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageEntity msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && isApprovalPlaceholder(msg.getContent())) {
                messageMapper.deleteById(msg.getId());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[ConversationService] Removed {} approval placeholder(s) from conversation {}",
                    removed, conversationId);
        }
    }

    private static boolean isApprovalPlaceholder(String content) {
        return ApprovalPlaceholderUtil.isApprovalPlaceholder(content);
    }

    /**
     * 检查会话是否存在
     */
    public boolean conversationExists(String conversationId) {
        return conversationMapper.selectCount(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)) > 0;
    }

    /**
     * 校验用户是否拥有该会话。
     * 定时任务产生的会话（username=system）对所有登录用户可见。
     */
    public boolean isConversationOwner(String conversationId, String username) {
        ConversationEntity conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            return false;
        }
        return username.equals(conv.getUsername()) || SYSTEM_USER.equals(conv.getUsername());
    }

    /**
     * 获取会话的持久化流状态
     */
    public String getStreamStatus(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId));
        return conv != null ? conv.getStreamStatus() : null;
    }

    private static final Path UPLOAD_ROOT = Paths.get("data", "chat-uploads");

    /**
     * 清理会话关联的附件文件
     */
    public void cleanAttachmentFiles(String conversationId) {
        Path dir = UPLOAD_ROOT.resolve(conversationId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete attachment file: {}", p, e);
                        }
                    });
            log.info("Cleaned attachment files for conversation: {}", conversationId);
        } catch (IOException e) {
            log.warn("Failed to walk attachment directory for conversation: {}", conversationId, e);
        }
    }
}
