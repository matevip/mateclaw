---
name: ckjia-shopping
nameZh: 参考价 - 比价购物
nameEn: CKJIA Shopping
version: "1.0.0"
icon: /skill-assets/ckjia-shopping/assets/ckjia_app_icon.png
description: "跨平台比价与购物推荐 / Cross-platform price comparison. 淘宝 / 京东 / 天猫 / 拼多多商品聚合搜索 + 拍图识物。需要先启用 ckjia-shopping MCP server 并配置 CKJIA_MCP_KEY 才能用。"
category: data
type: mcp
tags:
  - shopping
  - price
  - ecommerce
  - 比价
  - 购物
---

<!-- NOTE: dependencies.tools intentionally omitted. The three MCP tools
     (ckjia_shopping_recommend / ckjia_image_recognize / ckjia_ping) live
     on a remote MCP server and are registered into ToolRegistry at
     runtime via McpToolCallbackProvider — they're never persisted to the
     mate_tool table that SkillDependencyChecker.selectCount queries, so
     listing them here would permanently mark the skill as "unresolved".
     Tool availability is enforced by the ckjia-shopping MCP server being
     enabled + healthy in Settings ▸ MCP Connections instead. -->


# 参考价 - 比价购物

当用户询问"X 多少钱 / 哪里便宜 / 帮我推荐 X / 这个值不值买 / 拍照认一下这是什么"时使用本技能。

## 决策树

1. **"推荐 / 帮我挑 / 性价比 / 想买 X"** → `ckjia_shopping_recommend(query, top_n=5)`
   - 想要 ckjia 顺便给出意图理解（用于澄清后续问句）→ `include_intent=true`
2. **附带图片 / 拍照识物** → `ckjia_image_recognize(image_url)` → 拿到 `suggested_query` 后再 `ckjia_shopping_recommend(suggested_query)`
3. **transport 健康自检** → `ckjia_ping("hello")`，验证 MCP 链路通

## 输出格式（必须遵守）

每个 ProductCard **必须输出以下信息，缺一不可**——这是用户做购买决策的关键信号：

1. **商品名 + 平台**：用 `[name](url)` markdown 链接形式，**链接必须是 ProductCard.url 字段的真实跳转地址**，否则用户没法点开购买
2. **现价 + 原价**：`¥currentPrice`（划掉 `~~原价~~` 如果有 `originalPrice`）+ 折扣率 `discountPct`
3. **平台 + 店铺**：`[platformLabel] shopName`（用 `platformLabel` 不是 `platformCode`）
4. **历史最低价对比**：如果 `lowestPrice` 比 `price` 低很多，明确提示"当前比历史最低高 ¥X，可以再等等"
5. **purchaseAdvice**：如果有，原样附在卡片末尾

### 推荐输出模板

```markdown
**1. [当日发货 Apple iPhone 17 Pro](https://uland.taobao.com/coupon/edetail?...)**
- 💰 ¥9,999 ~~¥10,999~~ (省 ¥1,000，9 折)
- 🛒 淘宝 · {shopName}
- 📊 评分 4.8 · 销量 1.2 万
- 💡 历史最低 ¥9,499（2025-12），当前略高，可观察
```

### 严禁的错误

- ❌ 只列商品名不带链接 / 链接是占位符
- ❌ 只说"在淘宝有售"不给具体跳转 URL
- ❌ 拼接虚构的 URL（必须用 `ProductCard.url` 原文）

## 其它字段处理

- `intent` 字段是给 agent 自己看的"我理解对了吗"——拿到后若 `budgetRange` 与用户原话不符，应主动澄清
- `needsClarification=true` 时，把 `clarificationQuestion` + `clarificationOptions` 直接抛给用户选，不要硬猜

## 注意

- 同一个 query 不要在一次对话里反复调用 —— ckjia 侧已有缓存，重复调用浪费配额
- 用户未登录时不必填 `user_id`；当前 Phase 1 所有调用以 API key owner 身份执行
- API key 由管理员在 ckjia 控制台申请后填入 mateclaw `Settings ▸ MCP Connections` 的 `headers_json`，使用 `${CKJIA_MCP_KEY}` 环境变量占位符避免明文落库
- 触发 429 `rate_limited` 时按 `Retry-After` 等待一次，再失败就汇总现有结果而不是无限重试

## 如何申请 API Key

1. 访问 ckjia 控制台 `https://ckjia.com/console/mcp-keys`（自助申请页面 P2 落地后开放；Phase 1 需联系运维手工签发）
2. 选 `free` / `standard` tier 与勾选所需 scopes
3. 一次性获得明文 key（形如 `ckjia_mcp_live_5fK8j2nQ…`）
4. 在 mateclaw 部署环境配 `CKJIA_MCP_KEY=ckjia_mcp_live_xxx`
5. Settings ▸ MCP Connections 启用 `ckjia-shopping` 即可
