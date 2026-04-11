package vip.mate.skill.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mate.skill.installer.model.*;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceEvent;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 安装核心服务
 * <p>
 * 管理从外部源（GitHub / ClawHub）安装 skill 的完整流程：
 * URL 解析 → bundle 获取 → workspace 落盘 → 数据库注册 → 运行时刷新。
 * <p>
 * 支持异步安装（task_id 轮询模式），参考 MateClaw 实现。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillInstaller {

    private final BundleResolver bundleResolver;
    private final SkillHubClient skillHubClient;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillService skillService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 安装任务追踪 */
    private final ConcurrentHashMap<String, InstallTask> tasks = new ConcurrentHashMap<>();

    /**
     * 启动异步安装任务
     */
    public InstallTask startInstall(InstallRequest request) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        InstallTask task = InstallTask.create(taskId, request.getBundleUrl());
        tasks.put(taskId, task);

        doInstallAsync(taskId, request);
        return task;
    }

    /**
     * 获取安装任务状态
     */
    public InstallTask getTaskStatus(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 取消安装任务
     */
    public void cancelTask(String taskId) {
        InstallTask task = tasks.get(taskId);
        if (task != null && task.getStatus() == InstallTask.InstallStatus.INSTALLING) {
            task.setCancelRequested(true);
            task.markCancelled();
            log.info("Install task {} cancelled", taskId);
        }
    }

    /**
     * 卸载 skill（归档 workspace + 删除数据库记录）
     */
    public void uninstall(String skillName) {
        // 先在数据库中查找
        List<SkillEntity> skills = skillService.listSkills();
        SkillEntity target = skills.stream()
                .filter(s -> s.getName().equals(skillName))
                .findFirst()
                .orElse(null);

        if (target != null) {
            skillService.deleteSkill(target.getId());
        }

        // workspace 归档已在 SkillService.deleteSkill 中处理
        log.info("Uninstalled skill: {}", skillName);
    }

    /**
     * 搜索 ClawHub 市场（委托给 SkillHubClient）
     */
    public List<HubSkillInfo> searchHub(String query, int limit) {
        try {
            return skillHubClient.search(query, limit);
        } catch (Exception e) {
            log.warn("Hub search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 异步安装流程 ====================

    @Async
    public CompletableFuture<Void> doInstallAsync(String taskId, InstallRequest request) {
        InstallTask task = tasks.get(taskId);
        if (task == null) return CompletableFuture.completedFuture(null);

        task.markInstalling();

        try {
            // 1. 解析 bundle
            SkillBundle bundle = bundleResolver.resolve(request.getBundleUrl(), request.getVersion());
            if (bundle == null) {
                task.markFailed("Failed to resolve skill bundle from: " + request.getBundleUrl());
                return CompletableFuture.completedFuture(null);
            }

            if (task.isCancelRequested()) {
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 2. 确定 skill 名称
            String skillName = request.getTargetName() != null ? request.getTargetName() : bundle.name();
            if (skillName == null || skillName.isBlank()) {
                task.markFailed("Cannot determine skill name from bundle");
                return CompletableFuture.completedFuture(null);
            }

            // 3. 检查是否已存在
            boolean exists = skillService.listSkills().stream()
                    .anyMatch(s -> s.getName().equals(skillName));
            if (exists && !Boolean.TRUE.equals(request.getOverwrite())) {
                task.markFailed("Skill '" + skillName + "' already exists. Set overwrite=true to replace.");
                return CompletableFuture.completedFuture(null);
            }

            if (task.isCancelRequested()) {
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 4. 写入 workspace 目录
            //    overwrite 时先清理旧 references/ 和 scripts/，防止残留过期文件
            if (exists) {
                workspaceManager.cleanWorkspaceDataDirs(skillName);
            }
            workspaceManager.initWorkspace(skillName, bundle.content());

            // 写入 references/
            if (bundle.references() != null) {
                for (var entry : bundle.references().entrySet()) {
                    workspaceManager.writeWorkspaceFile(skillName, "references/" + entry.getKey(), entry.getValue());
                }
            }

            // 写入 scripts/
            if (bundle.scripts() != null) {
                for (var entry : bundle.scripts().entrySet()) {
                    workspaceManager.writeWorkspaceFile(skillName, "scripts/" + entry.getKey(), entry.getValue());
                }
            }

            // cancel check: 文件已落盘，但数据库尚未写入 —— 归档已写入的目录后退出
            if (task.isCancelRequested()) {
                workspaceManager.archiveWorkspace(skillName);
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 5. 注册/更新数据库
            SkillEntity skillEntity;
            if (exists) {
                // 更新已有记录
                skillEntity = skillService.listSkills().stream()
                        .filter(s -> s.getName().equals(skillName))
                        .findFirst().orElseThrow();
                skillEntity.setSkillContent(bundle.content());
                skillEntity.setDescription(bundle.description());
                skillEntity.setVersion(bundle.version());
                skillEntity.setAuthor(bundle.author());
                skillEntity.setIcon(bundle.icon());
                skillEntity.setConfigJson(buildConfigJson(bundle));
                if (Boolean.TRUE.equals(request.getEnable())) {
                    skillEntity.setEnabled(true);
                }
                skillService.updateSkill(skillEntity);
            } else {
                // 创建新记录
                skillEntity = new SkillEntity();
                skillEntity.setName(skillName);
                skillEntity.setDescription(bundle.description());
                skillEntity.setSkillType("dynamic");
                skillEntity.setVersion(bundle.version());
                skillEntity.setAuthor(bundle.author());
                skillEntity.setIcon(bundle.icon());
                skillEntity.setSkillContent(bundle.content());
                skillEntity.setConfigJson(buildConfigJson(bundle));
                skillEntity.setEnabled(Boolean.TRUE.equals(request.getEnable()));
                skillService.createSkill(skillEntity);
            }

            // cancel check: DB 已写入，此时取消不再回滚数据库，但标记任务为 cancelled
            if (task.isCancelRequested()) {
                task.markCancelled();
                return CompletableFuture.completedFuture(null);
            }

            // 6. 发布事件
            eventPublisher.publishEvent(new SkillWorkspaceEvent(
                    skillName, SkillWorkspaceEvent.Type.INSTALLED,
                    workspaceManager.resolveConventionPath(skillName)));

            // 7. 完成
            task.markCompleted(InstallResult.builder()
                    .name(skillName)
                    .enabled(Boolean.TRUE.equals(request.getEnable()))
                    .sourceUrl(bundle.sourceUrl())
                    .sourceType(bundle.sourceType())
                    .build());

            log.info("Skill '{}' installed successfully from {}", skillName, bundle.sourceUrl());

        } catch (Exception e) {
            log.error("Install task {} failed: {}", taskId, e.getMessage(), e);
            task.markFailed(e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 同步安装 SkillBundle（用于 ZIP 上传等本地解析场景，无需异步任务）
     *
     * @return 安装结果 Map（skillId, name, version, filesCount）
     */
    public Map<String, Object> installFromBundle(SkillBundle bundle, boolean enable, boolean overwrite, String targetName) {
        String skillName = (targetName != null && !targetName.isBlank()) ? targetName : bundle.name();
        if (skillName == null || skillName.isBlank()) {
            throw new vip.mate.exception.MateClawException("err.skill.name_required", "Cannot determine skill name from bundle");
        }

        boolean exists = skillService.listSkills().stream()
                .anyMatch(s -> s.getName().equals(skillName));
        if (exists && !overwrite) {
            throw new vip.mate.exception.MateClawException("err.skill.name_exists",
                    "Skill '" + skillName + "' already exists. Enable overwrite to replace.");
        }

        // 写入 workspace
        if (exists) {
            workspaceManager.cleanWorkspaceDataDirs(skillName);
        }
        workspaceManager.initWorkspace(skillName, bundle.content());

        if (bundle.references() != null) {
            for (var entry : bundle.references().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("references/")) key = "references/" + key;
                workspaceManager.writeWorkspaceFile(skillName, key, entry.getValue());
            }
        }
        if (bundle.scripts() != null) {
            for (var entry : bundle.scripts().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("scripts/")) key = "scripts/" + key;
                workspaceManager.writeWorkspaceFile(skillName, key, entry.getValue());
            }
        }

        // 注册/更新 DB
        SkillEntity skillEntity;
        if (exists) {
            skillEntity = skillService.listSkills().stream()
                    .filter(s -> s.getName().equals(skillName))
                    .findFirst().orElseThrow();
            skillEntity.setSkillContent(bundle.content());
            skillEntity.setDescription(bundle.description());
            skillEntity.setVersion(bundle.version());
            skillEntity.setAuthor(bundle.author());
            skillEntity.setIcon(bundle.icon());
            skillEntity.setConfigJson(buildConfigJson(bundle));
            if (enable) skillEntity.setEnabled(true);
            skillService.updateSkill(skillEntity);
        } else {
            skillEntity = new SkillEntity();
            skillEntity.setName(skillName);
            skillEntity.setDescription(bundle.description());
            skillEntity.setSkillType("dynamic");
            skillEntity.setVersion(bundle.version());
            skillEntity.setAuthor(bundle.author());
            skillEntity.setIcon(bundle.icon());
            skillEntity.setSkillContent(bundle.content());
            skillEntity.setConfigJson(buildConfigJson(bundle));
            skillEntity.setEnabled(enable);
            skillService.createSkill(skillEntity);
        }

        eventPublisher.publishEvent(new SkillWorkspaceEvent(
                skillName, SkillWorkspaceEvent.Type.INSTALLED,
                workspaceManager.resolveConventionPath(skillName)));

        int filesCount = (bundle.references() != null ? bundle.references().size() : 0)
                + (bundle.scripts() != null ? bundle.scripts().size() : 0) + 1;

        log.info("Skill '{}' installed from ZIP (v{}, {} files)", skillName, bundle.version(), filesCount);

        return Map.of(
                "skillId", skillEntity.getId(),
                "name", skillName,
                "version", bundle.version() != null ? bundle.version() : "",
                "filesCount", filesCount
        );
    }

    // ==================== 工具方法 ====================

    private String buildConfigJson(SkillBundle bundle) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("upstream", bundle.sourceType());
            config.put("entryFile", "SKILL.md");

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", bundle.sourceType());
            source.put("url", bundle.sourceUrl());
            source.put("installedAt", LocalDateTime.now().toString());
            source.put("installedVersion", bundle.version());
            config.put("source", source);

            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            return "{\"upstream\":\"" + bundle.sourceType() + "\"}";
        }
    }

}
