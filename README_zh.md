<div align="center">

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

# MateClaw

<p align="center"><b>让 AI 真正去思考、行动、记忆，并把结果交付出来。</b></p>

[![GitHub 仓库](https://img.shields.io/badge/GitHub-仓库-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![文档](https://img.shields.io/badge/文档-在线-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![在线演示](https://img.shields.io/badge/演示-在线-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![官网](https://img.shields.io/badge/官网-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java 版本](https://img.shields.io/badge/Java-17+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![最后提交](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)

[[官网](https://claw.mate.vip)] [[在线演示](https://claw-demo.mate.vip)] [[文档](https://claw.mate.vip/docs)] [[English](README.md)]

</div>

MateClaw 是一个基于 **Java + Vue 3** 构建的个人 AI 操作系统，由 [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) 驱动。

它不是另一个聊天框，而是一整套 AI 工作系统：能推理、能调工具、能记住上下文、能联网、能把原始资料消化成 Wiki、能生成多模态内容，也能出现在真正发生工作的渠道里。

这个产品的核心想法很简单：

- 给每个 Agent 清晰的角色
- 给它真正可用的工具和边界
- 让它拥有记忆，而不是每次从零开始
- 让它跨聊天、文档、知识、媒体和渠道协同工作
- 保持整套系统能被一个团队部署、理解和持续迭代

---

## MateClaw 到底是什么

MateClaw 不是单点产品，而是 4 个产品层合成的一个系统：

1. **AI 控制台**：人直接和 AI 交互
2. **Agent 运行时**：让模型分步推理、调用工具、执行任务
3. **知识系统**：把原始信息沉淀成记忆和结构化 Wiki
4. **交付面**：覆盖 Web、桌面和外部渠道

大多数产品只做其中一层。MateClaw 的目标，是把这四层做成一个完整工作系统。

---

## 产品思路

### 1. Agent 不该只会聊天，应该会干活

MateClaw 支持 ReAct 和 Plan-and-Execute。模型不只是生成回答，而是能拆解任务、调用工具、观察结果，再继续推进。

### 2. 知识不该反复原样读取，而应该被消化

原始文档很重要，但结构化知识更重要。MateClaw 内置 LLM Wiki 知识库，把文本、PDF、DOCX 等材料消化成可链接、可搜索、可编辑的 Wiki 页面。

### 3. 记忆应该越用越值钱

会话结束不该等于遗忘。MateClaw 把短期上下文、对话后提取、工作空间记忆文件和定时整合放进同一套体系，让 Agent 能积累连续性。

### 4. 工具要强，但不能失控

没有边界的工具系统不是能力，是事故源。MateClaw 提供工具防护、审批、路径校验和运行时过滤，让强能力能被放心使用。

### 5. AI 必须出现在真实工作的地方

真正有用的 AI 不能只困在一个网页里。MateClaw 连接桌面、Web 和外部消息渠道，让 Agent 出现在任务发生的地方。

---

## 你可以拿它做什么

### 个人 AI 工作台

- 一个有记忆、有工具、有工作空间文件的长期助手
- 一个开箱即用的桌面应用
- 一个用于聊天、规划和配置的 Web 控制台

### 团队知识助手

- 导入笔记、文档、PDF、DOCX
- 把原始材料转成结构化 Wiki 页面
- 让 Agent 按需搜索、总结、阅读知识，而不是反复扫描原文

### 会用工具的 AI Worker

- 能联网搜索、读文件、接 MCP 工具、执行工作流的 Agent
- 按角色安装技能包
- 对敏感动作进行审批和防护

### 多模态内容生产系统

- 文字转语音
- 语音转文字
- 音乐生成
- 图片生成
- 视频生成

### 多渠道 AI 存在

- Web 控制台
- 钉钉
- 飞书
- 企业微信
- Telegram
- Discord
- QQ

---

## 核心能力

### Agent 运行时

- **ReAct Agent**：支持思考 → 行动 → 观察循环
- **Plan-and-Execute Agent**：适合拆解复杂任务并按步骤执行
- **动态 Agent 配置**：运行时加载，不需要把配置写死
- **多 Agent 体系**：每个 Agent 有自己的提示词、人格、工具范围
- **更稳定的长任务执行**：支持上下文裁剪、智能截断、陈旧流清理、恢复机制

### 知识与记忆

- **LLM Wiki 知识库**：把原始材料转成结构化、可链接的 Wiki
- **工作空间记忆文件**：如 `AGENTS.md`、`SOUL.md`、`PROFILE.md`、`MEMORY.md`、daily notes
- **对话后自动提取**：把有价值的信息沉淀下来
- **定时整合**：不是一味堆积，而是持续整理
- **Dreaming / Emergence 记忆机制**：用于更长时间尺度上的记忆优化

### 工具、技能与搜索

- **内置工具**：搜索、文件、记忆、时间等能力
- **更强的联网搜索**：支持多 Provider、回退链和实时信息获取
- **MCP 集成**：支持 stdio、SSE、Streamable HTTP
- **技能系统**：通过 `SKILL.md` 安装和组织技能
- **ClawHub 市场**：发现和安装技能
- **工具防护与审批**：保障高权限操作的可控性

### 多模态创作

- **文字转语音**
- **语音转文字**
- **音乐生成**
- **图片生成**
- **视频生成**

### 模型灵活性

可在 Web 界面中配置云端与本地模型，支持：

- DashScope
- OpenAI
- Anthropic
- Google Gemini
- DeepSeek
- Kimi
- MiniMax
- 智谱 AI
- 火山引擎
- OpenRouter
- Ollama
- LM Studio
- llama.cpp
- MLX

### 使用入口

- **Web 应用**：聊天、Agent、MCP、模型、工具、渠道、安全配置
- **桌面应用**：内置 JRE 21 和后端
- **外部渠道**：适合真正面向业务场景的接入

---

## 为什么 Wiki 很重要

多数 AI 系统把知识当成“原始碎片仓库”。

MateClaw 多做了一层：让 AI 把知识整理成结构化 Wiki。不是每次临时从原始文档里切几段，而是先把知识变成清晰页面，再按需读取。

这会带来三个变化：

- Agent 不再把大量上下文浪费在原始材料上
- 人可以直接检查、编辑、维护知识结构
- 知识会随着使用而变得更清晰，而不是每次查询都重新理解

这就是“存信息”和“塑造知识”的区别。

---

## 快速开始

### 前置条件

- Java 17+
- Node.js 18+ 和 pnpm
- Maven 3.9+（或使用 `mvnw`）
- 至少一个 LLM API Key，例如 [DashScope](https://dashscope.aliyun.com/)

### 方式一：本地开发

**启动后端**

```bash
cd mateclaw-server
export DASHSCOPE_API_KEY=your-key-here
mvn spring-boot:run
```

后端地址：

- 应用：`http://localhost:18088`
- H2 Console：`http://localhost:18088/h2-console`
- Swagger UI：`http://localhost:18088/swagger-ui.html`

**启动前端**

```bash
cd mateclaw-ui
pnpm install
pnpm dev
```

前端地址：

- 应用：`http://localhost:5173`

**登录**

- 用户名：`admin`
- 密码：`admin123`

### 方式二：Docker

```bash
cp .env.example .env
docker compose up -d
```

默认服务地址：

- `http://localhost:18080`

### 方式三：桌面应用

从 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 下载桌面安装包。

桌面应用内置 **JRE 21 + Spring Boot 后端**，无需额外安装 Java。

> macOS：如果首次打开被系统拦截，使用右键 → 打开，或在隐私与安全性中手动允许。

---

## 架构

```text
mateclaw/
├── mateclaw-server/     Spring Boot 后端
├── mateclaw-ui/         Vue 3 SPA 前端
├── mateclaw-desktop/    Electron 桌面端
├── docs/                VitePress 文档
├── docker-compose.yml
└── .env.example
```

后端核心领域包括：

- `agent/`：Agent 运行时与编排
- `tool/`：内置工具与 MCP 集成
- `skill/`：技能安装与执行
- `memory/`：提取、整合、dreaming
- `wiki/`：知识库与结构化 Wiki 处理
- `channel/`：外部渠道适配
- `workspace/`：文件、消息、会话

---

## 技术栈

| 层次 | 技术 |
|------|------|
| 后端 | Spring Boot 3.5 + Spring AI Alibaba 1.1 |
| Agent Runtime | StateGraph |
| 数据库 | H2（开发）/ MySQL 8.0+（生产） |
| ORM | MyBatis Plus 3.5 |
| 认证 | Spring Security + JWT |
| 前端 | Vue 3 + TypeScript + Vite |
| 状态管理 | Pinia |
| UI | Element Plus |
| 样式 | TailwindCSS 4 |
| 桌面端 | Electron + electron-updater |
| 文档 | VitePress |

---

## 文档

| 主题 | 说明 |
|------|------|
| [项目介绍](https://mateclaw.mate.vip/zh/intro) | 产品定位与核心概念 |
| [快速开始](https://mateclaw.mate.vip/zh/quickstart) | 本地、Docker、桌面启动 |
| [控制台](https://mateclaw.mate.vip/zh/console) | Web 控制台日常使用 |
| [Agents](https://mateclaw.mate.vip/zh/agents) | ReAct、Plan-and-Execute 与运行时设计 |
| [模型配置](https://mateclaw.mate.vip/zh/models) | 模型 Provider 配置 |
| [工具系统](https://mateclaw.mate.vip/zh/tools) | 内置工具与扩展能力 |
| [技能系统](https://mateclaw.mate.vip/zh/skills) | 技能包与市场 |
| [MCP](https://mateclaw.mate.vip/zh/mcp) | Model Context Protocol 集成 |
| [记忆系统](https://mateclaw.mate.vip/zh/memory) | 记忆架构 |
| [渠道接入](https://mateclaw.mate.vip/zh/channels) | 外部渠道适配 |
| [安全机制](https://mateclaw.mate.vip/zh/security) | 防护与审批 |
| [桌面应用](https://mateclaw.mate.vip/zh/desktop) | 桌面端使用指南 |
| [API 参考](https://mateclaw.mate.vip/zh/api) | REST API |
| [常见问题](https://mateclaw.mate.vip/zh/faq) | 排障与说明 |

---

## 路线图

当前重点方向包括：

- 更强的多 Agent 协作
- 更深的多模态理解
- 更聪明的模型路由
- 更强的长期记忆
- 更丰富的 ClawHub 生态
- 更多渠道与桌面端覆盖

---

## 参与贡献

MateClaw 欢迎产品、代码、文档、集成四类贡献。

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

## 为什么叫 MateClaw

**Mate** 是伙伴。  
**Claw** 是能力。

这个产品想给人的感受，就是两者同时成立：它不是只陪你说话，也能真正抓住任务，把事情往前推进。

---

## 许可证

MateClaw 基于 [Apache License 2.0](LICENSE) 发布。
