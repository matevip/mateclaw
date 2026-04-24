package vip.mate.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import vip.mate.agent.AgentToolSet;
import vip.mate.i18n.I18nService;
import vip.mate.i18n.LocaleAwareToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
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
    private final I18nService i18nService;

    // ==================== Plugin Tools ====================

    /** Plugin-registered tool entries with lazy availability checks */
    private final CopyOnWriteArrayList<PluginToolEntry> pluginTools = new CopyOnWriteArrayList<>();

    /** A tool entry registered by a plugin */
    public record PluginToolEntry(ToolCallback callback, Supplier<Boolean> availabilityCheck) {}

    /**
     * Register a tool from a plugin with an availability check.
     * The check is evaluated lazily each time the tool set is built.
     */
    public void registerPluginTool(ToolCallback callback, Supplier<Boolean> availabilityCheck) {
        pluginTools.add(new PluginToolEntry(callback, availabilityCheck != null ? availabilityCheck : () -> true));
        log.info("Plugin tool registered: {}", callback.getToolDefinition().name());
    }

    /**
     * Unregister a plugin tool by name.
     */
    public void unregisterPluginTool(String toolName) {
        pluginTools.removeIf(entry -> entry.callback().getToolDefinition().name().equals(toolName));
        log.info("Plugin tool unregistered: {}", toolName);
    }

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

        // 对内置工具 callback 应用 i18n 描述包装
        List<ToolCallback> localizedCallbacks = new ArrayList<>();
        for (Object bean : toolBeans) {
            ToolCallback[] cbs = ToolCallbacks.from(bean);
            for (ToolCallback cb : cbs) {
                String toolName = cb.getToolDefinition().name();
                String descKey = "tool." + toolName + ".desc";
                String localizedDesc = i18nService.msg(descKey);
                // 如果 key 被解析（不等于 key 本身），使用本地化描述
                if (!localizedDesc.equals(descKey)) {
                    localizedCallbacks.add(new LocaleAwareToolCallback(cb, localizedDesc));
                } else {
                    localizedCallbacks.add(cb);
                }
            }
        }

        // MCP provider callbacks 不做 i18n 包装（MCP 工具自行管理描述）
        for (ToolCallbackProvider provider : providers) {
            ToolCallback[] cbs = provider.getToolCallbacks();
            if (cbs != null) {
                Collections.addAll(localizedCallbacks, cbs);
            }
        }

        // Plugin tool callbacks — evaluate availability checks lazily
        int pluginToolCount = 0;
        for (PluginToolEntry entry : pluginTools) {
            try {
                if (Boolean.TRUE.equals(entry.availabilityCheck().get())) {
                    localizedCallbacks.add(entry.callback());
                    pluginToolCount++;
                } else {
                    log.debug("Plugin tool excluded (availability check failed): {}",
                            entry.callback().getToolDefinition().name());
                }
            } catch (Exception e) {
                log.warn("Plugin tool availability check failed for {}: {}",
                        entry.callback().getToolDefinition().name(), e.getMessage());
            }
        }

        log.info("Building AgentToolSet: toolBeans={}, providers={}, pluginTools={}, totalCallbacks={}",
                toolBeans.size(), providers.size(), pluginToolCount, localizedCallbacks.size());
        return AgentToolSet.fromCallbacks(toolBeans, localizedCallbacks);
    }

    /**
     * Returns the set of runtime tool function names — i.e. the exact identifiers
     * LLMs and skill manifests use to reference tools (the @Tool method name, the
     * MCP tool name, or the registered plugin tool name).
     *
     * <p>Use this for dependency / availability checks instead of querying the
     * {@code mate_tool} table directly: the DB overlay stores class/bean names,
     * which do not match the function-name vocabulary skills declare against.
     */
    public Set<String> availableFunctionNames() {
        Set<String> names = new java.util.HashSet<>();
        AgentToolSet toolSet = getEnabledToolSet();
        for (ToolCallback cb : toolSet.callbacks()) {
            names.add(cb.getToolDefinition().name());
        }
        return names;
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
