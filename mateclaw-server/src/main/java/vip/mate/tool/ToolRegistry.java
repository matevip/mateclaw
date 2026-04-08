package vip.mate.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import org.springframework.ai.tool.ToolCallbackProvider;
import vip.mate.agent.AgentToolSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * 管理所有可供 Agent 使用的工具（内置 + 自定义）
 * 工具启用状态由数据库 mate_tool 表的 enabled 字段控制
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ToolMapper toolMapper;

    /**
     * 获取所有已启用的工具 Bean（Spring AI @Tool 注解方式）
     * 通过数据库 enabled 标志过滤，确保 UI 开关真正生效
     */
    public List<Object> getEnabledTools() {
        // 1. 从数据库获取明确禁用的 beanName 黑名单
        //    逻辑：只有 DB 中存在记录且 enabled=false 的才跳过
        //    DB 中没有记录的 bean 默认启用（向后兼容 + 新工具自动可用）
        Set<String> disabledBeanNames = toolMapper.selectList(
                new LambdaQueryWrapper<ToolEntity>()
                        .eq(ToolEntity::getEnabled, false)
                        .isNotNull(ToolEntity::getBeanName)
        ).stream()
                .map(ToolEntity::getBeanName)
                .collect(Collectors.toSet());

        List<Object> tools = new ArrayList<>();

        // 2. 扫描 Spring 容器中所有带 @Tool 方法的 Bean
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Component.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();

            boolean hasToolMethod = java.util.Arrays.stream(bean.getClass().getMethods())
                    .anyMatch(m -> m.isAnnotationPresent(Tool.class));

            if (hasToolMethod) {
                // 3. 只有 DB 中明确 enabled=false 的才跳过，其余全部启用
                if (disabledBeanNames.contains(beanName)) {
                    log.debug("Skipped disabled tool bean: {} (beanName={})", bean.getClass().getSimpleName(), beanName);
                } else {
                    tools.add(bean);
                    log.debug("Registered tool bean: {} (beanName={})", bean.getClass().getSimpleName(), beanName);
                }
            }
        }

        log.info("Total enabled tools: {}", tools.size());
        return tools;
    }

    /**
     * 获取统一的 AgentToolSet（包含 @Tool Bean + ToolCallbackProvider）
     * <p>
     * 同时收集：
     * 1. 当前启用的 @Tool bean
     * 2. 当前容器中所有 ToolCallbackProvider（MCP server 等）
     */
    public AgentToolSet getEnabledToolSet() {
        List<Object> toolBeans = getEnabledTools();
        Map<String, ToolCallbackProvider> providerBeans = applicationContext.getBeansOfType(ToolCallbackProvider.class);
        List<ToolCallbackProvider> providers = new ArrayList<>(providerBeans.values());
        log.info("Building AgentToolSet: toolBeans={}, providers={}", toolBeans.size(), providers.size());
        return AgentToolSet.from(toolBeans, providers);
    }

    /**
     * 获取数据库中的工具配置列表（全部）
     */
    public List<ToolEntity> listToolEntities() {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolEntity>()
                .orderByDesc(ToolEntity::getBuiltin)
                .orderByAsc(ToolEntity::getName));
    }

    /**
     * 获取已启用的工具配置列表
     */
    public List<ToolEntity> listEnabledToolEntities() {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolEntity>()
                .eq(ToolEntity::getEnabled, true)
                .orderByAsc(ToolEntity::getName));
    }
}
