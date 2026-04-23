---
name: multi_agent_collaboration
version: "1.4.0"
description: "当任务需要多个 Agent 的专业能力协同完成时，编排多 Agent 并行或串行协作，整合各方结果。"
dependencies:
  tools:
    - listAvailableAgents
    - delegateToAgent
    - delegateParallel
---

# 多 Agent 协作

## 何时使用

当任务明显需要多个专业 Agent 共同完成，或用户明确要求多 Agent 协作时使用。

### 应该使用
- 任务可拆分为多个专业子域，每个子域有对应 Agent
- 多个独立子任务可以并行执行（节省时间）
- 需要来自不同 Agent 的结果进行综合分析
- 用户明确要求"让 A 和 B 一起做"

### 不应使用
- 一个 Agent 可以完成，无需分工
- 只是简单咨询，用 `chat_with_agent` 即可
- 刚收到某 Agent 的消息，不要立刻回调它（防死循环）

## 两种协作模式

### 模式一：串行（有依赖关系）

B 的任务需要 A 的结果时使用：

```
# 第一阶段：A 完成
result_a = delegateToAgent(
  agentName="research-agent",
  task="[来自 Agent coordinator 的请求] 收集最新 AI 大模型基准测试数据，返回原始数据表格。"
)

# 第二阶段：B 基于 A 的结果处理
result_b = delegateToAgent(
  agentName="data-analyst",
  task="[来自 Agent coordinator 的请求] 基于以下数据生成分析报告和可视化建议：\n\n" + result_a
)
```

### 模式二：并行（互相独立）

多个子任务之间没有依赖时使用，最多同时 3 个：

```
results = delegateParallel(
  tasksJson="[
    {\"agentName\": \"research-agent\", \"task\": \"[来自 Agent coordinator 的请求] 搜索竞品 A 的最新功能更新\"},
    {\"agentName\": \"data-analyst\",   \"task\": \"[来自 Agent coordinator 的请求] 分析我们产品上月用户留存数据\"},
    {\"agentName\": \"writer-agent\",   \"task\": \"[来自 Agent coordinator 的请求] 起草本次竞品分析报告的大纲\"}
  ]"
)
```

所有任务完成后一次性返回全部结果，再由当前 Agent 整合。

## 完整工作流程

### 第一步：查询可用 Agent

```
listAvailableAgents()
```

根据各 Agent 的描述分配任务。

### 第二步：判断串行 or 并行

| 判断条件 | 模式 |
|---------|------|
| 子任务 B 依赖子任务 A 的结果 | 串行 |
| 子任务互相独立，可同时进行 | 并行 |
| 混合（部分有依赖） | 先并行无依赖任务，再串行有依赖任务 |

### 第三步：分配并执行

使用对应模式（见上）。

### 第四步：整合结果

由当前 Agent（编排者）负责整合所有 Agent 的返回结果，形成最终回复。**不要把整合工作再委托给某个子 Agent。**

## 关键规则

- 任务说明中加 `[来自 Agent <名称> 的请求]` 帮助目标 Agent 识别来源
- 并行任务数量不超过 3 个；超过时按优先级分批
- 不让两个 Agent 互相调用对方（会形成死循环）
- 整合由编排者负责，不再向下委托
- 如需上下文连贯，在 `task` 中附带前一阶段的关键结论

## 与 chat_with_agent 的区别

| 技能 | 场景 |
|------|------|
| `chat_with_agent` | 一对一，咨询或单任务委托 |
| `multi_agent_collaboration` | 一对多，多 Agent 分工、并行或串行编排 |
