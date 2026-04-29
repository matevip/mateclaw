---
name: mateclaw_source_index
version: "1.0.0"
description: "将用户问题映射到 MateClaw 文档路径与源码入口，减少盲目搜索。回答'XX 功能在哪里实现'、'怎么修改 YY 逻辑'等源码定位问题。"
dependencies:
  tools:
    - readMateClawDoc
    - read_file
---

# MateClaw 源码导航

当用户询问"XX 功能在哪里实现"、"Agent 流程入口在哪"、"如何修改 YY 逻辑"等源码定位问题时使用本技能。

## 工作流程

### 第一步：查文档索引

```
readMateClawDoc(action="list")
```

根据用户问题关键词在文件列表中找到最相关文档。

### 第二步：读取架构文档

```
readMateClawDoc(action="read", path="zh/architecture.md")
```

或直接读 CLAUDE.md（项目根目录，包含最完整的包结构说明）：
```
read_file(filePath="CLAUDE.md")
```

### 第三步：返回定位结果

回答格式：**文件路径 : 行号范围 + 一句话说明入口作用**

示例：
> `agent/graph/StateGraphReActAgent.java` — ReAct 循环图的组装入口，连接 ReasoningNode → ActionNode → ObservationNode

## 核心路径速查表

### Agent 运行时

| 功能 | 文件路径 |
|------|---------|
| ReAct 图组装 | `agent/graph/StateGraphReActAgent.java` |
| Plan-Execute 图组装 | `agent/graph/plan/StateGraphPlanExecuteAgent.java` |
| 推理节点 | `agent/graph/node/ReasoningNode.java` |
| 动作节点 | `agent/graph/node/ActionNode.java` |
| 观察节点 | `agent/graph/node/ObservationNode.java` |
| 最终答案节点 | `agent/graph/node/FinalAnswerNode.java` |
| Agent 图构建器 | `agent/AgentGraphBuilder.java` |
| 上下文注入 | `agent/context/RuntimeContextInjector.java` |
| Token 估算 / 裁剪 | `agent/context/TokenEstimator.java` |

### 工具 & 审批

| 功能 | 文件路径 |
|------|---------|
| 工具注册中心 | `tool/ToolRegistry.java` |
| MCP 适配器 | `tool/mcp/` |
| 工具守卫规则 | `tool/guard/` |
| 人工审批流程 | `approval/ApprovalWorkflowService.java` |
| 审批 Controller | `approval/ApprovalController.java` |

### 渠道 & 对话

| 功能 | 文件路径 |
|------|---------|
| 渠道适配器接口 | `channel/ChannelAdapter.java` |
| Web SSE 聊天 | `channel/web/ChatController.java` |
| 渠道 Webhook | `channel/ChannelWebhookController.java` |
| 流追踪器 | `channel/web/ChatStreamTracker.java` |

### 记忆 & Wiki

| 功能 | 文件路径 |
|------|---------|
| 记忆生命周期协调者 | `memory/MemoryLifecycleMediator.java` |
| Dream 引擎 | `memory/dream/DreamService.java` |
| Dream 报告 Controller | `memory/controller/DreamController.java` |
| Wiki 处理流水线 | `wiki/service/WikiProcessingService.java` |
| Wiki Controller | `wiki/controller/WikiController.java` |

### 技能运行时

| 功能 | 文件路径 |
|------|---------|
| 技能运行时服务 | `skill/runtime/SkillRuntimeService.java` |
| 技能安全扫描 | `skill/security/SkillSecurityService.java` |
| 技能 Controller | `skill/controller/SkillController.java` |

### 数据库 & 配置

| 功能 | 文件路径 |
|------|---------|
| Flyway 迁移（H2） | `resources/db/migration/h2/` |
| Flyway 迁移（MySQL） | `resources/db/migration/mysql/` |
| 种子数据（中文） | `resources/db/data-mysql-zh.sql` |
| 系统设置 | `system/service/SystemSettingService.java` |

## 注意

- 路径均相对于 `mateclaw-server/src/main/java/vip/mate/`（Java 文件）或 `mateclaw-server/src/main/resources/`（资源文件）
- 如找不到精确文件，先用 `readMateClawDoc` 搜索，再用 `read_file` 读取 CLAUDE.md 获取最新架构描述
