<div align="center">

# MateClaw

[![GitHub Repo](https://img.shields.io/badge/GitHub-Repo-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![Documentation](https://img.shields.io/badge/Docs-Website-green.svg?logo=readthedocs&label=Docs)](https://mateclaw.mate.vip/)
[![Java Version](https://img.shields.io/badge/Java-17+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![Last Commit](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![License](https://img.shields.io/badge/license-MIT-red.svg?logo=opensourceinitiative&label=License)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/matevip/mateclaw?style=flat&logo=github&color=yellow&label=Stars)](https://github.com/matevip/mateclaw/stargazers)
[![GitHub Forks](https://img.shields.io/github/forks/matevip/mateclaw?style=flat&logo=github&color=purple&label=Forks)](https://github.com/matevip/mateclaw/network)

[[Documentation](https://mateclaw.mate.vip/)] [[中文](README_zh.md)]

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

<p align="center"><b>Your AI mate, always ready to lend a claw.</b></p>

</div>

A personal AI assistant system built with **Java + Vue 3**, powered by [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba). Features multi-agent orchestration, a flexible tool/skill system with MCP protocol support, multi-layer memory, and multi-channel adapters.

> **Core capabilities:**
>
> **Multi-Agent Orchestration** — ReAct (Thought → Action → Observation loop) and Plan-and-Execute (auto-decompose complex tasks into ordered sub-steps). Create multiple independent agents, each with their own personality and tools.
>
> **Tool & Skill System** — Built-in tools (web search, date/time) + MCP protocol for external tool integration. Install skill packages from ClawHub marketplace or custom sources.
>
> **Multi-Layer Memory** — Short-term context window with auto-compression, event-driven post-conversation memory extraction, workspace files (PROFILE.md / MEMORY.md / daily notes), and scheduled memory consolidation.
>
> **Every Channel** — Web console, DingTalk, Feishu, WeChat Work, Telegram, Discord, QQ. One MateClaw, connect as needed.
>
> **Multi-Provider Models** — DashScope (Qwen), OpenAI, Ollama, DeepSeek, OpenRouter, Zhipu AI, Volcano Engine, and more. Configure in the web UI.
>
> **Desktop App** — Electron-based desktop application with auto-update support. Download and double-click to run.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Screenshots](#screenshots)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Features](#features)
- [Documentation](#documentation)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [Contact Us](#contact-us)
- [License](#license)

---

## Quick Start

### Prerequisites

- Java 17+
- Node.js 18+ & pnpm
- Maven 3.9+ (or use `mvnw`)
- At least one LLM API Key (e.g., [DashScope](https://dashscope.aliyun.com/))

### Option 1: Local Development

**1. Start the backend**

```bash
cd mateclaw-server
export DASHSCOPE_API_KEY=your-key-here
mvn spring-boot:run
# Backend runs at http://localhost:18088
# H2 Console: http://localhost:18088/h2-console
# API Docs (Knife4j): http://localhost:18088/doc.html
```

**2. Start the frontend**

```bash
cd mateclaw-ui
pnpm install
pnpm dev
# Frontend runs at http://localhost:5173 (proxies /api to :18088)
```

**3. Log in**

Open http://localhost:5173 and log in with `admin` / `admin123`.

### Option 2: Docker

```bash
cp .env.example .env
# Edit .env — fill in DASHSCOPE_API_KEY and other variables

docker compose up -d
# Service runs at http://localhost:18080 (MySQL + backend)
```

### Option 3: Desktop Application

Download the installer from [GitHub Releases](https://github.com/matevip/mateclaw/releases):

- **macOS**: `MateClaw-<version>-macOS.zip`
- **Windows**: `MateClaw-Setup-<version>.exe`

Double-click to run. The app bundles the Java backend and auto-updates from GitHub Releases.

> **macOS users**: If macOS blocks the app, right-click → Open → Open again, or go to System Settings → Privacy & Security → Open Anyway.

---

## Screenshots

<!-- TODO: Add screenshots of the chat console, agent workspace, skill market, etc. -->

---

## Architecture

```
mateclaw/
├── mateclaw-server/          # Spring Boot backend
│   ├── src/main/java/vip/mate/
│   │   ├── agent/            # Agent engine (ReAct, Plan-and-Execute, StateGraph)
│   │   ├── planning/         # Task planning (Plan / SubPlan models)
│   │   ├── tool/             # Tool system (built-in + MCP adapters)
│   │   ├── skill/            # Skill management (workspace + ClawHub)
│   │   ├── channel/          # Channel adapters (Web, DingTalk, Feishu, etc.)
│   │   ├── workspace/        # Conversations, messages, workspace files
│   │   ├── memory/           # Memory extraction & consolidation
│   │   ├── llm/              # Multi-provider model configs
│   │   ├── cron/             # Scheduled tasks (CronJob)
│   │   ├── auth/             # Spring Security + JWT
│   │   └── config/           # Spring bean configurations
│   └── src/main/resources/
│       ├── application.yml   # Main config (H2 for dev)
│       ├── prompts/          # Prompt templates
│       └── db/               # Schema & seed data (schema.sql, data.sql)
├── mateclaw-ui/              # Vue 3 SPA frontend
│   └── src/
│       ├── views/            # Pages (ChatConsole, AgentWorkspace, SkillMarket, etc.)
│       ├── components/       # Reusable components
│       ├── stores/           # Pinia stores (domain-driven)
│       ├── api/              # Axios HTTP client
│       ├── router/           # Vue Router
│       ├── types/            # TypeScript types
│       └── i18n/             # Internationalization (zh-CN, en-US)
├── mateclaw-desktop/         # Electron desktop app
├── docs/                     # VitePress documentation (zh + en)
├── docker-compose.yml
└── .env.example
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend Framework | Spring Boot 3.5 + Spring AI Alibaba 1.1 |
| LLM Integration | DashScope, OpenAI, Ollama, DeepSeek, OpenRouter, Zhipu, Volcano Engine |
| Agent Engine | StateGraph (ReAct + Plan-and-Execute) |
| Database | H2 (dev) / MySQL 8.0+ (prod) |
| ORM | MyBatis Plus 3.5 |
| Authentication | Spring Security + JWT |
| API Docs | Knife4j (OpenAPI 3) |
| Frontend | Vue 3 + TypeScript + Vite |
| State Management | Pinia |
| UI Components | Element Plus |
| Styling | TailwindCSS 4 |
| Desktop | Electron + electron-updater |
| Docs Site | VitePress |

---

## Features

### Agent System

- **ReAct Agent** — Thought → Action → Observation reasoning loop with tool calling
- **Plan-and-Execute** — Auto-decompose complex tasks into ordered sub-steps with progress tracking
- **Dynamic Agent** — Load agent configs from database at runtime
- **Multi-Agent** — Create multiple independent agents, each with their own system prompt, tools, and personality

### Tool & Skill System

- **Built-in Tools** — Web search (Serper/Tavily), date/time, workspace memory read/write
- **MCP Protocol** — Connect external tools via Model Context Protocol (stdio and SSE transports)
- **Skill Packages** — Install/uninstall skill packages with `SKILL.md` manifests
- **ClawHub Marketplace** — Browse and install skills from the ClawHub registry
- **Workspace Skills** — Convention-based skill directory at `~/.mateclaw/skills/{name}/`

### Memory System

- **Short-Term** — Conversation context window with auto-compression when token budget exceeded
- **Post-Conversation Extraction** — Event-driven async LLM analysis, writes to PROFILE.md / MEMORY.md / daily notes
- **Memory Consolidation** — Scheduled daily emergence (CronJob at 2:00 AM) merges daily notes into long-term memory
- **Workspace Files** — Per-agent AGENTS.md, SOUL.md, PROFILE.md, MEMORY.md, memory/*.md
- **Agent Memory Tool** — Agents can read/write their own workspace files during conversations

### Multi-Channel

- **Web Console** — SSE streaming with rich message rendering (Markdown, code, plans)
- **DingTalk** — Webhook + event subscription
- **Feishu (Lark)** — Webhook + event subscription
- **WeChat Work** — Callback API
- **Telegram** — Bot API with webhook
- **Discord** — Bot with slash commands
- **QQ** — QQ Bot API

### Model Providers

Configure in the web UI (Settings → Models). Supported providers:

| Provider | Models |
|----------|--------|
| DashScope (Alibaba) | Qwen-Max, Qwen-Plus, Qwen-Turbo, Qwen-Long, QVQ |
| OpenAI | GPT-4o, GPT-4o-mini, o1, o3 |
| DeepSeek | DeepSeek-Chat, DeepSeek-Reasoner |
| Ollama | Any locally-served model |
| OpenRouter | Access 200+ models via unified API |
| Zhipu AI | GLM-4-Plus, GLM-4-Flash |
| Volcano Engine | Doubao-Pro, Doubao-Lite |
| SiliconFlow | DeepSeek, Qwen via SiliconFlow |

### Security

- **Spring Security + JWT** — Token-based authentication
- **Tool Guard** — Approval rules for sensitive tool operations
- **File Validation** — Path traversal prevention for workspace files
- **Skill Security** — Validation during skill installation

### Scheduled Tasks

- **CronJob System** — Create scheduled tasks with 5-field cron expressions
- **Memory Consolidation** — Auto-triggered daily for each agent
- **Custom Tasks** — Schedule any prompt to run periodically

---

## Documentation

| Topic | Description |
|-------|-------------|
| [Introduction](https://mateclaw.mate.vip/en/intro) | What MateClaw is and core concepts |
| [Quick Start](https://mateclaw.mate.vip/en/quickstart) | Install and run (local, Docker, desktop) |
| [Console](https://mateclaw.mate.vip/en/console) | Web UI: chat and agent configuration |
| [Agents](https://mateclaw.mate.vip/en/agents) | Agent engine: ReAct, Plan-and-Execute, StateGraph |
| [Models](https://mateclaw.mate.vip/en/models) | Configure cloud, local, and custom providers |
| [Tools](https://mateclaw.mate.vip/en/tools) | Built-in tools and custom tool development |
| [Skills](https://mateclaw.mate.vip/en/skills) | Skill packages and ClawHub marketplace |
| [MCP](https://mateclaw.mate.vip/en/mcp) | Model Context Protocol integration |
| [Memory](https://mateclaw.mate.vip/en/memory) | Multi-layer memory system |
| [Channels](https://mateclaw.mate.vip/en/channels) | DingTalk, Feishu, Telegram, Discord, and more |
| [Security](https://mateclaw.mate.vip/en/security) | Authentication and tool guard |
| [Desktop](https://mateclaw.mate.vip/en/desktop) | Desktop application guide |
| [API Reference](https://mateclaw.mate.vip/en/api) | REST API documentation |
| [Configuration](https://mateclaw.mate.vip/en/config) | Configuration reference |
| [FAQ](https://mateclaw.mate.vip/en/faq) | Common questions and troubleshooting |

---

## Roadmap

| Area | Item | Status |
|------|------|--------|
| **Agent** | Multi-agent collaboration and delegation | Planned |
| **Agent** | Multimodal input (image, audio, video) | Planned |
| **Models** | Small + large model routing | Planned |
| **Memory** | Vector DB long-term memory (RAG) | Planned |
| **Memory** | Multimodal memory fusion | Planned |
| **Skills** | Richer ClawHub ecosystem | In Progress |
| **Channels** | WeChat personal (iLink Bot) | Planned |
| **Channels** | Email channel | Planned |
| **Desktop** | Linux support | Planned |
| **Security** | Multi-tenant support | Planned |
| **Console** | Plugin marketplace in web UI | Planned |

_Status:_ **In Progress** — actively being worked on; **Planned** — queued or under design.

---

## Contributing

MateClaw is open to contributions! Whether it's bug fixes, new features, documentation improvements, or new channel/tool integrations — all contributions are welcome.

```bash
# Clone the repository
git clone https://github.com/matevip/mateclaw.git
cd mateclaw

# Backend
cd mateclaw-server
mvn clean compile

# Frontend
cd ../mateclaw-ui
pnpm install
pnpm dev
```

Please read [CONTRIBUTING.md](https://github.com/matevip/mateclaw/blob/main/CONTRIBUTING.md) (if available) before submitting a PR.

---

## Contact Us

<!-- TODO: Fill in social accounts -->

| Discord | X (Twitter) | DingTalk |
|---------|-------------|----------|
| Coming soon | Coming soon | Coming soon |

---

## Why MateClaw?

**Mate** — a companion, always by your side. **Claw** — sharp, capable, ready to grab any task. MateClaw is your personal AI mate that lends a claw whenever you need it. Built as a monolith with modular design, it's easy to deploy, extend, and customize.

---

## License

MateClaw is released under the [MIT License](LICENSE).
