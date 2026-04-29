package vip.mate.agent.graph.state;

/**
 * ReAct 状态图终止原因枚举
 *
 * @author MateClaw Team
 */
public enum FinishReason {

    /** 正常完成：LLM 直接给出最终回答 */
    NORMAL("normal"),

    /** 经过 summarizing 后完成 */
    SUMMARIZED("summarized"),

    /** 达到最大迭代次数后强制收束 */
    MAX_ITERATIONS_REACHED("max_iterations_reached"),

    /** 发生错误后降级回答 */
    ERROR_FALLBACK("error_fallback"),

    /** 用户主动停止 */
    STOPPED("stopped"),

    /** RFC-052: a tool with returnDirect=true short-circuited the loop;
     *  result was delivered to the user without re-entering the LLM. */
    RETURN_DIRECT("return_direct");

    private final String value;

    FinishReason(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
