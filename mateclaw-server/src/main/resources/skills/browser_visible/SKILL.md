---
name: browser_visible
version: "1.3.0"
description: "以可见模式启动真实浏览器窗口，适用于演示、调试或需要人工参与的场景。控制 headed/cdpPort 等启动参数。"
dependencies:
  tools:
    - browser_use
---

# 浏览器启动模式

本技能控制 `browser_use` 的启动方式，核心参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| `headed` | Boolean | `true` = 显示窗口；`false`（默认）= 无头模式 |
| `cdpPort` | Integer | 指定 CDP 调试端口（不填则自动选择） |

## 常见用法

### 无头模式（默认，后台自动化）
```
browser_use(action="start")
```

### 可见窗口（演示 / 需要人工操作）
```
browser_use(action="start", headed=true)
```

### 指定 CDP 端口（与外部工具共享）
```
browser_use(action="start", headed=true, cdpPort=9222)
```

## 标准操作序列

```
# 1. 启动
browser_use(action="start", headed=true)

# 2. 打开页面
browser_use(action="open", url="https://example.com")

# 3. 截取快照（查看页面内容）
browser_use(action="snapshot")

# 4. 点击元素
browser_use(action="click", selector="#submit-btn")

# 5. 输入文本
browser_use(action="type", selector="input[name=email]", text="user@example.com")

# 6. 完成后关闭
browser_use(action="stop")
```

## 何时用可见模式

| 场景 | 推荐参数 |
|------|---------|
| 向用户演示操作过程 | `headed=true` |
| 需要用户手动登录（扫码、验证码） | `headed=true`，遇到登录页暂停并提示用户 |
| 调试自动化脚本 | `headed=true` |
| 纯自动化、不需要展示 | `headed=false`（默认） |

## 跨平台可执行文件路径（需自定义浏览器时）

MateClaw 的 `browser_use` 会自动检测系统浏览器，无需手动指定路径。如需调试特定 Chrome 安装位置：

**Windows 默认路径：**
```
C:\Program Files\Google\Chrome\Application\chrome.exe
C:\Program Files (x86)\Google\Chrome\Application\chrome.exe
```

**macOS 默认路径：**
```
/Applications/Google Chrome.app/Contents/MacOS/Google Chrome
```

**Linux 默认路径：**
```
/usr/bin/google-chrome
/usr/bin/chromium-browser
```

## 注意事项

- 可见模式需要图形环境（GUI）；无桌面的服务器无法使用
- 若浏览器已在运行，须先 `stop` 再重新 `start` 才能切换 `headed` 状态
- `cdpPort` 被占用时会报错，换端口或先停止占用该端口的进程
- 用户手动操作可见浏览器不会刷新 Agent 的 idle 超时计时
