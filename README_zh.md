<div align="center">

# MateClaw

[![GitHub 仓库](https://img.shields.io/badge/GitHub-仓库-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![文档](https://img.shields.io/badge/文档-在线-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![在线演示](https://img.shields.io/badge/演示-在线-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![官网](https://img.shields.io/badge/官网-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java 版本](https://img.shields.io/badge/Java-17+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![最后提交](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)
[![GitHub Star](https://img.shields.io/github/stars/matevip/mateclaw?style=flat&logo=github&color=yellow&label=Stars)](https://github.com/matevip/mateclaw/stargazers)
[![GitHub Fork](https://img.shields.io/github/forks/matevip/mateclaw?style=flat&logo=github&color=purple&label=Forks)](https://github.com/matevip/mateclaw/network)

[[官网](https://claw.mate.vip)] [[在线演示](https://claw-demo.mate.vip)] [[文档](https://claw.mate.vip/docs)] [[English](README.md)]

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

<p align="center"><b>懂你所需，利爪随行。</b></p>

</div>

基于 **Java + Vue 3** 的个人 AI 助手系统，由 [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) 驱动。支持多 Agent 编排、灵活的工具/技能系统与 MCP 协议、多层记忆体系、多渠道接入。

> **核心能力：**
>
> **多 Agent 编排** — ReAct（思考→行动→观察循环）和 Plan-and-Execute（自动将复杂任务拆解为有序子步骤）。创建多个独立 Agent，各有专属人格和工具。
>
> **工具与技能系统** — 内置工具（网络搜索、日期时间）+ MCP 协议接入外部工具。预置 GitHub 和 Filesystem MCP 服务，启用即用。从 ClawHub 市场或自定义源安装技能包。
>
> **多层记忆** — 短期上下文窗口自动压缩、事件驱动的对话后记忆提取、工作空间文件（PROFILE.md / MEMORY.md / 每日笔记）、定时记忆整合。
>
> **全域触达** — Web 控制台、钉钉、飞书、企业微信、Telegram、Discord、QQ。一个 MateClaw，按需连接。
>
> **多厂商模型** — 20+ 厂商：DashScope、OpenAI、Anthropic、Google Gemini、DeepSeek、Kimi、MiniMax、智谱、火山引擎、OpenRouter、Ollama、LM Studio、llama.cpp、MLX 等。在 Web 界面中配置。
>
> **桌面应用** — 基于 Electron 的桌面应用，支持自动更新。下载即用。

---

## 目录

- [快速开始](#快速开始)
- [截图](#截图)
- [架构](#架构)
- [技术栈](#技术栈)
- [功能特性](#功能特性)
- [文档](#文档)
- [路线图](#路线图)
- [参与贡献](#参与贡献)
- [联系我们](#联系我们)
- [许可证](#许可证)

---

## 快速开始

### 前置条件

- Java 17+
- Node.js 18+ & pnpm
- Maven 3.9+（或使用 `mvnw`）
- 至少一个 LLM API Key（如 [DashScope](https://dashscope.aliyun.com/)）

### 方式一：本地开发

**1. 启动后端**

```bash
cd mateclaw-server
export DASHSCOPE_API_KEY=your-key-here
mvn spring-boot:run
# 后端运行在 http://localhost:18088
# H2 控制台：http://localhost:18088/h2-console
# API 文档（SpringDoc OpenAPI）：http://localhost:18088/swagger-ui.html
```

**2. 启动前端**

```bash
cd mateclaw-ui
pnpm install
pnpm dev
# 前端运行在 http://localhost:5173（代理 /api 到 :18088）
```

**3. 登录**

打开 http://localhost:5173，使用 `admin` / `admin123` 登录。

### 方式二：Docker 部署

```bash
cp .env.example .env
# 编辑 .env，填写 DASHSCOPE_API_KEY 等变量

docker compose up -d
# 服务运行在 http://localhost:18080（MySQL + 后端）
```

### 方式三：桌面应用

从 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 下载安装包：

| 平台 | 文件 | 说明 |
|------|------|------|
| **macOS (Apple Silicon)** | `MateClaw_<version>_arm64.dmg` | 推荐 M1/M2/M3/M4/M5 Mac 使用 |
| **macOS (Apple Silicon)** | `MateClaw_<version>_arm64.zip` | zip 格式（Apple Silicon） |
| **macOS (Intel)** | `MateClaw_<version>_x64.dmg` | 适用于 Intel Mac |
| **macOS (Intel)** | `MateClaw_<version>_x64.zip` | zip 格式（Intel） |
| **Windows (x64)** | `MateClaw_<version>_Setup.exe` | 适用于大多数 Windows 电脑（64 位） |
| **Windows (x64)** | `MateClaw_<version>_x64_Setup.exe` | 显式 x64 构建 |
| **Windows (ARM64)** | `MateClaw_<version>_arm64_Setup.exe` | 适用于 ARM 架构 Windows（如 Surface Pro X） |

双击运行。应用内置 JRE 21 + Spring Boot 后端，无需安装 Java。支持从 GitHub Releases 自动更新。

> **macOS 用户**：如果系统阻止打开，右键 → 打开 → 再次点击打开，或前往系统设置 → 隐私与安全性 → 仍要打开。

---

## 截图

<!-- TODO: 添加控制台、Agent 工作台、技能市场等截图 -->

---

## 架构

```
mateclaw/
├── mateclaw-server/          # Spring Boot 后端
│   ├── src/main/java/vip/mate/
│   │   ├── agent/            # Agent 引擎（ReAct、Plan-and-Execute、StateGraph）
│   │   ├── planning/         # 任务规划（Plan / SubPlan 模型）
│   │   ├── tool/             # 工具系统（内置 + MCP 适配器）
│   │   ├── skill/            # 技能管理（工作空间 + ClawHub）
│   │   ├── channel/          # 渠道适配器（Web、钉钉、飞书等）
│   │   ├── workspace/        # 会话、消息、工作空间文件
│   │   ├── memory/           # 记忆提取与整合
│   │   ├── llm/              # 多厂商模型配置
│   │   ├── cron/             # 定时任务（CronJob）
│   │   ├── auth/             # Spring Security + JWT
│   │   └── config/           # Spring Bean 配置
│   └── src/main/resources/
│       ├── application.yml   # 主配置（开发环境用 H2）
│       ├── prompts/          # 提示词模板
│       └── db/               # 数据库脚本（schema.sql、data.sql）
├── mateclaw-ui/              # Vue 3 SPA 前端
│   └── src/
│       ├── views/            # 页面（ChatConsole、AgentWorkspace、SkillMarket 等）
│       ├── components/       # 复用组件
│       ├── stores/           # Pinia 状态管理（领域驱动）
│       ├── api/              # Axios HTTP 客户端
│       ├── router/           # Vue Router
│       ├── types/            # TypeScript 类型
│       └── i18n/             # 国际化（zh-CN、en-US）
├── mateclaw-desktop/         # Electron 桌面应用
├── docs/                     # VitePress 文档站（中 + 英）
├── docker-compose.yml
└── .env.example
```

---

## 技术栈

| 层次 | 技术选型 |
|------|---------|
| 后端框架 | Spring Boot 3.5 + Spring AI Alibaba 1.1 |
| 大模型接入 | DashScope、OpenAI、Anthropic、Gemini、DeepSeek、Kimi、MiniMax、智谱、火山引擎、OpenRouter、Ollama、LM Studio、llama.cpp、MLX |
| Agent 引擎 | StateGraph（ReAct + Plan-and-Execute） |
| 数据库 | H2（开发）/ MySQL 8.0+（生产） |
| ORM | MyBatis Plus 3.5 |
| 认证 | Spring Security + JWT |
| API 文档 | SpringDoc OpenAPI 3 |
| 前端框架 | Vue 3 + TypeScript + Vite |
| 状态管理 | Pinia |
| UI 组件 | Element Plus |
| 样式 | TailwindCSS 4 |
| 桌面端 | Electron + electron-updater |
| 文档站 | VitePress |

---

## 功能特性

### Agent 系统

- **ReAct Agent** — 思考→行动→观察推理循环，支持工具调用
- **Plan-and-Execute** — 自动将复杂任务拆解为有序子步骤，带进度追踪
- **动态 Agent** — 运行时从数据库加载 Agent 配置
- **多 Agent** — 创建多个独立 Agent，各有专属系统提示词、工具和人格

### 工具与技能系统

- **内置工具** — 网络搜索（Serper/Tavily）、日期时间、工作空间记忆读写
- **MCP 协议** — 通过 Model Context Protocol 接入外部工具（stdio、SSE、Streamable HTTP 传输）。在 Web 界面中完成全生命周期管理 — 添加、编辑、启用/禁用、测试连接
- **预置 MCP 服务** — GitHub（`@modelcontextprotocol/server-github`）和 Filesystem 开箱即用。在 MCP 管理页面启用并填入 Token 即可，无需改代码
- **技能包** — 安装/卸载带 `SKILL.md` 清单的技能包
- **ClawHub 市场** — 从 ClawHub 注册中心浏览和安装技能
- **工作空间技能** — 基于约定的技能目录 `~/.mateclaw/skills/{name}/`

### 记忆系统

- **短期记忆** — 会话上下文窗口，Token 超出预算时自动压缩
- **对话后提取** — 事件驱动的异步 LLM 分析，写入 PROFILE.md / MEMORY.md / 每日笔记
- **记忆整合** — 定时每日涌现（CronJob 凌晨 2:00），将每日笔记合并为长期记忆
- **工作空间文件** — 每个 Agent 独立的 AGENTS.md、SOUL.md、PROFILE.md、MEMORY.md、memory/*.md
- **Agent 记忆工具** — Agent 在对话中可主动读写自己的工作空间文件

### 多渠道接入

- **Web 控制台** — SSE 流式输出，富消息渲染（Markdown、代码、计划）
- **钉钉** — Webhook + 事件订阅
- **飞书** — Webhook + 事件订阅
- **企业微信** — 回调接口
- **Telegram** — Bot API + Webhook
- **Discord** — Bot + Slash Commands
- **QQ** — QQ Bot API

### 模型厂商

在 Web 界面中配置（设置 → 模型）。支持的厂商：

| 厂商 | 模型 |
|------|------|
| **云端厂商** | |
| DashScope（阿里云） | Qwen3.5-Max、Qwen3.5-Plus、Qwen3-Max、Qwen3-Plus、Qwen-Max、Qwen-Plus、Qwen-Turbo、Qwen-Long、DeepSeek-V3.2 |
| ModelScope | Qwen3.5-122B-A10B、GLM-5 |
| 阿里云百炼 Coding Plan | Qwen3.5-Plus、Qwen3-Coder-Next、GLM-5、GLM-4.7、MiniMax-M2.5、Kimi-K2.5 |
| OpenAI | GPT-5.2、GPT-5、GPT-5-Mini、GPT-5-Nano、GPT-4.1、GPT-4.1-Mini、GPT-4.1-Nano、o3、o4-mini、GPT-4o |
| Azure OpenAI | GPT-5、GPT-4.1、GPT-4o 等 |
| Anthropic | Claude Opus 4.6、Claude Sonnet 4.6（通过模型发现） |
| Google Gemini | Gemini 3.1 Pro、Gemini 3 Flash、Gemini 2.5 Pro、Gemini 2.5 Flash、Gemini 2.0 Flash |
| DeepSeek | DeepSeek-Chat、DeepSeek-Reasoner |
| Kimi（月之暗面） | Kimi-K2.5、Kimi-K2-Thinking、Kimi for Coding（国内版 / 国际版 / Code 版） |
| MiniMax | MiniMax-M2.7、MiniMax-M2.5（国际版 / 国内版） |
| 智谱 AI | GLM-5.1、GLM-5、GLM-5-Turbo、GLM-5V-Turbo（国内版 / 国际版） |
| 火山引擎 | 豆包-1.5-Pro-256K、豆包-1.5-Lite、豆包-1.5-Thinking-Pro、豆包-1.5-Vision-Pro |
| OpenRouter | GPT-5、Claude Opus 4.6、Gemini 2.5 Pro、Llama 4 Maverick、DeepSeek R1 及 200+ 模型 |
| **本地厂商** | |
| Ollama | Qwen3、Gemma 4、Gemma 3、Llama 3.1、DeepSeek R1、Mistral（启动时自动检测） |
| LM Studio | 任意本地服务的模型 |
| llama.cpp | 任意本地服务的模型 |
| MLX（Apple Silicon） | 任意本地服务的模型 |

### 安全

- **Spring Security + JWT** — 基于 Token 的认证
- **工具防护** — 敏感工具操作的审批规则
- **文件校验** — 工作空间文件路径穿越防护
- **技能安全** — 技能安装时的安全校验

### 定时任务

- **CronJob 系统** — 使用 5 位 cron 表达式创建定时任务
- **记忆整合** — 每个 Agent 每日自动触发
- **自定义任务** — 调度任意提示词定期执行

---

## 文档

| 主题 | 说明 |
|------|------|
| [项目介绍](https://mateclaw.mate.vip/zh/intro) | MateClaw 是什么、核心概念 |
| [快速开始](https://mateclaw.mate.vip/zh/quickstart) | 安装与运行（本地、Docker、桌面） |
| [控制台](https://mateclaw.mate.vip/zh/console) | Web 界面：聊天与 Agent 配置 |
| [Agent 引擎](https://mateclaw.mate.vip/zh/agents) | ReAct、Plan-and-Execute、StateGraph |
| [模型配置](https://mateclaw.mate.vip/zh/models) | 配置云端、本地和自定义厂商 |
| [工具系统](https://mateclaw.mate.vip/zh/tools) | 内置工具与自定义工具开发 |
| [技能系统](https://mateclaw.mate.vip/zh/skills) | 技能包与 ClawHub 市场 |
| [MCP](https://mateclaw.mate.vip/zh/mcp) | Model Context Protocol 集成 |
| [记忆系统](https://mateclaw.mate.vip/zh/memory) | 多层记忆体系 |
| [渠道接入](https://mateclaw.mate.vip/zh/channels) | 钉钉、飞书、Telegram、Discord 等 |
| [安全机制](https://mateclaw.mate.vip/zh/security) | 认证与工具防护 |
| [桌面应用](https://mateclaw.mate.vip/zh/desktop) | 桌面应用使用指南 |
| [API 参考](https://mateclaw.mate.vip/zh/api) | REST API 文档 |
| [配置指南](https://mateclaw.mate.vip/zh/config) | 配置参考 |
| [常见问题](https://mateclaw.mate.vip/zh/faq) | 常见问题与故障排查 |

---

## 路线图

| 方向 | 事项 | 状态 |
|------|------|------|
| **Agent** | 多 Agent 协作与任务委派 | 计划中 |
| **Agent** | 多模态输入（图片、音频、视频） | 计划中 |
| **模型** | 大小模型智能路由 | 计划中 |
| **记忆** | 向量数据库长期记忆（RAG） | 计划中 |
| **记忆** | 多模态记忆融合 | 计划中 |
| **技能** | 丰富 ClawHub 生态 | 进行中 |
| **渠道** | 微信个人号（iLink Bot） | 计划中 |
| **渠道** | 邮件渠道 | 计划中 |
| **桌面** | Linux 支持 | 计划中 |
| **安全** | 多租户支持 | 计划中 |
| **控制台** | Web 端插件市场 | 计划中 |

_状态说明：_ **进行中** — 正在开发；**计划中** — 排期中或设计阶段。

---

## 参与贡献

MateClaw 欢迎各种形式的贡献！无论是 Bug 修复、新功能、文档改进，还是新的渠道/工具集成，我们都非常欢迎。

```bash
# 克隆仓库
git clone https://github.com/matevip/mateclaw.git
cd mateclaw

# 后端
cd mateclaw-server
mvn clean compile

# 前端
cd ../mateclaw-ui
pnpm install
pnpm dev
```

提交 PR 前请阅读 [CONTRIBUTING.md](https://github.com/matevip/mateclaw/blob/main/CONTRIBUTING.md)（如有）。

---

## 联系我们

<!-- TODO: 补充社交账号 -->

| Discord | X (Twitter) | 钉钉群 |
|---------|-------------|--------|
| 即将上线 | 即将上线 | 即将上线 |

---

## 为什么叫 MateClaw？

**Mate** — 伙伴，始终陪伴在你身边。**Claw** — 利爪，锋利有力，随时抓取任何任务。MateClaw 是你的个人 AI 伙伴，在你需要时伸出利爪。采用单体模块化设计，部署简单、扩展灵活、定制方便。

---

## 许可证

MateClaw 基于 [Apache License 2.0](LICENSE) 发布。
