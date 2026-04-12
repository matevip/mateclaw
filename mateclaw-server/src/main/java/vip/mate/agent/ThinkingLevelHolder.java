package vip.mate.agent;

/**
 * 请求级思考深度的 ThreadLocal 持有器。
 * <p>
 * 用于将前端选择的思考级别从 AgentService 传递到 ReasoningNode，
 * 避免修改 Agent 缓存实例或 StructuredStreamCapable 接口。
 * <p>
 * 支持的值：off / low / medium / high / max，null 表示跟随模型默认。
 *
 * @author MateClaw Team
 */
public final class ThinkingLevelHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ThinkingLevelHolder() {}

    public static void set(String level) {
        HOLDER.set(level);
    }

    /**
     * 获取当前请求的思考级别，null 表示未设置（跟随模型默认）
     */
    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
