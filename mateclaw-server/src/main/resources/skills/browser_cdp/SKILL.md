---
name: browser_cdp
version: "1.2.0"
description: "通过 Chrome DevTools Protocol (CDP) 连接已运行的浏览器，或扫描本机 CDP 端口，用于远程调试与多工具共享浏览器实例。"
dependencies:
  tools:
    - browser_use
    - execute_shell_command
---

# 浏览器 CDP 使用

仅在用户**明确**提出以下需求时使用本技能：
- 连接到已打开的 Chrome（附着现有实例）
- 扫描本机有哪些 CDP 端口可用
- 用固定调试端口启动浏览器
- 让多个工具 / Agent 共享同一浏览器

**普通"打开浏览器"需求请用 `browser_visible` 技能。**

## 场景一：扫描本机 CDP 端口

```
browser_use(action="list_cdp_targets")
```

扫描指定端口：
```
browser_use(action="list_cdp_targets", cdpPort=9222)
```

## 场景二：连接已有 Chrome

用户已手动用调试端口启动了 Chrome：

```
browser_use(action="connect_cdp", url="http://localhost:9222")
```

连接后可正常使用 `open` / `snapshot` / `click` / `type` 等操作。

> **注意**：`stop` 只断开连接，**不会关闭**用户的 Chrome 进程。

## 场景三：启动时指定固定 CDP 端口

```
browser_use(action="start", cdpPort=9222)
```

可见窗口 + 固定端口：
```
browser_use(action="start", headed=true, cdpPort=9222)
```

> **仅在用户明确要求固定端口时才传 `cdpPort`**，否则让系统自动选择空闲端口以避免冲突。

## 手动启动 Chrome（带调试端口）

若用户需要先手动打开 Chrome 再连接：

**macOS：**
```
execute_shell_command(
  command="\"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome\" --remote-debugging-port=9222 --no-first-run --no-default-browser-check"
)
```

**Windows：**
```
execute_shell_command(
  command="\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" --remote-debugging-port=9222 --no-first-run"
)
```

**Linux：**
```
execute_shell_command(
  command="google-chrome --remote-debugging-port=9222 --no-first-run &"
)
```

## stop 行为区别

| 启动方式 | stop 效果 |
|---------|----------|
| `browser_use(action="start")` | 断开连接 + **关闭** Chrome 进程 |
| `browser_use(action="connect_cdp", ...)` | 仅断开连接，**不关闭**外部 Chrome |

## 与 browser_visible 的分工

| 需求 | 用哪个技能 |
|------|-----------|
| 显示 / 隐藏浏览器窗口 | `browser_visible` |
| 连接 / 暴露 / 扫描 CDP 端口 | `browser_cdp`（本技能） |

## 安全提醒

- CDP 端口暴露后，本机任何进程都可以控制该浏览器（包括读取 Cookies）
- 不要在公共 / 多用户服务器上暴露 CDP 端口
- 用完后及时 `stop` 断开连接
