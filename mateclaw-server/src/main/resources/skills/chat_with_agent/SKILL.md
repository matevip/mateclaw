---
name: chat_with_agent
version: "1.2.0"
description: "当需要咨询其他 Agent、寻求帮助，或用户明确要求某个 Agent 参与时使用。支持单次委托和多任务并行委托。"
dependencies:
  tools:
    - listAvailableAgents
    - delegateToAgent
    - delegateParallel
---

# 与 Agent 对话

## 何时使用

当你需要**向另一个 Agent 询问问题、寻求帮助、请求方案、请求复核**，或用户明确要求某个 Agent 参与时使用。

### 应该使用
- 需要另一个 Agent 的专长、判断或第二意见
- 需要向某个 Agent 请求方案、复核或建议
- 用户明确要求某个 Agent 参与或协助
- 多个独立子任务需要并行分配给不同 Agent

### 不应使用
- 你自己可以直接完成，且用户没有明确要求调用其他 Agent
- 只是普通问答，不需要专门 Agent
- 刚收到某个 Agent 的消息，**不要立刻回调同一个 Agent**（防止死循环）

## 工作流程

### 第一步：查询可用 Agent

```
listAvailableAgents()
```

返回所有已启用 Agent 的名称、类型和描述，根据描述选择最合适的 Agent。

### 第二步A：单次委托（串行）

```
delegateToAgent(
  agentName="data-analyst",
  task="[来自 Agent my-agent 的请求] 请帮我分析以下销售数据，给出环比趋势摘要：..."
)
```

- `agentName`：目标 Agent 的名称（从 `listAvailableAgents()` 返回值中取）
- `task`：发送给目标 Agent 的完整任务描述
- 建议在 `task` 开头加 `[来自 Agent <自身名称> 的请求]` 便于对方识别来源

### 第二步B：并行委托（多个独立任务同时进行）

最多同时委托 3 个 Agent：

```
delegateParallel(
  tasksJson="[
    {\"agentName\": \"research-agent\", \"task\": \"[来自 Agent coordinator 的请求] 搜索 AI 行业最新融资动态\"},
    {\"agentName\": \"data-analyst\", \"task\": \"[来自 Agent coordinator 的请求] 分析上季度销售数据趋势\"}
  ]"
)
```

`tasksJson` 是 JSON 数组字符串，每个元素包含 `agentName` 和 `task`。

## 决策规则

1. **用户明确要求调用某 Agent** → 先 `listAvailableAgents()` 确认名称，不要猜
2. **能自己完成** → 不调用
3. **多个互相独立的子任务** → 用 `delegateParallel`，不要串行逐个调用
4. **不超过 3 个并行** → 超过时按优先级分批
5. **收到 Agent B 回复后** → 不要立刻回调 Agent B

## 与 make_plan 的区别

| 技能 | 用途 |
|------|------|
| `chat_with_agent` | 向 Agent 咨询、委托、获取结果 |
| `make_plan` | 专门向更强 Agent 索要执行计划（由自己执行） |

## 注意事项

- `delegateToAgent` 是同步阻塞调用，等待目标 Agent 完成后返回结果
- `delegateParallel` 并发执行，等所有任务完成后一次性返回所有结果
- MateClaw 当前不支持跨调用的会话 session 续接，每次 `delegateToAgent` 是独立对话
- 如需上下文连贯，在 `task` 参数中附带上一次的关键结论
