package vip.mate.skill.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill 工作区启动初始化
 * <p>
 * 1. 确保 workspace root 目录存在
 * 2. 将 classpath 下预置技能同步到 workspace
 *    - 首次：创建并同步
 *    - 后续：比对 SKILL.md frontmatter 中的 version 字段，
 *      bundled version 更高时归档旧版本并覆盖升级
 * <p>
 * Order(195) — 在 DatabaseBootstrapRunner(200) 之前执行。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(195)
@RequiredArgsConstructor
public class SkillWorkspaceBootstrapRunner implements ApplicationRunner {

    private final SkillWorkspaceManager workspaceManager;
    private final BundledSkillSyncer bundledSkillSyncer;

    @Override
    public void run(ApplicationArguments args) {
        var root = workspaceManager.getWorkspaceRoot();
        log.info("Skill workspace root ready: {}", root);

        // 同步 classpath 下预置技能到 workspace
        List<String> synced = bundledSkillSyncer.sync();
        if (!synced.isEmpty()) {
            log.info("Synced {} bundled skill(s) to workspace: {}", synced.size(), synced);
        }
    }
}
