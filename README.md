<div align="center">

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

# MateClaw

<p align="center"><b>Your second brain</b></p>

[![GitHub Repo](https://img.shields.io/badge/GitHub-Repo-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![Documentation](https://img.shields.io/badge/Docs-Website-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![Live Demo](https://img.shields.io/badge/Demo-Online-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![Website](https://img.shields.io/badge/Website-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java Version](https://img.shields.io/badge/Java-21+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![Last Commit](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![License](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)

[[Website](https://claw.mate.vip)] [[Live Demo](https://claw-demo.mate.vip)] [[Documentation](https://claw.mate.vip/docs)] [[中文](README_zh.md)]

</div>

<p align="center">
  <img src="assets/images/preview.png" alt="MateClaw Preview" width="800">
</p>

---

> **Other personal AI agents are built for one person. MateClaw is the one your IT department can actually sign off on.**
>
> Multi-user workspaces. Approval-gated sensitive actions. Full audit trail. Spring Boot Actuator health monitoring. Per-channel error isolation so one chat platform's outage doesn't take down the rest. One JAR on your own machine, zero data egress.

Most AI tools die when their vendor has a bad day. Most forget you the moment the tab closes. Most give you a chatbox and call it a product.

**MateClaw is the whole widget.** One deployment. Reasoning, knowledge, memory, tools, channels — built together, not bolted on. And when your primary model goes down, the next one picks up mid-sentence.

---

## Three things that make it different

### 1 · Your AI doesn't die when a model does

Primary key expired. Vendor returns 401. Network blip. Quota drained.

Other tools hand you a red error card. MateClaw routes to the next healthy provider — DashScope, OpenAI, Anthropic, Gemini, DeepSeek, Kimi, Ollama, LM Studio, MLX, 14+ in total — and the user sees the reply finish. A provider health tracker parks bad vendors in a cooldown window so they don't waste seconds on every turn.

You don't write a retry script. You drag providers into priority order in **Settings → Models** and watch the health dashboard fill with green dots as requests route around failures in real time.

### 2 · Knowledge that links itself

Upload a PDF, a batch of markdown, a scraped page — raw material in.

MateClaw's **LLM Wiki** digests it into structured pages, builds `[[links]]` between them, and remembers where every sentence came from. Click a citation, see the exact source chunk. Ask a question, the page you get is stitched from the right chunks — with references you can verify.

This is the difference between a warehouse and a library.

### 3 · One product, five surfaces

| Surface | What it is |
|---|---|
| **Web Console** | Full admin — agents, models, tools, skills, knowledge, security, cron |
| **Desktop** | Electron app with a bundled JRE 21. Double-click, run. No Java install |
| **Webchat Widget** | One `<script>` tag embed. Drop it on any site |
| **IM Channels** | DingTalk · Feishu · WeChat Work · Telegram · Discord · QQ |
| **Plugin SDK** | Java module for third-party capability packs |

Same brain. Same memory. Same tools. Different doors.

<p align="center"><b>$0 · No tokens metered. No seats billed. Your server. Your data. Your keys.</b></p>

---

## What's in the box

### Agent runtime
**ReAct** for iterative reasoning. **Plan-and-Execute** for complex multi-step work. Dynamic context pruning, smart truncation, stale-stream cleanup — the boring stuff that makes long conversations actually work.

### Knowledge & memory
- **LLM Wiki** — raw materials digest into linked pages with citations
- **Workspace memory** — `AGENTS.md`, `SOUL.md`, `PROFILE.md`, `MEMORY.md`, daily notes
- **Memory lifecycle** — post-conversation extraction, scheduled consolidation, dreaming workflows

### Tools, skills, MCP
Built-in tools for web search, files, memory, date/time. **MCP** over stdio / SSE / Streamable HTTP. **SKILL.md** packages from the ClawHub marketplace. A **Tool Guard** layer with RBAC, approval flows, and path protection — capability needs boundaries.

### Multimodal creation
Text-to-speech · Speech-to-text · Image · Music · Video. First-class, not add-ons.

### Enterprise-ready
RBAC + JWT. Full audit trail. Flyway-managed schema that auto-heals on upgrade. One JAR to ship. MySQL in production, H2 for dev — nothing to change in your code.

---

## AI is becoming infrastructure

On March 2, 2026, Claude went dark for 4 hours across API, web, and mobile. Three weeks later, another 5 hours. Every company that bet their AI strategy on a single vendor spent those outages staring at red error cards.

This is the same shift databases went through around 2010 and cloud went through around 2018: the winning layer stops being tied to one supplier. **57% of companies now run AI agents in production.** None of them want one vendor's bad day to become their bad day.

**MateClaw is that layer — built the Spring Boot way.**

---

## Why MateClaw

| | MateClaw | [OpenClaw](https://github.com/openclaw/openclaw) | [Hermes Agent](https://github.com/NousResearch/hermes-agent) | [Claude Code](https://github.com/anthropics/claude-code) | [Cursor](https://cursor.com) |
|:---|:---:|:---:|:---:|:---:|:---:|
| **Multi-vendor failover** | **Chain + health tracker + cooldown** | Swap providers via config | Orchestration w/ retry | Anthropic only | One model |
| **Knowledge digestion** | **LLM Wiki + page-level citations** | Canvas + memory | Skills Hub + memory | — | Code index |
| **Multi-user admin** | **RBAC + approval flow + audit** | Config-file first | Single-user CLI | Enterprise tier | Teams plan |
| **Surfaces** | Web admin + Desktop + Widget + SDK + 6 IM | 25+ chat channels | 15+ channels (CLI-led) | 3 IM preview | IDE only |
| **Stack** | **Java (Spring Boot)** | TypeScript | Python | TypeScript | Electron/TS |
| **License / Price** | **Apache 2.0 · Free** | MIT · Free | MIT · Free | Proprietary · $20–200/mo | Proprietary · $0–200/mo |

**OpenClaw and Hermes Agent are excellent personal AI platforms** — pick either if you're running one user on one laptop, building your own agent from CLI, and treating everything as config files to hand-tune. Both have bigger communities than MateClaw today.

**MateClaw is the version built for teams.** RBAC per agent, per model, per tool. An approval flow that pauses risky actions for review. Full audit trail. A web admin dashboard where one operator manages 50 agents across 14 vendors. Spring Boot inside — drop-in for any Java shop already running production services.

Same "whole widget" philosophy. Different center of gravity.

---

## Quick start

```bash
# Backend
cd mateclaw-server
mvn spring-boot:run           # http://localhost:18088

# Frontend
cd mateclaw-ui
pnpm install && pnpm dev      # http://localhost:5173
```

Login: `admin` / `admin123`

### Docker

```bash
cp .env.example .env
docker compose up -d          # http://localhost:18080
```

### Desktop

Download from [GitHub Releases](https://github.com/matevip/mateclaw/releases). Bundles JRE 21. No Java install needed.

---

## Architecture

<p align="center">
  <img src="assets/architecture-biz-en.svg" alt="Business Architecture" width="800">
</p>

<details>
<summary><b>Technical architecture</b></summary>
<p align="center">
  <img src="assets/architecture-tech-en.svg" alt="Technical Architecture" width="800">
</p>
</details>

---

## Project structure

```
mateclaw/
├── mateclaw-server/        Spring Boot 3.5 backend (Spring AI Alibaba, StateGraph runtime)
├── mateclaw-ui/            Vue 3 + TypeScript admin SPA (built into the server JAR)
├── mateclaw-webchat/       Embeddable chat widget (UMD / ES bundles)
├── mateclaw-plugin-api/    Java SDK for third-party capability plugins
├── mateclaw-plugin-sample/ Reference plugin implementation
├── docker-compose.yml
└── .env.example
```

Desktop binaries ship via [GitHub Releases](https://github.com/matevip/mateclaw/releases) with a bundled JRE 21 — no Java install needed.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.5 · Spring AI Alibaba 1.1 · MyBatis Plus · Flyway |
| Agent | StateGraph runtime · ReAct + Plan-Execute |
| Database | H2 (dev) · MySQL 8.0+ (prod) |
| Auth | Spring Security + JWT |
| Frontend | Vue 3 · TypeScript · Vite · Element Plus · TailwindCSS 4 |
| Desktop | Electron · electron-updater · JRE 21 (bundled) |
| Widget | Vite library mode · UMD + ES bundles |

---

## Documentation

Full docs at **[claw.mate.vip/docs](https://claw.mate.vip/docs)** — setup, architecture, each subsystem, API reference.

## Roadmap

Sharper multi-agent collaboration · Smarter model routing · Deeper multimodal understanding · Longer-lived memory · A richer ClawHub.

## Contributing

```bash
git clone https://github.com/matevip/mateclaw.git
cd mateclaw
cd mateclaw-server && mvn clean compile
cd ../mateclaw-ui && pnpm install && pnpm dev
```

---

## Why the name

**Mate** is companion. **Claw** is capability.

Something that stays with you — and grabs work and moves it.

## License

[Apache License 2.0](LICENSE). No asterisks.
