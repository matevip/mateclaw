package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.List;

/**
 * Wiki 知识库服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiKnowledgeBaseService {

    private final WikiKnowledgeBaseMapper kbMapper;

    private static final String DEFAULT_CONFIG = """
            # Wiki Processing Rules

            ## Quality First
            - Create high-quality pages — prefer fewer complete pages over many shallow ones
            - Each page focuses on one concept, entity, or process
            - A page must have at least 3 sentences of substantive content
            - Target 3-5 pages per source material (not 10-15)
            - If a concept already exists in the wiki, update it instead of duplicating

            ## Format
            - Use clear Markdown headers (## and ###)
            - Include a one-paragraph summary at the top of each page
            - Use [[Page Title]] syntax for bidirectional links between pages

            ## Updates
            - Merge new information into existing pages, do not replace
            - Preserve manually edited content (last_updated_by = 'manual')
            - Mark contradictions clearly with a "Note:" annotation

            ## Language
            - Write wiki pages in the same language as the source material
            - Keep technical terms consistent across pages
            """;

    public List<WikiKnowledgeBaseEntity> listAll() {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * 按工作区列出知识库
     */
    public List<WikiKnowledgeBaseEntity> listByWorkspace(Long workspaceId) {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .eq(WikiKnowledgeBaseEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * 获取 Agent 可访问的知识库：Agent 专属 KB + 公共 KB（agent_id IS NULL）
     */
    public List<WikiKnowledgeBaseEntity> listByAgentId(Long agentId) {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .and(w -> w.eq(WikiKnowledgeBaseEntity::getAgentId, agentId)
                                .or().isNull(WikiKnowledgeBaseEntity::getAgentId))
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    public WikiKnowledgeBaseEntity getById(Long id) {
        return kbMapper.selectById(id);
    }

    @Transactional
    public WikiKnowledgeBaseEntity create(String name, String description, Long agentId) {
        return create(name, description, agentId, 1L);
    }

    @Transactional
    public WikiKnowledgeBaseEntity create(String name, String description, Long agentId, Long workspaceId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setName(name);
        entity.setDescription(description);
        entity.setAgentId(agentId);
        entity.setWorkspaceId(workspaceId);
        entity.setConfigContent(DEFAULT_CONFIG);
        entity.setStatus("active");
        entity.setPageCount(0);
        entity.setRawCount(0);
        kbMapper.insert(entity);
        log.info("[Wiki] Knowledge base created: id={}, name={}, workspaceId={}", entity.getId(), name, workspaceId);
        return entity;
    }

    @Transactional
    public WikiKnowledgeBaseEntity update(Long id, String name, String description, Long agentId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        if (name != null) entity.setName(name);
        if (description != null) entity.setDescription(description);
        if (agentId != null) entity.setAgentId(agentId);
        kbMapper.updateById(entity);
        return entity;
    }

    @Transactional
    public void updateConfig(Long id, String configContent) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setConfigContent(configContent);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateCounts(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        // counts will be updated by callers via specific methods
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateStatus(Long kbId, String status) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setStatus(status);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void incrementRawCount(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setRawCount(entity.getRawCount() + 1);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void setPageCount(Long kbId, int count) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setPageCount(count);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateSourceDirectory(Long id, String path) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setSourceDirectory(path);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void decrementRawCount(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setRawCount(Math.max(0, entity.getRawCount() - 1));
        kbMapper.updateById(entity);
    }

    /**
     * 更新知识库的 workspace 归属
     */
    public void updateWorkspaceId(Long kbId, Long workspaceId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity != null) {
            entity.setWorkspaceId(workspaceId);
            kbMapper.updateById(entity);
        }
    }

    @Transactional
    public void delete(Long id) {
        kbMapper.deleteById(id);
        log.info("[Wiki] Knowledge base deleted: id={}", id);
    }
}
