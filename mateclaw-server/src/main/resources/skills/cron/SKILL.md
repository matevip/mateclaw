---
name: cron
version: "1.4.0"
description: "仅在需要未来定时执行或周期执行任务时使用。通过 create_cron_job / list_cron_jobs / toggle_cron_job / delete_cron_job 管理定时任务。"
dependencies:
  tools:
    - create_cron_job
    - list_cron_jobs
    - toggle_cron_job
    - delete_cron_job
---

# 定时任务管理

## 何时使用

只有在需要**未来某个时间自动执行**，或**按周期重复执行**时使用本技能。

### 应该使用
- 用户要求"每天 / 每周 / 每小时"执行某件事
- 用户要求"明天 9 点 / 下周一 / 某个时间"自动提醒或执行
- 需要长期周期性通知、检查、汇报

### 不应使用
- 只是**现在立即执行一次**
- 只是当前会话中的正常回复
- 用户没有明确执行时间或周期

## 工具速查

| 操作 | 工具调用 |
|------|---------|
| 查看所有任务 | `list_cron_jobs()` |
| 创建任务 | `create_cron_job(name, cronExpression, triggerMessage, timezone)` |
| 暂停任务 | `toggle_cron_job(jobId, enabled=false)` |
| 恢复任务 | `toggle_cron_job(jobId, enabled=true)` |
| 删除任务 | `delete_cron_job(jobId)` |

## 工作流程

### 第一步：确认必要信息

创建前**必须**确认以下信息，缺一不可：

- 任务名称（`name`）
- 执行周期（`cronExpression`，5 段 cron 表达式）
- 触发消息（`triggerMessage`，任务触发时 Agent 收到的提示词）
- 时区（`timezone`，可选，默认 `Asia/Shanghai`）

信息不全时先追问用户，不要用占位符创建任务。

### 第二步：创建任务

```
create_cron_job(
  name="每日早报",
  cronExpression="0 9 * * *",
  triggerMessage="请获取今日财经新闻并发送摘要给用户",
  timezone="Asia/Shanghai"
)
```

### 第三步：管理任务

查看所有任务：
```
list_cron_jobs()
```

暂停（从返回的任务列表中获取 jobId）：
```
toggle_cron_job(jobId=123, enabled=false)
```

恢复：
```
toggle_cron_job(jobId=123, enabled=true)
```

删除：
```
delete_cron_job(jobId=123)
```

## Cron 表达式参考（5 段格式）

```
格式：分 时 日 月 周
0 9 * * *        每天 09:00
0 */2 * * *      每 2 小时整点
30 8 * * 1-5     工作日 08:30
0 0 * * 0        每周日零点
*/15 * * * *     每 15 分钟
0 9,18 * * *     每天 09:00 和 18:00
```

## 常见错误

- **缺少信息就创建**：必须先确认周期、名称和触发消息
- **只是立即执行一次**：不需要创建 cron，直接执行即可
- **时区混淆**：默认 `Asia/Shanghai`；若用户在其他时区，明确指定

## 安全须知

- `triggerMessage` 是 Agent 触发时收到的提示词，不是发给用户的消息
- 避免创建执行频率极高（< 5 分钟）的任务，除非用户明确需要
