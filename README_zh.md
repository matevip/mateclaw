<div align="center">

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

# 太一（MateClaw）

<p align="center"><b>你的超级大脑</b></p>

[![GitHub 仓库](https://img.shields.io/badge/GitHub-仓库-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![文档](https://img.shields.io/badge/文档-在线-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![在线演示](https://img.shields.io/badge/演示-在线-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![官网](https://img.shields.io/badge/官网-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java 版本](https://img.shields.io/badge/Java-21+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![最后提交](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)

[[官网](https://claw.mate.vip)] [[在线演示](https://claw-demo.mate.vip)] [[文档](https://claw.mate.vip/docs)] [[English](README.md)]

</div>

<p align="center">
  <img src="assets/images/preview.png" alt="MateClaw 预览" width="800">
</p>

---

> **别的 AI 助手是给一个人用的。MateClaw 是公司允许部署的那一个。**
>
> 多用户工作空间。敏感操作走审批。完整审计日志。Spring Boot Actuator 健康监控。单个渠道挂掉不影响其他渠道的错误隔离。一个 JAR 包跑在自己机器上，数据不出门。

大多数 AI 工具一到厂商抽风那天就两手一摊。关一次标签页就忘了你是谁。给你一个聊天框，就敢叫产品。

**MateClaw 是完整的一整套。** 一次部署——推理、知识、记忆、工具、多渠道入口，从第一天就一起设计，不是事后拼接。主模型挂了，下一家接着把这句话说完。

---

## 三件让它与众不同的事

### 1 · 模型挂了，AI 不挂

Key 过期。厂商返回 401。网络抖动。配额耗尽。

别的工具丢你一张红色错误卡。MateClaw 自动切到下一家健康的供应商——DashScope、OpenAI、Anthropic、Gemini、DeepSeek、Kimi、Ollama、LM Studio、MLX，共 14+ 家——用户只会看到回答正常完成。内置的 **Provider Health Tracker** 会把连续失败的供应商放进冷却窗口，避免每一轮对话都白白撞壁。

你不用写重试脚本。在 **设置 → 模型** 里把供应商拖成你想要的优先顺序，健康面板实时亮起一排绿点——请求绕着故障流过去。

### 2 · 知识会自己长出链接

上传 PDF、一批 markdown、抓下来的网页——原始材料进去。

MateClaw 的 **LLM Wiki** 把它消化成结构化页面，页面之间自己长出 `[[链接]]`，每一句话都记得来自哪里。点开引用抽屉，就能看到原始 chunk。问一个问题，得到的页面是从对应片段拼出来的——带可核对的出处。

这是**仓库**和**图书馆**的区别。

### 3 · 一个产品，五个入口

| 入口 | 它是什么 |
|---|---|
| **Web 控制台** | 完整的管理后台——智能体、模型、工具、技能、知识、安全、定时任务 |
| **桌面端** | Electron + 内嵌 JRE 21，双击即用，无需装 Java |
| **网页嵌入式聊天** | 一个 `<script>` 标签就能嵌进任何网站 |
| **IM 渠道** | 钉钉 · 飞书 · 企业微信 · Telegram · Discord · QQ |
| **插件 SDK** | Java 模块，供第三方扩展能力包 |

同一个大脑。同一份记忆。同一套工具。不同的门。

<p align="center"><b>$0 · 无 token 计费。无座位收费。你的服务器，你的数据，你的 Key。</b></p>

---

## 盒子里有什么

### 智能体引擎
**ReAct** 做迭代推理。**Plan-and-Execute** 做复杂多步任务。动态上下文裁剪、智能截断、僵死流清理——让长对话真正能用的那些"不起眼"的基础设施。

### 知识与记忆
- **LLM Wiki** — 原始材料消化成有链接、带引用的结构化页面
- **工作区记忆** — `AGENTS.md` / `SOUL.md` / `PROFILE.md` / `MEMORY.md` / 每日笔记
- **记忆生命周期** — 对话后自动提取 · 定时整理 · 记忆涌现工作流

### 工具、技能、MCP
内置工具覆盖搜索、文件、记忆、日期。**MCP** 支持 stdio / SSE / Streamable HTTP 三种传输。**SKILL.md** 包可从 ClawHub 市场安装。**工具防护**层提供 RBAC、审批流、文件路径保护——能力必须有边界。

### 多模态创作
语音合成 · 语音识别 · 图片 · 音乐 · 视频。一等公民，不是附加插件。

### 企业就绪
RBAC + JWT。完整审计事件流。Flyway 管理数据库 schema，升级时自愈。一个 JAR 交付。生产用 MySQL，开发用 H2，代码零改动。

---

## AI 正在变成基础设施

2026 年 3 月 2 日，Claude 全球宕机 **4 小时**——API、Web、移动端同时黑屏。三周后又来一次，**5 小时**。每一家把 AI 战略押在单一厂商身上的公司，那几个小时只能盯着红色错误卡。

这和 2010 年数据库走过的路、2018 年云走过的路**是同一个转弯**：赢的那一层，不再绑在一家供应商身上。**57% 的公司已经把 AI agent 推进生产**——没有一家希望某个厂商的坏日子变成自己的坏日子。

**MateClaw 就是那一层——用 Spring Boot 方式盖的。**

---

## 为什么选 MateClaw

| | MateClaw | [OpenClaw](https://github.com/openclaw/openclaw) | [Hermes Agent](https://github.com/NousResearch/hermes-agent) | [Claude Code](https://github.com/anthropics/claude-code) | [Cursor](https://cursor.com) |
|:---|:---:|:---:|:---:|:---:|:---:|
| **多厂商失败转移** | **Chain + 健康追踪 + 冷却** | 切换供应商（改配置） | 内置编排重试 | 仅 Anthropic | 单模型 |
| **知识消化式加工** | **Wiki + 页面级引用溯源** | Canvas + 记忆 | Skills Hub + 记忆 | — | 代码索引 |
| **多用户管理** | **RBAC + 审批流 + 审计** | 配置文件优先 | 单用户 CLI | 企业版 | 团队版 |
| **用户触点** | Web 管理台 + 桌面 + 嵌入 + SDK + 6 IM | 25+ 聊天渠道 | 15+ 渠道（CLI 为主） | 3 IM（预览） | 仅 IDE |
| **技术栈** | **Java（Spring Boot）** | TypeScript | Python | TypeScript | Electron/TS |
| **许可 / 定价** | **Apache 2.0 · 免费** | MIT · 免费 | MIT · 免费 | 闭源 · $20–200/月 | 闭源 · $0–200/月 |

**OpenClaw 和 Hermes Agent 是优秀的个人 AI 平台**——如果你是一个人、一台笔记本、习惯从 CLI 搭自己的 agent、所有东西都靠手工配置文件调优，选它们没问题。两家的社区规模今天都大于 MateClaw。

**MateClaw 是那个给团队用的版本。** 每个 agent、每个模型、每个工具都有 RBAC。危险动作自动暂停等审批。完整审计事件流。一个 Web 管理台里，一个运维能同时管 50 个 agent 跑在 14 家供应商上。底座是 Spring Boot——任何一家已经在生产跑 Java 服务的公司可以直接并入。

**同一套"完整一整套"哲学，不同的重心。**

---

## 快速开始

```bash
# 后端
cd mateclaw-server
mvn spring-boot:run           # http://localhost:18088

# 前端
cd mateclaw-ui
pnpm install && pnpm dev      # http://localhost:5173
```

默认登录：`admin` / `admin123`

### Docker 部署

```bash
cp .env.example .env
docker compose up -d          # http://localhost:18080
```

### 桌面端

从 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 下载安装包。内嵌 JRE 21，无需额外装 Java。

---

## 架构全景

<p align="center">
  <img src="assets/architecture-biz-zh.svg" alt="业务架构" width="800">
</p>

<details>
<summary><b>技术架构</b></summary>
<p align="center">
  <img src="assets/architecture-tech-zh.svg" alt="技术架构" width="800">
</p>
</details>

---

## 项目结构

```
mateclaw/
├── mateclaw-server/        Spring Boot 3.5 后端（Spring AI Alibaba · StateGraph 运行时）
├── mateclaw-ui/            Vue 3 + TypeScript 管理 SPA（构建产物打进后端 JAR）
├── mateclaw-webchat/       网页嵌入式聊天组件（UMD / ES bundle）
├── mateclaw-plugin-api/    第三方能力插件的 Java SDK
├── mateclaw-plugin-sample/ 参考插件实现
├── docker-compose.yml
└── .env.example
```

桌面端安装包通过 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 分发，内嵌 JRE 21——无需安装 Java。

## 技术栈

| 层次 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5 · Spring AI Alibaba 1.1 · MyBatis Plus · Flyway |
| 智能体 | StateGraph 运行时 · ReAct + Plan-Execute |
| 数据库 | H2（开发）· MySQL 8.0+（生产）|
| 认证 | Spring Security + JWT |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · TailwindCSS 4 |
| 桌面端 | Electron · electron-updater · 内嵌 JRE 21 |
| Webchat | Vite library 模式 · UMD + ES bundle |

---

## 文档

完整文档 **[claw.mate.vip/docs](https://claw.mate.vip/docs)**——安装、架构、各子系统、API 参考。

## 路线图

更强的多智能体协作 · 更智能的模型路由 · 更深度的多模态理解 · 更长久的记忆 · 更繁荣的 ClawHub。

## 参与贡献

```bash
git clone https://github.com/matevip/mateclaw.git
cd mateclaw
cd mateclaw-server && mvn clean compile
cd ../mateclaw-ui && pnpm install && pnpm dev
```

---

## 为什么叫这个名字

**Mate** 是陪伴。**Claw** 是能力。

一个陪在你身边的系统——也是一个真的能抓住工作、把它推向完成的系统。

## 许可证

[Apache License 2.0](LICENSE)。没有星号。
