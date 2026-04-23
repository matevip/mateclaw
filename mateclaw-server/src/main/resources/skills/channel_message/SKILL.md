---
name: channel_message
version: "1.3.0"
description: "当需要主动向用户、会话或渠道单向推送消息时使用。适用于任务完成通知、定时提醒、异步结果回推等场景。"
dependencies:
  tools:
    - execute_shell_command
---

# 渠道消息推送

## 何时使用

仅在以下情况使用，这是**单向推送**，不会收到回复：

### 应该使用
- 用户明确要求"向某个渠道 / 会话发送消息"
- 异步任务完成后主动通知用户
- 定时提醒、告警、状态更新
- 将后台任务结果推送回指定会话

### 不应使用
- 当前对话中的正常回复（直接回复即可）
- 需要等待用户回复的双向交互
- 目标渠道或会话不明确时（先询问用户）

## 支持渠道

`console`、`dingtalk`、`feishu`、`telegram`、`discord`、`qq`、`slack`

## 工作流程

### 第一步：查询目标会话

**macOS / Linux：**
```
execute_shell_command(
  command="mateclaw chats list --agent-id <agentId> --channel <channel>"
)
```

**Windows：**
```
execute_shell_command(
  command="mateclaw.exe chats list --agent-id <agentId> --channel <channel>"
)
```

从返回结果中获取 `user_id` 和 `session_id`。有多个会话时，优先选 `updated_at` 最近的。

### 第二步：发送消息

**macOS / Linux：**
```
execute_shell_command(
  command="mateclaw channels send --agent-id <agentId> --channel <channel> --target-user <userId> --target-session <sessionId> --text \"消息内容\""
)
```

**Windows（PowerShell）：**
```
execute_shell_command(
  command="mateclaw.exe channels send --agent-id <agentId> --channel <channel> --target-user <userId> --target-session <sessionId> --text '消息内容'"
)
```

### 必填参数一览

| 参数 | 说明 |
|------|------|
| `--agent-id` | 当前 Agent 的 ID |
| `--channel` | 目标渠道名称（见支持渠道列表） |
| `--target-user` | 目标用户 ID（从 `chats list` 获取） |
| `--target-session` | 目标会话 ID（从 `chats list` 获取） |
| `--text` | 消息内容 |

## 常见场景示例

### 任务完成通知

```
execute_shell_command(
  command="mateclaw chats list --agent-id task-bot --channel dingtalk"
)
# 从结果中取 user_id / session_id，然后：
execute_shell_command(
  command="mateclaw channels send --agent-id task-bot --channel dingtalk --target-user alice --target-session alice_dt_001 --text \"✅ 数据分析已完成，结果已保存到 report.xlsx\""
)
```

### 按用户筛选会话

```
execute_shell_command(
  command="mateclaw chats list --agent-id notify-bot --user-id alice"
)
```

## mateclaw CLI 未安装时的降级处理

若 `mateclaw` 命令不可用：

1. 检测：
```
execute_shell_command(command="which mateclaw || where mateclaw")
```

2. 如果未安装，告知用户：
> mateclaw CLI 未找到，无法主动推送消息。请确认 MateClaw 已正确安装并将 CLI 加入 PATH。安装后重试。

## 常见错误

- **缺少必填参数**：5 个参数（agent-id、channel、target-user、target-session、text）缺一不可
- **没有先查 session 就发送**：不要猜 target-user 和 target-session，必须先查
- **把正常对话回复当成推送**：当前会话直接回复不需要用本技能
- **期望收到回复**：`channels send` 是单向推送，不返回用户回复
