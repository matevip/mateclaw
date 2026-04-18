package vip.mate.agent.binding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.service.AgentBindingService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 issue #8 的重绑定 bug 回归：在去掉 @TableLogic 之前，
 * bind → unbind → rebind 会因为 uk_agent_tool / uk_agent_skill 唯一索引
 * 与软删除并存而抛 DuplicateKeyException。本测试断言修复后各条路径都成功，
 * 同时断言合法的唯一约束仍被保留（不能让修 bug 顺带破坏唯一性）。
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:binding_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class AgentBindingServiceTest {

    private static final AtomicLong AGENT_ID_SEQ = new AtomicLong(9_000_000L);

    @Autowired
    private AgentBindingService bindingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long agentId;

    @BeforeEach
    void setUp() {
        // 每个用例用独立 agent_id，互不干扰
        agentId = AGENT_ID_SEQ.getAndIncrement();
    }

    @Test
    @DisplayName("bindTool → unbindTool → bindTool 同一 (agent, tool) 不抛异常")
    void rebindToolAfterUnbind() {
        bindingService.bindTool(agentId, "echo");
        bindingService.unbindTool(agentId, "echo");
        assertDoesNotThrow(() -> bindingService.bindTool(agentId, "echo"));

        Set<String> names = bindingService.getBoundToolNames(agentId);
        assertNotNull(names);
        assertTrue(names.contains("echo"));
    }

    @Test
    @DisplayName("bindSkill → unbindSkill → bindSkill 同一 (agent, skill) 不抛异常")
    void rebindSkillAfterUnbind() {
        long skillId = 7_777_001L;
        bindingService.bindSkill(agentId, skillId);
        bindingService.unbindSkill(agentId, skillId);
        assertDoesNotThrow(() -> bindingService.bindSkill(agentId, skillId));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertTrue(ids.contains(skillId));
    }

    @Test
    @DisplayName("setToolBindings 连续调用两次相同列表不抛异常，状态收敛")
    void setToolBindingsIsIdempotent() {
        List<String> desired = List.of("tool_a", "tool_b");
        bindingService.setToolBindings(agentId, desired);
        assertDoesNotThrow(() -> bindingService.setToolBindings(agentId, desired));

        Set<String> names = bindingService.getBoundToolNames(agentId);
        assertNotNull(names);
        assertEquals(2, names.size());
        assertTrue(names.containsAll(desired));
    }

    @Test
    @DisplayName("setSkillBindings 连续调用两次相同列表不抛异常，状态收敛")
    void setSkillBindingsIsIdempotent() {
        List<Long> desired = List.of(7_777_101L, 7_777_102L);
        bindingService.setSkillBindings(agentId, desired);
        assertDoesNotThrow(() -> bindingService.setSkillBindings(agentId, desired));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(desired));
    }

    @Test
    @DisplayName("唯一性回归：同一 (agent, tool) 直接 INSERT 第二行仍被唯一索引拦截")
    void uniqueIndexStillEnforcedForTool() {
        bindingService.bindTool(agentId, "unique_probe");

        // 绕过 service，直接 INSERT 第二行，断言 DB 层唯一约束仍生效
        assertThrows(DuplicateKeyException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO mate_agent_tool " +
                                "(id, agent_id, tool_name, enabled, create_time, update_time, deleted) " +
                                "VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                        System.nanoTime(), agentId, "unique_probe"
                )
        );
    }

    @Test
    @DisplayName("唯一性回归：同一 (agent, skill) 直接 INSERT 第二行仍被唯一索引拦截")
    void uniqueIndexStillEnforcedForSkill() {
        long skillId = 7_777_201L;
        bindingService.bindSkill(agentId, skillId);

        assertThrows(DuplicateKeyException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO mate_agent_skill " +
                                "(id, agent_id, skill_id, enabled, create_time, update_time, deleted) " +
                                "VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                        System.nanoTime(), agentId, skillId
                )
        );
    }

    @Test
    @DisplayName("unbindTool 后 DB 里真的没行（物理 delete，不是软删留 deleted=1）")
    void unbindPhysicallyRemovesRow() {
        bindingService.bindTool(agentId, "physical_check");
        bindingService.unbindTool(agentId, "physical_check");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_agent_tool WHERE agent_id = ? AND tool_name = ?",
                Integer.class, agentId, "physical_check"
        );
        assertNotNull(count);
        assertEquals(0, count, "unbind 应该物理删除，而不是软删（软删会留 deleted=1 行，占用唯一索引槽位导致 rebind 失败）");
    }
}
