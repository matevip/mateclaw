<div align="center">

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

# MateClaw

<p align="center"><b>Build AI that thinks, acts, remembers, and ships.</b></p>

[![GitHub Repo](https://img.shields.io/badge/GitHub-Repo-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![Documentation](https://img.shields.io/badge/Docs-Website-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![Live Demo](https://img.shields.io/badge/Demo-Online-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![Website](https://img.shields.io/badge/Website-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java Version](https://img.shields.io/badge/Java-17+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![Last Commit](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![License](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)

[[Website](https://claw.mate.vip)] [[Live Demo](https://claw-demo.mate.vip)] [[Documentation](https://claw.mate.vip/docs)] [[中文](README_zh.md)]

</div>

MateClaw is a personal AI operating system built with **Java + Vue 3** and powered by [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba).

It is not just a chat box. It is a system for building AI workers that can reason, use tools, remember context, search the live web, digest knowledge into structured Wiki pages, generate media, and show up across the channels where work actually happens.

The idea is simple:

- Give each agent a clear role
- Give it the right tools and guardrails
- Let it keep memory instead of starting from zero
- Let it work across chat, channels, documents, and media
- Keep the whole system deployable by one team without turning into infrastructure theater

---

## What MateClaw Is

MateClaw sits at the intersection of four product ideas:

1. **An AI console** for direct interaction
2. **An agent runtime** for structured reasoning and tool use
3. **A knowledge system** that turns raw information into reusable memory and Wiki pages
4. **A deployment surface** that spans web, desktop, and external channels

Most products do one of these. MateClaw is designed to make them work as one system.

---

## Product Principles

### 1. Agents should do work, not just talk

MateClaw supports ReAct and Plan-and-Execute agents so the model can break work down, call tools, observe results, and continue instead of stopping at a polished paragraph.

### 2. Knowledge should be digested, not endlessly re-read

Raw files are useful, but structured knowledge is better. MateClaw includes an LLM Wiki knowledge base that converts source materials into linked Wiki pages with summaries, backlinks, and on-demand retrieval.

### 3. Memory should compound

Conversations should not disappear. MateClaw combines short-term context management, post-conversation extraction, workspace memory files, and scheduled consolidation so agents can build continuity over time.

### 4. Tools need control, not chaos

Powerful tools without boundaries are a liability. MateClaw includes tool guard rules, approval flows, file-path protection, and runtime filtering so capability does not become recklessness.

### 5. AI should live where work already happens

A useful assistant cannot be trapped in one web page. MateClaw connects to desktop, browser, and external messaging/work channels so the agent can meet users where decisions are being made.

---

## What You Can Build With It

### Personal AI Workspace

- A persistent assistant with memory, tools, and workspace files
- A desktop app with bundled backend and auto-update
- A web console for direct chat, planning, and configuration

### Team Knowledge Assistant

- Ingest notes, documents, PDFs, and DOCX files
- Turn source materials into structured Wiki pages
- Let agents search, summarize, and read knowledge on demand

### Tool-Using AI Workers

- Agents that search the web, read files, use MCP tools, and execute workflows
- Role-specific skill packages with `SKILL.md`
- Approval and security controls for sensitive actions

### Multimodal Content Workflows

- Text-to-speech
- Speech-to-text
- Music generation
- Image generation
- Video generation

### Multi-Channel AI Presence

- Web console
- DingTalk
- Feishu
- WeChat Work
- Telegram
- Discord
- QQ

---

## Core Capabilities

### Agent Runtime

- **ReAct agents** for thought → action → observation loops
- **Plan-and-Execute agents** for decomposing complex work into ordered steps
- **Dynamic agent configuration** loaded at runtime
- **Multi-agent setup** with separate prompts, personalities, and tool scopes
- **Runtime resilience** including context pruning, smart truncation, stale stream cleanup, and recovery for longer tasks

### Knowledge and Memory

- **LLM Wiki knowledge base** for structured, linked, AI-digested knowledge
- **Workspace memory files** such as `AGENTS.md`, `SOUL.md`, `PROFILE.md`, `MEMORY.md`, and daily notes
- **Post-conversation extraction** to preserve useful information automatically
- **Scheduled consolidation** so memory quality improves instead of just growing
- **Dreaming and emergence workflows** for longer-horizon memory refinement

### Tools, Skills, and Search

- **Built-in tools** for web search, file operations, memory access, date/time, and more
- **Advanced web search** with provider chaining, fallback strategies, and live information support
- **MCP integration** across stdio, SSE, and Streamable HTTP transports
- **Skill system** with installable `SKILL.md` packages
- **ClawHub marketplace** for discovering and installing skills
- **Tool guard and approval** for sensitive operations

### Multimodal Creation

- **Text-to-speech** for read-aloud and voice output
- **Speech-to-text** for audio transcription
- **Music generation**
- **Image generation** with multiple providers
- **Video generation** with async task handling

### Model Flexibility

Configure models in the web UI. MateClaw supports cloud and local model providers including:

- DashScope
- OpenAI
- Anthropic
- Google Gemini
- DeepSeek
- Kimi
- MiniMax
- Zhipu AI
- Volcano Engine
- OpenRouter
- Ollama
- LM Studio
- llama.cpp
- MLX

### Surfaces

- **Web app** for chat, agent management, MCP, models, tools, channels, and security
- **Desktop app** with bundled JRE 21 and backend
- **External channels** for production-facing assistant workflows

---

## Why The Wiki Matters

Most AI systems treat knowledge like a warehouse of raw fragments.

MateClaw adds another layer: a structured Wiki that AI can build and maintain. Instead of retrieving arbitrary chunks from source files every time, the system can pre-digest information into clean pages with summaries and links.

That changes the product in three ways:

- Agents waste less context on raw material
- Knowledge becomes easier to inspect and edit by humans
- Understanding improves over time instead of resetting on every query

This is the difference between storing information and shaping it.

---

## Quick Start

### Prerequisites

- Java 17+
- Node.js 18+ and pnpm
- Maven 3.9+ (or use `mvnw`)
- At least one LLM API key such as [DashScope](https://dashscope.aliyun.com/)

### Option 1: Local Development

**Backend**

```bash
cd mateclaw-server
export DASHSCOPE_API_KEY=your-key-here
mvn spring-boot:run
```

Backend:

- App: `http://localhost:18088`
- H2 Console: `http://localhost:18088/h2-console`
- Swagger UI: `http://localhost:18088/swagger-ui.html`

**Frontend**

```bash
cd mateclaw-ui
pnpm install
pnpm dev
```

Frontend:

- App: `http://localhost:5173`

**Login**

- Username: `admin`
- Password: `admin123`

### Option 2: Docker

```bash
cp .env.example .env
docker compose up -d
```

Default service:

- `http://localhost:18080`

### Option 3: Desktop App

Download installers from [GitHub Releases](https://github.com/matevip/mateclaw/releases).

The desktop app bundles **JRE 21 + the Spring Boot backend**, so users do not need to install Java separately.

> macOS: if the app is blocked on first launch, use right-click → Open, or allow it in Privacy & Security.

---

## Architecture

```text
mateclaw/
├── mateclaw-server/     Spring Boot backend
├── mateclaw-ui/         Vue 3 SPA frontend
├── mateclaw-desktop/    Electron desktop app
├── docs/                VitePress documentation
├── docker-compose.yml
└── .env.example
```

Backend domains include:

- `agent/` for runtime and orchestration
- `tool/` for built-in tools and MCP integration
- `skill/` for skill installation and execution
- `memory/` for extraction, consolidation, and dreaming
- `wiki/` for knowledge base and structured Wiki processing
- `channel/` for external platform adapters
- `workspace/` for files, messages, and conversations

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Spring Boot 3.5 + Spring AI Alibaba 1.1 |
| Agent Runtime | StateGraph |
| Database | H2 (dev) / MySQL 8.0+ (prod) |
| ORM | MyBatis Plus 3.5 |
| Auth | Spring Security + JWT |
| Frontend | Vue 3 + TypeScript + Vite |
| State | Pinia |
| UI | Element Plus |
| Styling | TailwindCSS 4 |
| Desktop | Electron + electron-updater |
| Docs | VitePress |

---

## Documentation

| Topic | Description |
|-------|-------------|
| [Introduction](https://mateclaw.mate.vip/en/intro) | Product overview and core concepts |
| [Quick Start](https://mateclaw.mate.vip/en/quickstart) | Local, Docker, and desktop setup |
| [Console](https://mateclaw.mate.vip/en/console) | Web console and day-to-day usage |
| [Agents](https://mateclaw.mate.vip/en/agents) | ReAct, Plan-and-Execute, and runtime design |
| [Models](https://mateclaw.mate.vip/en/models) | Model provider setup |
| [Tools](https://mateclaw.mate.vip/en/tools) | Built-in tools and extension model |
| [Skills](https://mateclaw.mate.vip/en/skills) | Skill packages and marketplace |
| [MCP](https://mateclaw.mate.vip/en/mcp) | Model Context Protocol integration |
| [Memory](https://mateclaw.mate.vip/en/memory) | Memory architecture |
| [Channels](https://mateclaw.mate.vip/en/channels) | External channel integration |
| [Security](https://mateclaw.mate.vip/en/security) | Guardrails and approval |
| [Desktop](https://mateclaw.mate.vip/en/desktop) | Desktop application guide |
| [API Reference](https://mateclaw.mate.vip/en/api) | REST API |
| [FAQ](https://mateclaw.mate.vip/en/faq) | Troubleshooting |

---

## Roadmap

Current focus areas include:

- richer multi-agent collaboration
- deeper multimodal understanding
- smarter model routing
- stronger long-term memory
- richer ClawHub ecosystem
- more channels and desktop coverage

---

## Contributing

MateClaw is open to product, code, docs, and integration contributions.

```bash
git clone https://github.com/matevip/mateclaw.git
cd mateclaw

cd mateclaw-server
mvn clean compile

cd ../mateclaw-ui
pnpm install
pnpm dev
```

---

## Why The Name

**Mate** means companion.  
**Claw** means capability.

The product is meant to feel like both: a system that stays with you, and a system that can actually grab work and move it.

---

## License

MateClaw is released under the [Apache License 2.0](LICENSE).
