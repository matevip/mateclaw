package vip.mate.tool.search;

import vip.mate.system.model.SystemSettingsDTO;

import java.util.List;

/**
 * 搜索提供商接口 — 所有搜索 provider（含 keyless）统一实现此接口
 *
 * @author MateClaw Team
 */
public interface SearchProvider {

    /** 提供商唯一 ID，如 "serper"、"tavily"、"duckduckgo"、"searxng" */
    String id();

    /** 显示名称 */
    String label();

    /** 是否需要 API Key / Credential */
    boolean requiresCredential();

    /**
     * 自动探测排序优先级（升序）。
     * <p>有 key 的 provider 用较大值（300+），keyless 用较小值（100+），
     * 但实际调度时"有 credential 的 provider"优先于 keyless，
     * 此字段仅在同类 provider 之间排序。
     */
    int autoDetectOrder();

    /**
     * 判断该 provider 在当前配置下是否可用
     * （key 已配置 / keyless provider 已启用等）
     */
    boolean isAvailable(SystemSettingsDTO config);

    /**
     * 执行搜索，返回结构化结果列表
     *
     * @param query  搜索关键词
     * @param config 系统配置（包含 key、baseUrl 等）
     * @return 搜索结果列表；不应返回 null，失败时抛异常
     */
    List<SearchResult> search(String query, SystemSettingsDTO config);
}
