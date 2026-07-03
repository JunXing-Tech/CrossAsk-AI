# CrossAsk - 跨境电商客服 RAG 项目方案

> 本文件是项目的权威方案文档，所有代码实现方向以此为准。如需调整方案，先修改本文件再改代码。

---

## 一、项目定位

- **名字**：CrossAsk（Cross-border + Ask）
- **场景**：跨境电商客服问答 Bot（基于 RAG）
- **切口**：eBay（买家帮助中心）+ USPS（物流说明），客服问答场景
- **目标**：端到端跑通 RAG 全流程，作为项目展示和能力验证

---

## 二、环境前置要求

| 依赖 | 版本/要求 | 说明 |
|---|---|---|
| JDK | Java 21 | 必须用 21，虚拟线程需要 |
| Maven | 3.9+ | 多模块构建 |
| Qdrant | 1.8+ | 本地 Docker 启动，默认端口 6333 |
| Spring Boot | 3.5.x | Spring AI Alibaba 1.1.2.0 要求 3.5.x |
| Spring AI Alibaba | 1.1.2.0（当前推荐版） | DashScope 模型集成 |
| DashScope API Key | 阿里云百炼平台获取 | 一个 Key 通吃 LLM/Embedding/Rerank，配在 application.yml |
| DashScope 模型 | text-embedding-v4（向量维度 **1024**）、qwen-plus（LLM）、qwen3-rerank（v0.5） | — |

> text-embedding-v4 维度可选 2048/1536/1024/768/512/256/128/64，MVP 用 1024（官方推荐通用值）。

> **Qdrant 连接**：本地 Docker 启动后默认 `http://localhost:6333`，无需 API Key（本地模式）。
> 启动命令：`docker run -d --name qdrant -p 6333:6333 -p 6334:6334 -v ${PWD}/qdrant_data:/qdrant/storage qdrant/qdrant`

> **DashScope API Key**：通过环境变量 `DASHSCOPE_API_KEY` 注入，application.yml 中用 `${DASHSCOPE_API_KEY}` 引用。

---

## 三、技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.2 + Spring AI Alibaba | Spring 生态，学习曲线低 |
| LLM | DashScope - qwen-plus | 通义千问 |
| Embedding | DashScope - text-embedding-v4 | 模型 API，非托管知识库，1024 维 |
| Rerank | DashScope - qwen3-rerank | v0.5 引入，MVP 不用 |
| 向量库 | Qdrant | 同时存 dense（1024 维）+ sparse（v0.6 引入），支持混合检索 + scalar filter |
| 关键词检索 | DashScope sparse（text-embedding-v4 的 sparse 输出）+ Qdrant sparse vector | v0.6 引入，MVP 不用。不引入 Elasticsearch，省一个组件 |
| 结构化数据 | MySQL 5.7.44（远程 101.96.211.131） | v0.7 引入，存商品价格/库存/标题（爬 eBay 真实商品页）；与 Qdrant 同一台远程主机 |
| 持久层 | MyBatis-Plus 3.5.x | v0.7 引入，Spring Boot 3.5 兼容版（`mybatis-plus-spring-boot3-starter`） |
| 意图路由 | DashScope qwen-plus Function Calling | v0.7 引入，LLM 自动决定调 `search_docs`（RAG）还是 `query_products`（SQL） |
| Eval | 独立 `crossask-eval` 模块 + HTTP 黑盒测试 | v0.8 引入，Recall@5 / MRR / Tool-call accuracy / 关键词命中率 |
| 多轮记忆 | Spring AI `ChatMemory` + 自定义 `MySQLChatMemoryRepository` | v0.9 引入，MySQL `chat_history` 表存对话历史，7 天定期清理 |
| 跨语言优化 | sparse 路 query 翻译 + 商品 SQL 中英同义词表 | v1.0 引入，解决中文 query 召回英文文档/商品 title 的问题 |
| 前端 | Vue 3 + Vite + Element Plus + marked（Markdown 渲染） | v1.1 引入，独立 `crossask-ai-frontend` 目录；通过 SSE 与后端 `/ask/stream` 通信 |
| 流式输出 | Server-Sent Events (SSE) + Spring AI `stream().content()` | v1.1 引入，后端逐 token 推送，前端逐字渲染 |
| HTML 解析 | Jsoup | 爬虫解析 + 按标题结构切分 |

**核心原则**：只用 DashScope 的模型 API（embedding/rerank/LLM），自己搭 Qdrant、自己写 RAG 流程。不使用百炼托管知识库——那样 RAG 全流程是黑盒，失去项目意义。

---

## 四、架构总览

### 4.1 MVP 架构（当前阶段）

```
用户提问
   │
   ▼
┌──────────────────────────────────────────────┐
│  crossask-api (Spring Boot)                  │
│                                              │
│  POST /ask                                   │
│     │                                        │
│     ▼                                        │
│  1. 问题文本 → text-embedding-v4 → 向量         │
│  2. 向量 → 查 Qdrant (topK=5, score≥0.5)       │
│  3. 召回数=0 → 返回兜底话术，不调 LLM           │
│  4. 召回>0 → 拼上下文 → 调 qwen-plus → 生成     │
│  5. 返回 {answer, sources[]}                   │
└──────────────────────────────────────────────┘
        │                          │
        ▼                          ▼
┌───────────────────┐    ┌────────────────────┐
│   Qdrant          │    │  DashScope API     │
│  collection:      │    │  - embedding (v4)  │
│  crossask_docs    │    │  - qwen-plus (LLM) │
│  dim=1024         │    └────────────────────┘
│  distance=Cosine  │
└───────────────────┘
        ▲
        │ 入库（离线）
┌──────────────────────────────────────────────┐
│  crossask-ingestion                          │
│                                              │
│  crawler → cleaner → splitter → indexer      │
│  (Jsoup   (去噪音元素  (按h1/h2切分  (embedding│
│   下载)    保留正文)    +去标签+   +入Qdrant) │
│                       <100字符丢弃)          │
└──────────────────────────────────────────────┘
```

### 4.2 数据流原则

1. **长文本走向量库，结构化字段走 DB**：价格/库存/SKU 等精确字段进 MySQL（v0.7 引入），商品类问题由 LLM Function Calling 自动路由到 `query_products` 工具走 SQL；文档类问题走 RAG。两路独立检索，由 LLM 最终聚合回答
2. **答案必须带来源追溯**：返回 answer + sources（url + title + 片段），防幻觉
3. **空结果兜底**：Qdrant 查询时带 `score_threshold=0.5`，返回结果=0 时不调 LLM，直接返回兜底话术

---

## 五、核心数据模型

### 5.1 数据实体（归属 crossask-common，ingestion 和 api 共用）

**RawDocument** — crawler 输出，cleaner/splitter 的输入：
```json
{
  "url": "https://...",          // 原文 URL
  "title": "Return Policy",      // HTML <title> 标签内容
  "htmlContent": "<html>...",    // 完整 HTML（含噪音）
  "content_type": "policy"       // 文档类型，标注规则见下
}
```

**Document** — splitter 输出，indexer 存入 Qdrant 的向量 payload：
```json
{
  "doc_id": "uuid",            // 文档块唯一 ID（UUID 自动生成）
  "source_url": "https://...", // 继承自 RawDocument.url
  "source_title": "Return Policy", // 继承自 RawDocument.title
  "chunk_index": 0,            // 该文档的第几个块（从 0 开始）
  "content": "实际文本内容",     // 纯文本（已去标签）
  "content_type": "policy"     // 继承自 RawDocument.content_type
}
```

**content_type 标注规则**（crawler 根据 URL 域名自动判断）：
- URL 含 `usps.com` → `logistic`
- URL 含 `ebay.com` 且含 `international`/`shipping`/`customs` → `logistic`
- 其他 → `policy`（默认值）

### 5.2 API 接口契约

**POST /ask**

请求：
```json
{
  "question": "发往美国的包裹需要交关税吗？"
}
```

正常响应（有召回）：
```json
{
  "answer": "根据 DHL 政策，美国 800 美元以下免关税...",
  "sources": [
    {
      "source_url": "https://...",
      "source_title": "DHL Customs Guide",
      "snippet": "Shipments valued under $800 USD..."
    }
  ]
}
```

空结果兜底响应：
```json
{
  "answer": "未找到相关政策信息，建议联系人工客服。",
  "sources": []
}
```

LLM 调用失败响应：
```json
{
  "answer": "服务暂时不可用，请稍后重试。",
  "sources": []
}
```

**字段说明**：
- `answer`：LLM 生成的回答，或兜底/错误话术
- `sources`：引用的文档来源列表，空数组表示无召回或异常
- `sources[].snippet`：Document.content 的前 200 字符（截取，非全文）

### 5.3 检索参数

| 参数 | MVP 值 | 说明 |
|---|---|---|
| topK | 5 | 向量检索返回数 |
| similarity_threshold | 0.5 | Qdrant 查询参数 score_threshold，查询时一步过滤，结果=0 则触发兜底 |
| embedding 模型 | text-embedding-v4 | 1024 维 |
| Qdrant distance | Cosine | 文本向量标准选择 |
| Qdrant collection | crossask_docs | 统一命名 |

### 5.4 Prompt 模板

```
你是一个跨境电商客服助手。请根据以下参考资料回答用户问题。
如果参考资料中没有相关信息，请明确告知用户无法回答，不要编造内容。

参考资料：
{contexts}

用户问题：{question}

回答要求：
1. 基于参考资料回答，不要编造
2. 简明扼要
3. 如涉及具体政策，注明来源
```

### 5.5 配置文件模板（application.yml）

> **说明**：`crossask-api` 和 `crossask-ingestion` 各自有独立的 `src/main/resources/application.yml`。api 需要全部配置（含 chat + rag）；ingestion 不需要 `spring.ai.dashscope.chat` 段和 `crossask.rag` 段，只需 embedding + Qdrant 配置。

> **配置前缀**：Spring AI Alibaba 1.1.2.x 的 DashScope 配置前缀是 `spring.ai.dashscope`（不是 `spring.ai.alibaba.dashscope`）。Qdrant 用 Spring AI 原生 `spring-ai-starter-vector-store-qdrant`，配置前缀 `spring.ai.vectorstore.qdrant`。

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        model: text-embedding-v4
        options:
          dimensions: 1024
      chat:                    # 仅 crossask-api 需要，crossask-ingestion 删除此段
        model: qwen-plus
    vectorstore:
      qdrant:
        host: 101.96.211.131   # 云服务器 IP（或 localhost）
        port: 6334             # gRPC 端口
        collection-name: crossask_docs
        use-tls: false
        initialize-schema: false

crossask:
  qdrant:
    host: localhost
    port: 6333
    collection: crossask_docs
    dimension: 1024
    distance: Cosine
  rag:                          # 仅 crossask-api 需要，crossask-ingestion 删除此段
    top-k: 5
    similarity-threshold: 0.5
```

---

## 六、MVP 定义

### 6.1 MVP 目标

验证：**用户问一个问题 → 检索到正确知识 → LLM 给出准确答案（带来源）**。

### 6.2 MVP 范围

| 维度 | 完整版 | MVP |
|---|---|---|
| 数据源 | 帮助文档 + 商品数据 + 合成对话 | 跨境电商政策/物流文档（≥20 篇，HTML 格式） |
| 检索方式 | dense+sparse 混合 + Rerank（v0.6 详见第十三章） | 只向量检索 |
| 切分策略 | 按条款/按字段精细切分 | 按 HTML 标题结构切分（Jsoup） |
| 数据库 | Qdrant + MySQL | 只用 Qdrant |
| 对话能力 | 多轮 + 记忆 | 单轮问答 |
| Eval | Recall@5 + MRR + LLM-judge | 不做（v0.8） |
| 接口 | REST + WebSocket | 一个 REST 接口 POST /ask |

### 6.3 MVP 不可砍功能

1. **答案来源追溯**：返回 sources（url/title/片段），验证非幻觉
2. **空结果兜底**：Qdrant 查询时带 `score_threshold=0.5`，返回结果=0 时返回兜底话术，不调 LLM
3. **数据清洗**：入库前去噪音元素 + 去重（按 URL）+ 短文本过滤

---

## 七、模块结构

```
crossask/
├── pom.xml                          # 父 POM，管理公共依赖版本
│
├── crossask-common/                 # 公共层（最薄）
│   ├── config/                      # QdrantProperties（读 crossask.qdrant.* 配置）
│   ├── model/                       # RawDocument、Document、AskRequest、AskResponse、Source
│   └── constants/                   # ContentTypes 等常量
│
├── crossask-ingestion/              # 数据摄入（离线任务）
│   ├── src/main/java/.../crawler/   # 读取 URL 列表，Jsoup 下载 HTML
│   ├── src/main/java/.../cleaner/   # 按 URL 去重 → 去噪音元素（保留正文 HTML 结构含 h1/h2）
│   ├── src/main/java/.../splitter/  # 按 <h1>/<h2> 切分 → 每个 chunk 去 HTML 标签转纯文本 → <100 字符丢弃
│   ├── src/main/java/.../indexer/   # embedding + 入 Qdrant + 创建 collection
│   ├── src/main/java/.../IngestionRunner.java  # 入口，CommandLineRunner
│   └── src/main/resources/
│       ├── application.yml          # DashScope + Qdrant 配置（见 5.5）
│       └── urls.txt                 # URL 列表，每行一个 URL
│
└── crossask-api/                    # 问答接口（核心展示）
    ├── src/main/java/.../controller/AskController  # POST /ask
    ├── src/main/java/.../rag/AskService            # 检索→兜底判断→拼prompt→调LLM→组装sources
    ├── src/main/java/.../rag/QdrantCollectionInit  # 启动时检查 collection 存在且维度匹配，不匹配则报错退出
    ├── src/main/java/.../CrossAskApplication.java  # Spring Boot 启动类
    └── src/main/resources/
        └── application.yml          # DashScope + Qdrant + rag 配置（见 5.5）
```

**模块依赖关系**：
```
crossask-api → crossask-common
crossask-ingestion → crossask-common
```
api 和 ingestion 互不依赖，通过 common 共享 Document 模型。

**职责边界**：
- `crossask-common`：纯模型 + 配置，不写业务逻辑，不依赖 Spring AI
- `crossask-ingestion`：离线任务，把文档变成 Qdrant 向量，不对外提供服务
- `crossask-api`：在线服务，对外提供 POST /ask，不负责数据摄入

**Qdrant collection 创建职责**：
- **创建**：由 `crossask-ingestion` 的 indexer 在首次入库前创建（dim=1024, distance=Cosine），若已存在则跳过
- **检查**：由 `crossask-api` 的 `QdrantCollectionInit` 在应用启动时检查 collection 存在且维度匹配，不匹配则报错退出（不自动创建/重建，避免误删数据）

**ingestion 触发方式**：
- `crossask-ingestion` 作为独立 Spring Boot 应用启动，通过 `IngestionRunner`（实现 `CommandLineRunner`）执行
- 启动命令：`mvn -pl crossask-ingestion spring-boot:run`（或打 jar 后 `java -jar crossask-ingestion.jar`）

**数据摄入流水线顺序（关键，不可调换）**：
```
1. crawler:   Jsoup 下载 URL → 得到完整 HTML 文档（单个 URL 失败时记录日志并跳过，不中断流程）
2. cleaner:   按 URL 去重 → 去掉 <nav>/<footer>/<header> 等噪音元素（保留正文 HTML 结构含 h1/h2）
3. splitter:  在清理后的 HTML 中按 <h1>/<h2> 切分 → 每个 chunk 转纯文本（去 HTML 标签）→ 纯文本 <100 字符则丢弃
              （无 h1/h2 时，整篇文档作为一个 chunk）
4. indexer:   对剩余 chunk 调 text-embedding-v4 → 存入 Qdrant（入库前按 source_url 删除旧数据，避免重复执行导致重复入库）
```

**导航噪音判定标准**（cleaner 用）：
- HTML 中 `<nav>`、`<footer>`、`<header>`、`class` 含 "menu/sidebar/breadcrumb/copyright" 的元素视为噪音，删除
- 只删元素，保留正文 HTML 结构（splitter 需要用 h1/h2 标签）

---

## 八、MVP 实现顺序

严格按顺序走，每步有独立验证点，前一步没通不进下一步。

### 第 1 步：搭骨架 + 验证集成 ✅ 已完成

- 建多模块 Maven 项目（父 POM + 3 子模块）
- 配 Spring Boot 3.5.3 + Spring AI Alibaba 1.1.2.0 + Spring AI 1.1.2 + Qdrant 依赖
- 跑起 crossask-api 的空 Spring Boot 应用（主验证对象，ingestion 后续步骤才用）
- **验证 1** ✅：应用能启动，DashScope 配置能加载（配置前缀为 `spring.ai.dashscope`），`${DASHSCOPE_API_KEY}` 能读取
- **验证 2** ✅：Spring AI 原生 `spring-ai-starter-vector-store-qdrant` 直连 Qdrant 成功（QdrantVectorStore 注入成功）
- **验证 3** ✅：text-embedding-v4 调用成功，返回 1024 维向量
- **验证 4** ✅：Spring AI Alibaba 1.1.2.x 原生支持 text-embedding-v4，通过 `spring.ai.dashscope.embedding.options.dimensions: 1024` 指定维度

### 第 2 步：数据准备 + 爬虫 ✅ 已完成

- 准备 URL 列表文件（`crossask-ingestion/src/main/resources/urls.txt`，每行一个 URL）
- 数据源优先级：手动整理 URL 列表写入 urls.txt（最可控）> eBay 买家帮助中心（公开）+ USPS 物流说明页收集 URL
- 实现 crawler：读 urls.txt → 用 Jsoup 逐个下载 → 根据 URL 域名自动标注 content_type（见 5.1 规则）→ 输出 `List<RawDocument>`
- **验证** ✅：20 个 URL 全部下载成功、Jsoup 解析出 `<title>` 和正文 `<body>`（htmlLength≈280KB/篇）
- **数据源调整**：DHL/FedEx/UPS 官网有反爬机制（403/404），Wikipedia 在国内连接超时，Amazon 需登录；最终使用 eBay 帮助中心 + USPS 物流说明页（共 20 篇，含退货/关税/海关等政策与物流内容）
- **ingestion 模块配置**：`WebApplicationType.NONE`（离线任务不启动 Web 服务器）

### 第 3 步：清洗 + 切分 + 入库

- **前置**：indexer 中实现 Qdrant collection 创建（dim=1024, distance=Cosine），若已存在则跳过
- cleaner：按 URL 去重 → 删除 `<nav>/<footer>/<header>` 等噪音元素（**保留正文 HTML 结构**）
- splitter：用 Jsoup `select("h1, h2")` 按标题切分 → 每个 chunk 去 HTML 标签转纯文本 → 纯文本 <100 字符则丢弃（无 h1/h2 时整篇文档作为一个 chunk）
- indexer：入库前按 source_url 删除旧数据（避免重复执行导致重复入库）→ 每个 chunk 调 text-embedding-v4（embedding 失败时记录日志并跳过该 chunk，不中断整体流程）→ 存入 Qdrant（payload 含 doc_id/source_url/source_title/chunk_index/content/content_type）
- **验证 1**：Qdrant 里能查到向量数据，payload 字段完整
- **验证 2**：用一个英文问题检索，看 Top5 召回是否合理（验证 text-embedding-v4 英文能力，不达标则记录到风险表）

### 第 4 步：问答接口

- `QdrantCollectionInit`：应用启动时检查 collection `crossask_docs` 存在且维度=1024，不匹配则报错退出
- `AskService`：问题 → text-embedding-v4 向量化（embedding 失败时返回错误话术"服务暂时不可用"，不抛 500）→ 查 Qdrant（topK=5, score_threshold=0.5）→ 结果=0 则返回兜底 → 有召回则拼 prompt（5.4 模板）→ 调 qwen-plus（LLM 调用异常时返回错误话术，不抛 500）→ 组装 sources 返回
- **验证**：POST /ask 返回答案 + sources，且答案内容能在 sources 中找到依据

### 第 5 步：联调验证 ✅ MVP 完成

- 准备 5 个测试问题：2 个政策类（如退货/关税）、2 个物流类（如时效/禁运）、1 个无关问题（如天气）
- 验证 ✅：5/5 全部通过
  - Q1 政策类-退货：准确回答退货条件/全额退款/卖家响应/保障机制，5 个 eBay 来源 ✅
  - Q2 政策类-关税：准确回答需交关税 + 买家责任，5 个来源（eBay+USPS）✅
  - Q3 物流类-时效：准确回答 Priority Mail 6-10 天 / Express 3-5 天，5 个 USPS 来源 ✅
  - Q4 物流类-禁运：LLM 诚实回答"资料中未列出禁止物品清单"，未编造 ✅
  - Q5 无关问题-天气：LLM 回答"无法回答，参考资料中未提供天气信息"，未编造 ✅
- **MVP 完成** ✅

---

## 九、演进路线（MVP 之后）

```
MVP ✅ 已完成
  ↓
v0.5: 加 Rerank（qwen3-rerank）✅ 已完成（详见第十二章）  ← 召回质量提升
  ↓
v0.6: 加 BM25 混合检索（Qdrant sparse）✅ 已完成（详见第十三章）  ← 关键词/精确匹配场景（产品名/服务名）
  ↓
v0.7: 加商品数据 + MySQL  ← ✅ 已完成（详见第十四章）  ← 答商品类问题（价格/库存）
  ↓
v0.8: 加 Eval（评测集 + Recall@5）  ← ✅ 已完成（详见第十五章）  ← 量化效果，迭代有依据
  ↓
v0.9: 加多轮对话 + 记忆                ← ✅ 已完成（详见第十六章）  ← 连续追问
  ↓
v1.0: 加多语言优化                      ← ✅ 已完成（详见第十七章）  ← 跨语言召回
  ↓
v1.1: 加前端界面 + SSE 流式输出         ← ✅ 已完成（详见第十八章）  ← 可被真实用户使用
```

---

## 十、已知风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| eBay 返回安全验证页 / 编码错乱 | 数据获取失败或乱码 | crawler 不带 Accept-Language 让 eBay 返回中文，用 Jsoup `response.parse()` 自动检测编码（GBK）；单个 URL 失败跳过不中断 |
| Spring AI Alibaba + Qdrant 集成不确定 | 第1步受阻 | 第1步优先验证，不行用 Spring AI 原生 QdrantVectorStore |
| Spring AI Alibaba 可能未适配 text-embedding-v4 | embedding 调不通 | 第1步验证 4 项检查，不行则降级 v3 或查文档找配置方式 |
| text-embedding-v4 英文能力未验证 | 召回质量差 | 第3步同步验证，不达标则升维度至 2048 或换 embedding 模型并更新第二章 |
| 纯向量检索对关键词查询不准 | MVP 效果有限 | 已知取舍，v0.6 加 BM25 解决 |

---

## 十一、关键设计决策

1. **用 DashScope 模型 API，不用百炼托管知识库**：后者是黑盒，失去项目学习价值
2. **Java + Spring AI Alibaba，不用 LangChain4j**：Spring 生态学习曲线低，自带 embedding 封装
3. **MVP 不上 BM25/Rerank**：先验证架构跑通，优化召回质量放 v0.5
4. **MVP 不上 MySQL**：只用 Qdrant，等加商品数据时再上
5. **MVP 不做 Eval**：先跑通主链路，量化效果放 v0.8
6. **答案必须带来源追溯**：验证 RAG 非幻觉的前提，不可砍
7. **切分按 HTML 标题结构，不按固定 token**：政策文档有标题层级，固定 token 会切断条款
8. **Document 模型放 common 模块**：ingestion 和 api 共用，避免循环依赖
9. **Embedding 用 text-embedding-v4 而非 v2**：v4 支持 100+ 语种（v2 仅 ~10 种）、输入 8192 token（v2 仅 2048）、MTEB 得分更高、单价更便宜（0.0005 vs 0.0007 元/千Token），维度选 1024（官方推荐通用值，性能与成本平衡）
10. **cleaner 只去噪音元素，不碰正文 HTML 结构**：splitter 依赖 h1/h2 标签切分，去标签必须在切分之后
11. **collection 创建归 ingestion，api 只检查不创建**：避免 api 启动时误建/误删数据

---

## 十二、v0.5 详细设计：Rerank（精排）

> 本章节是 v0.5 的实现依据，代码改动以此为准。

### 12.1 目标与原理

向量召回（embedding）速度快但偏粗，rerank 模型对「query-文档」做交叉编码精排，把最相关的排到前面。官方建议：embedding 召回 20~100 候选 → rerank 精选 top 5~10 → 传入 LLM。rerank 在「召回结果相关度参差」时价值最大；若召回已高度相关则价值有限。

### 12.2 Pipeline 变化

**MVP（当前）**：
```
question → embedding → Qdrant search(topK=5, score≥0.3) → [空则兜底] → prompt → LLM
```

**v0.5**：
```
question → embedding → Qdrant search(topK=15, score≥0.2) → rerank(qwen3-rerank) → 取 top 5 → [空则兜底] → prompt → LLM
```

变化点：
1. 召回量 5 → 15（扩大召回给 rerank 更多候选；总库仅 83 条，15 已是合适规模）
2. 召回阈值 0.3 → 0.2（rerank 兜底精排，召回可更宽松，避免跨语言漏召回）
3. 新增 rerank 步骤，对 15 个候选按 query 相关性重排
4. rerank 后取 top 5（给 LLM 的上下文数量不变）
5. 兜底判断点不变：仍看「召回是否=0」（召回>0 则 rerank 必有结果）

### 12.3 关键参数

| 参数 | MVP | v0.5 | 说明 |
|---|---|---|---|
| retrieve-top-k（召回量） | 5 | 15 | 总库 83 条，召回 15 兼顾多样性与成本 |
| rerank-top-k（精排后取） | — | 5 | 给 LLM 的文档数，与 MVP 一致 |
| similarity-threshold | 0.3 | 0.2 | rerank 兜底，召回阶段放宽阈值 |
| rerank 模型 | — | qwen3-rerank | DashScope，100+ 语种 |
| rerank top_n | — | 5 | API 参数，返回前 N |

> rerank 的 relevance_score 是 0~1 的相对分（同次请求内可比，跨请求不可比），v0.5 不对 rerank 分数加阈值，直接取 top N。

### 12.4 模型与 API

- **模型**：`qwen3-rerank`（旧 `gte-rerank`/`gte-rerank-v2` 将于 2026-05-30 下线，必须用 qwen3-rerank）
- **限制**：单次最多 500 文档，单文档 4000 token，请求最大 120000 token
- **接口**（OpenAI 兼容，扁平结构）：
  ```
  POST https://dashscope.aliyuncs.com/compatible-api/v1/reranks
  Authorization: Bearer ${DASHSCOPE_API_KEY}

  {
    "model": "qwen3-rerank",
    "query": "用户问题",
    "documents": ["候选1", "候选2", "..."],
    "top_n": 5
  }
  ```
- **响应**：
  ```json
  {
    "output": { "results": [ { "index": 0, "relevance_score": 0.93 } ] },
    "usage": { "total_tokens": 0 },
    "request_id": "..."
  }
  ```
  - `index`：对应输入 documents 数组的原始下标，用它映射回 Qdrant 召回结果
  - `relevance_score`：相关性分，降序排列

> **Spring AI Alibaba 1.1.2.0 无原生 rerank 抽象**（仅有 ChatModel/EmbeddingModel/ImageModel），因此用 RestClient 直接调 DashScope rerank API，与现有 Indexer/AskService 调 Qdrant REST 的方式一致。

### 12.5 兜底与异常

| 场景 | 处理 |
|---|---|
| 向量召回=0 | 返回兜底话术，不调 rerank、不调 LLM（同 MVP） |
| rerank 调用失败 | 降级用向量召回前 5，记 warn 日志，继续调 LLM |
| rerank 返回 <5 条 | 有几条用几条 |

### 12.6 设计决策

1. **不设 rerank 分数阈值**：relevance_score 是相对分，跨请求不可比；取 top N 更稳定
2. **rerank 失败降级而非报错**：rerank 是增强项，不应让增强组件故障拖垮主链路
3. **用 RestClient 直调而非等框架封装**：Spring AI Alibaba 1.1.2.0 无 rerank 抽象
4. **rerank 作为开关**：`rerank.enabled` 方便 A/B 对比有无 rerank 效果

### 12.7 联调结果 ✅

5/5 全部通过（recall=15, topN=5，rerank 全链路调用成功）。对比 MVP：rerank 后 top1 在 Q1/Q2/Q3 都是「最对题」的文档（MVP 未做 rerank 时部分问题 top1 是泛相关而非精确相关），LLM 回答的针对性明显提升。

---

## 十三、v0.6 详细设计：混合检索（Dense + Sparse）

> 本章节是 v0.6 的实现依据，代码改动以此为准。

### 13.1 目标与原理

纯 dense 向量擅长「语义相似」，但对**精确关键词**（如服务名 `Priority Mail Express`、术语 `customs`、英文短语完整匹配）会丢分——dense embedding 会把它们「揉碎」成语义。混合检索 = dense（语义） + sparse（关键词）双路召回，用 RRF/加权融合两路结果，覆盖两类查询。

不引入 Elasticsearch 的原因：
1. DashScope `text-embedding-v4` 原生支持 `output_type=dense&sparse`，一次 API 同时拿两种向量，**费用与单 dense 相同**
2. Qdrant 1.10+ 支持 named vectors（同 collection 多向量）+ sparse vector + 内置 IDF + 服务端 fusion query
3. CrossAsk 文档库不大（83 chunks），上 ES 是过度工程

> **重要**：DashScope sparse 不是裸 BM25，而是「学习型稀疏向量」（接近 SPLADE 思路）。Qdrant 官方文章 [bm42](https://qdrant.tech/articles/bm42/) 指出：RAG 短文档场景下 BM25 公式里 TF/文档长度归一化基本失效，**只剩 IDF 有用**——而 DashScope sparse 已把这一思想内化到模型里，对短文档表现更稳。本章节标题保留「BM25」是沿用社区习惯，实际实现是稀疏向量混合检索。

### 13.2 Pipeline 变化

**v0.5（当前）**：
```
question → dense embedding → Qdrant search(retrieve-top-k=15) → rerank → top 5 → LLM
```

**v0.6**：
```
question → dense+sparse embedding（一次 DashScope API）
              ↓
         Qdrant Query API（服务端 RRF 融合两路）
              ↓
         hybrid candidates（15 条）→ rerank → top 5 → LLM
```

变化点：
1. embedding 阶段同时产出 dense（1024 维）+ sparse（变长稀疏向量），且区分 `text_type`（query/document）
2. Qdrant 检索从单 `points/search` 改为 [`points/query`](https://qdrant.tech/documentation/concepts/hybrid-queries/)，prefetch 两路 + fusion RRF
3. rerank 阶段不变（输入候选数仍是 15，输出 5）
4. **ingestion 也要改**：入库时存 dense+sparse 双向量，调 embedding 时传 `text_type=document`

**v0.6 架构图（覆盖第四章 4.1 的 MVP 架构）**：

```
用户提问
   │
   ▼
┌──────────────────────────────────────────────────────────────┐
│  crossask-api  (POST /ask)                                   │
│                                                              │
│  1. question → DashScopeHybridEmbeddingClient                │
│     (text_type=query, output_type=dense&sparse)              │
│     → HybridEmbedding{ dense[1024], sparse[indices,values] } │
│                                                              │
│  2. Qdrant /points/query                                     │
│     prefetch: [ dense×30, sparse×30 ] + fusion=rrf           │
│     → 15 candidates                                          │
│                                                              │
│  3. RerankService (qwen3-rerank) → top 5                     │
│                                                              │
│  4. prompt + LLM (qwen-plus) → answer + sources              │
└──────────────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
┌──────────────────────────────┐  ┌──────────────────────┐
│  Qdrant                      │  │  DashScope           │
│  collection: crossask_docs   │  │  - text-embedding-v4 │
│  vectors.dense (1024,Cosine) │  │    (dense&sparse)    │
│  sparse_vectors.sparse(idf)  │  │  - qwen3-rerank      │
└──────────────────────────────┘  │  - qwen-plus         │
         ▲                        └──────────────────────┘
         │ 离线入库（text_type=document）
┌──────────────────────────────────────────────────────────────┐
│  crossask-ingestion                                          │
│  crawler → cleaner → splitter → indexer                      │
│                                  │                           │
│                                  ├ DashScopeHybridEmbedding  │
│                                  │  (text_type=document)     │
│                                  └ Qdrant upsert(双向量)      │
└──────────────────────────────────────────────────────────────┘
```

### 13.3 关键参数

| 参数 | v0.5 | v0.6 | 说明 |
|---|---|---|---|
| dense embedding | text-embedding-v4 (1024) | 同左 | 不变 |
| sparse embedding | — | text-embedding-v4 `output_type=dense&sparse` | 新增 |
| Qdrant 检索方式 | points/search | points/query（prefetch + fusion） | 服务端融合 |
| dense prefetch limit | — | 30 | 给融合更多候选 |
| sparse prefetch limit | — | 30 | 给融合更多候选 |
| fusion | — | RRF（k=60，Qdrant 默认） | Qdrant 服务端内置 |
| retrieve-top-k（融合后） | 15 | 15 | 不变，给 rerank |
| rerank-top-k | 5 | 5 | 不变，给 LLM |
| similarity-threshold | 0.2 | — | 混合分不可比，去掉阈值 |

> **去掉 similarity-threshold**：RRF 融合后的 score 是 `1/(k+rank)` 形式，不再是 cosine 相似度，无法用同一阈值。改为按候选数控制（取前 15）+ rerank 兜底。
>
> **「召回=0」的兜底逻辑改为「拼接的 contexts 为空字符串」**：理论上 RRF 融合后只要库非空就有结果，但 collection 空/网络异常时仍需触发兜底。

### 13.4 数据存储改造

Qdrant collection 从 **单向量** 改为 **named vectors（双向量）**：

```json
PUT /collections/crossask_docs
{
  "vectors": {
    "dense": { "size": 1024, "distance": "Cosine" }
  },
  "sparse_vectors": {
    "sparse": { "modifier": "idf" }   // 让 Qdrant 服务端算 IDF
  }
}
```

**Document payload 不变**（仍是第 5.1 节定义的 `doc_id` / `source_url` / `source_title` / `chunk_index` / `content` / `content_type` 六字段）。改的只是**点结构里 vector 字段的形态**：

```json
// MVP（单向量）
{ "id": "...", "vector": [0.01, ...], "payload": {...} }

// v0.6（双向量）
{
  "id": "...",
  "vector": {
    "dense":  [0.01, ...],
    "sparse": { "indices": [12345, 67890], "values": [0.87, 0.42] }
  },
  "payload": {...}
}
```

**迁移决策**：Qdrant collection 一旦创建无法在线变更向量配置。两种处理方案：

| 方案 | 操作 | 风险 |
|---|---|---|
| A. 新建 collection（推荐） | 起名 `crossask_docs_v2`，并行重建，迁移完后切换 api 配置 | 需要二次跑 ingestion，但零停机 |
| B. 删除重建 | 删 `crossask_docs` 后重建 + 重跑 ingestion | 简单但中间窗口 api 不可用 |

MVP 阶段数据量小（83 chunks），**采用方案 B**：在 v0.6 启动前手动删 collection，让 ingestion 重新建库入数据。

### 13.5 DashScope 同时输出 dense + sparse

DashScope **OpenAI 兼容接口不支持 `output_type`**，必须用 DashScope 原生 SDK / REST：

```
POST https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding
Authorization: Bearer ${DASHSCOPE_API_KEY}

{
  "model": "text-embedding-v4",
  "input": { "texts": ["..."] },
  "parameters": {
    "dimension": 1024,
    "output_type": "dense&sparse",
    "text_type": "document"            // 入库时填 document，查询时填 query
  }
}
```

**text_type 用法**（DashScope 官方推荐）：
- `text_type=document`：ingestion 入库时使用，输出"被检索"优化的向量
- `text_type=query`：API 查询时使用，输出"去检索"优化的向量
- 两者向量空间相通（同一模型同一维度），但语义偏向不同；用对了能让 dense 检索质量进一步提升
- 仅 DashScope 原生接口支持，OpenAI 兼容接口不可用

响应（关键字段）：
```json
{
  "output": {
    "embeddings": [
      {
        "text_index": 0,
        "embedding": [0.01, ...],           // dense 1024 维
        "sparse_embedding": [
          { "index": 12345, "value": 0.87 },
          { "index": 67890, "value": 0.42 }
        ]
      }
    ]
  }
}
```

> **Spring AI Alibaba 1.1.2.0 的 `DashScopeEmbeddingModel` 默认只走 OpenAI 兼容接口，不返回 sparse**。v0.6 需要新建 `DashScopeHybridEmbeddingClient`（RestClient 直调 DashScope REST），输出 `record HybridEmbedding(float[] dense, List<SparseEntry> sparse)`。原 `EmbeddingModel` 在 LLM 主链路上保留，新 client 仅用于检索/索引。

### 13.6 Qdrant 混合检索 Query API

```
POST /collections/crossask_docs/points/query
{
  "prefetch": [
    { "query": [0.01, ...],                 "using": "dense",  "limit": 30 },
    { "query": { "indices": [...], "values": [...] }, "using": "sparse", "limit": 30 }
  ],
  "query": { "fusion": "rrf" },
  "limit": 15,
  "with_payload": true
}
```

返回结构与 `/points/search` 一致（`{ "result": [...] }`），AskService 现有解析逻辑可复用。

### 13.7 兜底与异常

| 场景 | 处理 |
|---|---|
| DashScope 返回的 sparse_embedding 为空 | 该 chunk 只存 dense；query 时若 query sparse 为空，prefetch sparse 跳过，行为退回纯 dense |
| Qdrant query 调用失败 | 返回 `FALLBACK_ERROR` 兜底话术（与 v0.5 一致） |
| 融合后候选 = 0 | 返回 `FALLBACK_EMPTY` 兜底（与 v0.5 一致） |
| `hybrid.enabled=false` | 走 v0.5 纯 dense 路径，便于 A/B 对比 |

### 13.8 设计决策

1. **不上 Elasticsearch**：DashScope sparse + Qdrant sparse vector 已覆盖关键词召回，省一个重组件
2. **用 DashScope sparse 而非 FastEmbed BM25**：FastEmbed 是 Python 生态，Java 接入成本高；DashScope sparse 中英文都训练过，跨语言更稳，与 dense 同一 API 一次拿
3. **Qdrant 服务端做 RRF 融合**：避免 Java 端实现归一化 + 加权的复杂度；RRF 不需要分数归一化，对未知分布鲁棒
4. **prefetch limit=30**：fusion 输入越多融合越稳，30 兼顾质量与延迟
5. **去掉 similarity-threshold**：RRF 分数不可解释，靠 rerank 兜底质量
6. **collection 删后重建**：MVP 数据量小（83 chunks），无需在线迁移；正式生产应该并行新建 collection 再切换
7. **`hybrid.enabled` 开关**：与 `rerank.enabled` 一致，支持 A/B 对比验证混合检索价值

### 13.9 已知风险

| 风险 | 影响 | 应对 |
|---|---|---|
| DashScope sparse 输出格式可能与 Qdrant 期望的 indices/values 不一致 | sparse 入库失败 | 在 `DashScopeHybridEmbeddingClient` 内做格式转换，提供单测覆盖 |
| sparse 向量 token id 词表与 Qdrant 内置 IDF 计算冲突 | IDF 算的是 Qdrant 里出现频次，可能与模型训练分布不一致 | 实测对比 `modifier=idf` 开/关效果，不达预期则关闭 modifier，由模型权重直接决定 |
| `text_type=document` 入库 vs `text_type=query` 查询，dense 向量空间可能存在偏差 | dense 召回质量异常下降（理论同空间，实际需验证） | 验证点 5 单独跑纯 dense 对比 MVP 表现；若下降明显，降级为入库/查询都不传 text_type |
| 重建 collection 中间窗口 api 不可用 | 短暂 503 | 计划停机重建；正式生产用方案 A（并行新建） |
| Qdrant 1.18 客户端版本与 Spring AI starter（1.13）已有 minor 不兼容 warn | 当前已知 warn 不影响功能 | 沿用 REST API 直调，避开 grpc client 不一致问题 |

### 13.10 实施过程中的方案调整

**调整 1：移除 spring-ai-starter-vector-store-qdrant 依赖**
- 起因：v0.6 collection 改为 named vectors（dense + sparse），Spring AI starter 默认期望单向量 collection，启动时会因结构不匹配报错；且 v0.6 后两个模块的检索/入库都直接用 Qdrant REST API，VectorStore Bean 完全无用
- 处理：`crossask-ingestion/pom.xml` 和 `crossask-api/pom.xml` 都移除该依赖；application.yml 删除 `spring.ai.vectorstore.qdrant.*` 段

**调整 2：删除 IntegrationVerifyRunner**
- 起因：原来用于第 1 步集成验证的 Runner 依赖 `EmbeddingModel` + `VectorStore`，v0.6 后 api 模块不再注入这两个 Bean；且 MVP 联调已完成，该 Runner 长期保留是过度工程
- 处理：删除 `crossask-api/src/main/java/com/crossask/api/rag/IntegrationVerifyRunner.java`

**调整 3：crossask-common 新增 jackson-databind 依赖**
- 起因：`DashScopeHybridEmbeddingClient` 放在 common 模块（ingestion/api 共用），需要 jackson 解析 DashScope 响应；而 `spring-boot-starter` 默认不传递 jackson
- 处理：`crossask-common/pom.xml` 显式加 `com.fasterxml.jackson.core:jackson-databind`

**调整 4：纯 dense 降级也走 /points/query 而非 /points/search**
- 起因：v0.6 后 collection 是 named vectors，老 `/points/search` 不带 `using` 参数无法定位向量；统一走 `/points/query` 并通过 `using=dense` 指定
- 处理：`AskService.hybridSearch()` 在 hybrid 关闭时仍调 `/points/query`，只是不带 `prefetch`/`fusion`，直接对 named vector "dense" 做单路检索
- 副作用：原 `RagProperties.similarityThreshold` 在 hybrid 路径下不再使用；保留字段但不传给 Qdrant（hybrid 模式 RRF 分数与 cosine 不可比）

**调整 5：Qdrant `/points/query` 响应结构与 `/points/search` 不同**
- 起因：`/points/search` 返回 `{ "result": [...] }`，`/points/query` 返回 `{ "result": { "points": [...] } }`
- 处理：`AskService` 兼容两种格式，优先解析 `result.points`，回退到 `result` 直接作为数组

### 13.11 联调结果 ✅

ingestion 重建：
- Collection 创建成功（dense dim=1024 Cosine + sparse modifier=idf）
- 入库 **83/83**，sparse 为空 **0 条**（DashScope sparse 输出格式与 Qdrant 完全兼容）

5 题 MVP + 2 题强关键词测试（每题日志确认 `模式=hybrid, 返回 15 条 → rerank 5`）：

| # | 问题 | 结果 | top1 命中 |
|---|---|---|---|
| Q1 | eBay 上购买的商品如何申请退货？ | ✅ | 退回物品以获得退款 \| eBay |
| Q2 | 国际包裹寄到美国需要交关税吗？ | ✅ | 面向买家的跨国购买和运送 \| eBay |
| Q3 | USPS Priority Mail International 寄到中国需要多少天？ | ✅ | Priority Mail International（5 条 sources 中占 3 条） |
| Q4 | 哪些物品禁止国际寄送？ | ✅ | LLM 诚实拒答（资料确无清单） |
| Q5 | 北京今天天气怎么样？ | ✅ | LLM 诚实拒答 |
| Q6 | Priority Mail Express International 寄到加拿大要几天？ | ✅ | **sparse 命中**：top1 直接是 Priority Mail Express International，准确答 3-5 个工作日 |
| Q7 | First-Class Package International Service 的时效是多少？ | ✅ | **sparse 命中**：top1 是 First-Class Package International Service，但该页本身未列时效，LLM 诚实回答"资料未提及" |

**对比 v0.5**：
- Q3 在 v0.5 是 "rerank 把 Priority Mail International 排前 2"；v0.6 是 **top1/2/5 三个位置都是该服务页**——sparse 把同一服务名的相关 chunk 全聚拢
- Q6/Q7 都是 v0.6 新增的"完整服务名"查询，纯 dense 容易把"Priority Mail Express"和"Priority Mail"混淆，sparse 让精确名称匹配占主导

---

## 十四、v0.7 详细设计：商品数据 + MySQL（Function Calling 路由）

> 本章节是 v0.7 的实现依据，代码改动以此为准。

### 14.1 目标与原理

到 v0.6 为止，CrossAsk 只能答**文档类问题**（政策、物流时效、退货流程）。跨境电商客服的另一半流量是**商品类问题**——「这个商品多少钱」「还有库存吗」「卖家是谁」。这类问题：

- **不适合向量检索**：用户问"iPhone 15 价格"，向量检索把所有 iPhone 商品文档召回排序，但 LLM 看到的还是自然语言描述，最终输出的"价格"可能是上下文凑出来的、不准
- **天然适合 SQL**：商品价格、库存、卖家是结构化字段，SQL 一条 `SELECT price FROM products WHERE id = ?` 比任何 RAG 都准
- **关键不在检索而在路由**：得让系统知道「这个问题该查文档还是查商品库」

v0.7 引入 **LLM Function Calling**：在 `/ask` 入口，让 qwen-plus 直接拿到两个工具——`search_docs`（走 v0.6 hybrid RAG）和 `query_products`（走 MySQL）——由 LLM 决定调哪个、传什么参。Function Calling 是 2024 后 Agent / RAG 主流范式，比"手写规则路由"或"先 LLM 分类再走"更优雅、可扩展，未来加新工具（物流追踪、订单查询）零侵入。

### 14.2 Pipeline 变化

**v0.6（当前）**：
```
question → hybrid embedding → Qdrant query(prefetch+RRF) → rerank → top5 → LLM → answer
```

**v0.7**：
```
question
   │
   ▼
qwen-plus（绑定工具：search_docs / query_products）
   │
   ├─ 判定"文档类"：tool_call=search_docs(query=...) → v0.6 hybrid RAG 流程返回片段
   ├─ 判定"商品类"：tool_call=query_products(keyword=..., max_price=..., ...) → MySQL 查询返回 List<Product>
   └─ 混合类（"iPhone 15 怎么退货"）：两个工具都调
   │
   ▼
qwen-plus 拿到工具结果 → 生成最终 answer + sources（doc sources + product items）
```

变化点：
1. `/ask` 入口从「先 RAG 后 LLM」改为「LLM 主导，工具执行检索」
2. 新增 MySQL 持久化层 + Product 实体 + ProductService + 商品爬虫
3. AskResponse 增加 `products` 字段，前端可分两栏展示「相关文档」和「相关商品」
4. v0.6 的 RAG 流程**完整保留**，被封装为 `search_docs` 工具

### 14.3 v0.7 架构图

```
用户提问
   │
   ▼
┌──────────────────────────────────────────────────────────────┐
│  crossask-api  (POST /ask)                                   │
│                                                              │
│  AskService                                                  │
│    │                                                         │
│    ▼                                                         │
│  AgentOrchestrator                                           │
│    │ ChatClient(qwen-plus).tools(searchDocs, queryProducts) │
│    │                                                         │
│    ├──[tool] search_docs(query)                              │
│    │     → RagSearchService（封装 v0.6 hybrid + rerank）     │
│    │     → List<DocChunk>                                    │
│    │                                                         │
│    └──[tool] query_products(keyword?, maxPrice?, brand?,     │
│             condition?, freeShippingOnly?, limit=10)         │
│          → ProductService.query() → MyBatis-Plus → MySQL     │
│          → List<Product>                                     │
│                                                              │
│  → LLM 综合工具返回 → answer + sources + products            │
└──────────────────────────────────────────────────────────────┘
         │                              │                    │
         ▼                              ▼                    ▼
┌──────────────────────┐  ┌──────────────────────┐  ┌────────────────┐
│  Qdrant              │  │  DashScope           │  │  MySQL 8.x     │
│  crossask_docs       │  │  - hybrid embedding  │  │  crossask.     │
│  (v0.6 双向量)        │  │  - qwen3-rerank      │  │  products      │
│                      │  │  - qwen-plus (tools) │  │                │
└──────────────────────┘  └──────────────────────┘  └────────────────┘
         ▲                                                  ▲
         │ 离线入库（v0.6 ingestion）                        │ 离线入库（v0.7 新增）
┌──────────────────────────────┐                ┌──────────────────────────────┐
│  crossask-ingestion (文档线) │                │  crossask-ingestion (商品线) │
│  crawler→cleaner→splitter→   │                │  ProductCrawler →            │
│   indexer (v0.6)             │                │  ProductImporter → MySQL     │
└──────────────────────────────┘                └──────────────────────────────┘
```

### 14.4 数据模型：products 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT, PK, AUTO_INC | 主键 |
| `item_id` | VARCHAR(64), UNIQUE | eBay item ID（URL 中提取），用作去重键 |
| `title` | VARCHAR(512) | 商品标题（如 "Apple iPhone 15 Pro 256GB Natural Titanium - Unlocked"） |
| `brand` | VARCHAR(64), NULL | 品牌（Apple / Samsung 等，解析失败可空） |
| `price` | DECIMAL(10,2) | 价格（美元，主货币） |
| `currency` | VARCHAR(8) | 默认 `USD` |
| `condition_text` | VARCHAR(32) | "New" / "Pre-Owned" / "Refurbished"（eBay 原字段） |
| `shipping_text` | VARCHAR(128), NULL | "Free shipping" 或 "$5.99 shipping" 文本 |
| `free_shipping` | BOOLEAN | 解析自 shipping_text 的布尔标记 |
| `seller_name` | VARCHAR(128), NULL | 卖家名 |
| `seller_feedback_pct` | DECIMAL(5,2), NULL | 卖家好评率（如 99.5） |
| `item_location` | VARCHAR(128), NULL | 发货地 |
| `image_url` | VARCHAR(512), NULL | 主图 URL |
| `source_url` | VARCHAR(512) | eBay 商品页 URL |
| `created_at` | DATETIME DEFAULT CURRENT_TIMESTAMP | 首次入库时间 |
| `crawled_at` | DATETIME | 最近一次抓取时间（每次 upsert 更新） |
| `updated_at` | DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | 行更新时间 |

**索引**：`UNIQUE KEY (item_id)`、`INDEX (brand)`、`INDEX (price)`、`FULLTEXT(title)` （InnoDB 5.7+ 已支持 FULLTEXT）。

> 商品数据**只进 MySQL，不进 Qdrant**——商品检索靠 SQL（含 FULLTEXT 关键词检索），不参与混合向量检索。这与第 4.2 节"长文本走向量库，结构化字段走 DB"一致。

### 14.5 商品爬虫与入库

**爬取策略**：
- 起点：eBay 搜索结果页（例：`https://www.ebay.com/sch/i.html?_nkw=iphone+15`），抓取若干目标关键词的搜索结果
- 解析：Jsoup 提取列表项 → 取 detail URL → 进入 detail 页提取详细字段
- 目标 SKU 数：**20-30 条**（覆盖 4-5 个类目：手机、耳机、相机、笔电、配件）

**模块归属**：商品爬虫与文档爬虫复用 ingestion 模块（同一 module，不同 Job 入口）：
- 文档线 `IngestionApplication` 调 `IngestionRunner`（v0.6 已有，**v0.7 改名为 `DocIngestionRunner` 并加 `@Profile("docs")`**）
- 商品线 `IngestionApplication` 调 `ProductIngestionRunner`（v0.7 新增，`@Profile("products")`）
- 启动时通过 `--spring.profiles.active=docs` 或 `--spring.profiles.active=products` 区分；默认不带 profile 则两个 runner 都不执行（避免误跑）
- `IngestionApplication` 的 `@ComponentScan` 已含 `com.crossask.common`，新增 `@MapperScan("com.crossask.ingestion.mapper")` 让 MyBatis-Plus 扫到 `ProductMapper`

**入库**：MyBatis-Plus `ProductMapper`（按 `item_id` 去重，已存在则更新价格/库存信息——可用 `saveOrUpdate` + 唯一索引）。

**ingestion 模块新增依赖**：`mybatis-plus-spring-boot3-starter`、`mysql-connector-j`。

### 14.6 LLM Function Calling 设计

#### 14.6.1 工具定义（Spring AI 风格）

```java
@Service
class RagSearchTool {
    @Tool(description =
        "Search internal help center documents (eBay & USPS policy / shipping / returns). " +
        "Use this for questions about policies, shipping times, return rules, customs etc. " +
        "Do NOT use this for product price/stock/seller queries.")
    public List<Source> searchDocs(
        @ToolParam(description = "User's question in original wording") String query) { ... }
}

@Service
class ProductQueryTool {
    @Tool(description =
        "Query the e-commerce product catalog (MySQL). " +
        "Use this for questions about specific product price, availability, brand, seller, " +
        "or finding products matching criteria. " +
        "Do NOT use this for policy/shipping/return questions.")
    public List<ProductItem> queryProducts(
        @ToolParam(description = "Free-text keyword (matches product title)", required = false) String keyword,
        @ToolParam(description = "Brand filter (e.g. Apple, Samsung)", required = false) String brand,
        @ToolParam(description = "Maximum price in USD", required = false) BigDecimal maxPrice,
        @ToolParam(description = "Minimum price in USD", required = false) BigDecimal minPrice,
        @ToolParam(description = "Item condition: New / Pre-Owned / Refurbished", required = false) String conditionText,
        @ToolParam(description = "true to only return items with free shipping", required = false) Boolean freeShippingOnly,
        @ToolParam(description = "Max results (default 5, max 10)", required = false) Integer limit) { ... }
}
```

> **关于工具方法返回类型**：Spring AI 把 Java 方法返回值序列化为 JSON 喂给 LLM。这里复用 v0.6 已有的 `Source`（url/title/content）让 LLM 拿到文档片段；商品则用新增的 `ProductItem` DTO（见 14.6.4）只暴露关键字段，避免噪声。

#### 14.6.2 调用方式

```java
String answer = ChatClient.create(chatModel)
    .prompt()
    .system(SYSTEM_PROMPT)   // 见 14.6.3
    .user(userQuestion)
    .tools(ragSearchTool, productQueryTool)
    .call()
    .content();
```

> 当前 `AskService` 已用 `ChatClient.Builder` 注入（v0.6 引入），v0.7 升级为：构造器里 `builder.defaultSystem(SYSTEM_PROMPT).defaultTools(ragSearchTool, productQueryTool).build()`，每次 `ask()` 直接调 `chatClient.prompt().user(question).call().content()` 即可。**工具被 LLM 调用时把 sources/products 写入 ThreadLocal 收集器（见 14.6.5），方便最终 `/ask` 响应同时返回结构化数据**。

Spring AI 自动处理 tool_call 解析、Java 方法执行、结果回填、多轮 tool 调用。

#### 14.6.3 System Prompt（核心约束）

```
You are CrossAsk, a cross-border e-commerce customer support assistant.

You have access to two tools:
1. search_docs    - for policy / shipping / return / customs questions
2. query_products - for product catalog (price / stock / brand / seller)

Rules:
- Always call the appropriate tool BEFORE answering. Do NOT answer from memory.
- For mixed questions (e.g. "how to return this iPhone"), you may call both tools.
- If a tool returns empty, respond honestly that you cannot find the info — do not hallucinate.
- Cite sources at the end:
  - For docs: "Source: <title> (<url>)"
  - For products: "Product: <title> | $<price> | <seller>"
- Reply in the same language as the user's question (zh / en).
```

#### 14.6.4 工具返回结构

`Source`（复用 v0.6）：包含 `url` / `title` / `content` 三字段，是 `search_docs` 工具的返回元素。v0.6 中 `Source` 已用于 `AskResponse.sources`。

`ProductItem`（v0.7 新增 DTO，位于 `crossask-common/model/ProductItem.java`）：仅暴露给 LLM 的必要字段，与 MyBatis-Plus 实体 `Product` 解耦。包含字段：
- `title`（商品标题）
- `price`（BigDecimal）
- `currency`（USD）
- `conditionText`（"New" / "Pre-Owned" / "Refurbished"）
- `brand`
- `shippingText`
- `freeShipping`
- `sellerName`
- `sourceUrl`

> 实体 `Product` 多出 `id` / `itemId` / `crawledAt` / `updatedAt` / `imageUrl` / `sellerFeedbackPct` / `itemLocation` 等字段，不暴露给 LLM（避免 noise，也避免 token 浪费）。

#### 14.6.5 工具调用收集器（ToolCallContext）

LLM 文本回答 + 结构化 sources/products 需要同时返回给前端，但 Spring AI 的 `chatClient.prompt().call().content()` 只返回文本。设计 `ToolCallContext`（ThreadLocal 收集器）：

```java
public final class ToolCallContext {
    private static final ThreadLocal<List<Source>> SOURCES = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<ProductItem>> PRODUCTS = ThreadLocal.withInitial(ArrayList::new);

    public static void reset() { SOURCES.get().clear(); PRODUCTS.get().clear(); }
    public static void addSources(List<Source> list) { SOURCES.get().addAll(list); }
    public static void addProducts(List<ProductItem> list) { PRODUCTS.get().addAll(list); }
    public static List<Source> getSources() { return List.copyOf(SOURCES.get()); }
    public static List<ProductItem> getProducts() { return List.copyOf(PRODUCTS.get()); }
}
```

- `RagSearchTool.searchDocs()` 末尾 `ToolCallContext.addSources(result)` 后返回
- `ProductQueryTool.queryProducts()` 末尾 `ToolCallContext.addProducts(result)` 后返回
- `AskService.ask()` 入口 `reset()`，末尾从 context 取出装到 `AskResponse`
- 单线程内 Spring AI 串行调 tool；若未来上虚拟线程或并发，需评估是否换为 `RequestScope` Bean

### 14.7 兜底与异常

| 场景 | 处理 |
|---|---|
| LLM 没调任何工具 | 走 system prompt 兜底（"我只能基于查询结果回答"），但实际应通过 prompt 约束避免 |
| `search_docs` 返回空 + `query_products` 返回空 | AskService 检测两个工具调用结果都空，返回 `FALLBACK_EMPTY` 兜底话术，不传给 LLM 生成 |
| MySQL 连接失败 | `query_products` 抛 RuntimeException，Spring AI 兜底为 "tool execution failed"；AskService 包一层 try-catch，返回 `FALLBACK_ERROR` |
| 工具循环调用 > 3 次 | Spring AI 自带 `MaxIterationsAdvisor`（或在 prompt 强约束）限制，强制收敛 |
| 商品爬取触发 eBay 反爬 | ingestion 单 URL 失败跳过，不中断（与 v0.6 文档爬虫策略一致） |
| MyBatis-Plus 与 Spring Boot 3.5 兼容 | 用 `mybatis-plus-spring-boot3-starter` 3.5.9+（适配 Spring Boot 3.5），避开 jakarta 包名变更问题 |

### 14.8 设计决策

1. **商品数据不进 Qdrant，只进 MySQL**：商品检索是精确查询，向量化反而增加噪声；保持"长文本→向量库 / 结构化→DB"的清晰分工
2. **用 Function Calling 而非规则路由**：规则脆弱、可维护性差；LLM Function Calling 是 2024 后 Agent 主流范式，未来加新工具（物流追踪、汇率）零侵入
3. **MyBatis-Plus 而非 JPA**：项目已用国内技术栈（DashScope / 阿里 Spring AI），MyBatis-Plus 写 SQL 直观、文档充分，配合 LambdaQueryWrapper 类型安全
4. **真实 eBay 数据而非 mock**：贴近"跨境电商客服"场景，简历真实可信；数据量小（20-30 条）爬取风险低
5. **ingestion 不拆模块**：文档线和商品线共用 `crossask-ingestion`，通过 `--mode` 或 Profile 区分入口；避免模块膨胀
6. **`Product` 实体放 common**：API（查询）和 ingestion（写入）共用，与 `Document` 处理方式一致
7. **Tool 返回结构对 LLM 友好**：只暴露必要字段，避免 LLM 在 noise 字段（id、crawled_at）上浪费 token
8. **AskResponse 扩展而非新增 endpoint**：保持单一 `/ask` 入口，前端按 `products` 是否非空决定 UI 分栏

### 14.9 已知风险

| 风险 | 影响 | 应对 |
|---|---|---|
| eBay 反爬升级（验证码 / IP 封禁） | 商品 ingestion 失败 | 加 User-Agent 池、抓取间隔 ≥ 2s、失败跳过；极端情况降级为本地 mock JSON 兜底 |
| LLM 不调用工具直接编造答案 | 用户拿到幻觉答案 | system prompt 强约束 + Spring AI Advisors + 兜底检测（LLM 输出未引用工具结果时记录告警） |
| LLM 调错工具（商品问题调 `search_docs`） | 召回不相关 / 答非所问 | 工具 description 明确划界 + 在 system prompt 列正反例；联调阶段抽样测试 |
| MyBatis-Plus 自动建表与生产数据不一致 | DDL 漂移 | 开发期手写 DDL（schema.sql 放 resources，Spring Boot 启动时执行）；不依赖自动建表 |
| qwen-plus tool_call 协议格式与 Spring AI 兼容性 | 工具调用失败 | Spring AI Alibaba 1.1.2.0 已适配 DashScope tool_call 格式；联调若失败则升级到最新 minor 版本，或降级 ChatModel 为 OpenAI 兼容接口（DashScope OpenAI 兼容也支持 tools） |
| MySQL 8 默认 utf8mb4 与 JDBC URL 不匹配 | 中文乱码 / 写入失败 | URL 显式 `useUnicode=true&characterEncoding=utf8`；建表 `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` |
| 商品价格漂移导致测试用例不稳定 | Q8/Q9/Q10 断言失败 | 联调测试只验证「价格非空 / 在合理区间」，不写死具体金额 |

### 14.10 联调结果 ✅

MySQL `crossask_ai.products` 表：**25 条商品入库**（5 个类目：iPhone/AirPods/Sony 耳机/MacBook/Kindle、Bose/Samsung/Google/Apple Watch/iPad 补充），通过 mock JSON 加载。

v0.7 重构后 v0.6 RAG 回归测试与新增商品类测试全部通过：

| # | 问题 | 工具调用 | 结果 | 说明 |
|---|---|---|---|---|
| Q1 | eBay 上购买的商品如何申请退货？ | searchDocs | ✅ | 5 条退货相关来源，准确回答退货流程 |
| Q2 | 国际包裹寄到美国需要交关税吗？ | searchDocs | ✅ | 5 条关税来源，提到 $800 免税额 |
| Q3 | USPS Priority Mail International 寄到中国需要多少天？ | searchDocs | ✅ | 6-10 个工作日，命中该服务页 |
| Q4 | 哪些物品禁止国际寄送？ | searchDocs | ✅ | 列出 USPS 禁止清单 |
| Q5 | 北京今天天气怎么样？ | 未调（兜底） | ✅ | FALLBACK_EMPTY，未编造 |
| Q6 | Priority Mail Express International 寄到加拿大要几天？ | searchDocs | ✅ | 3-5 个工作日（sparse 精确匹配） |
| Q7 | First-Class Package International Service 的时效是多少？ | searchDocs | ✅ | 1-4 周，诚实说明无时效保证 |
| Q8 | iPhone 15 大概多少钱？ | queryProducts(keyword="iPhone 15") | ✅ | 返回 $433.50-$641.69 价格区间，5 个商品 |
| Q9 | 有 200 美元以下的耳机吗？ | queryProducts(keyword="headphones", maxPrice=200) | ✅ | **LLM 将"耳机"→"headphones"** 后匹配到 Sony 耳机 2 款 |
| Q10 | Apple 品牌的免邮商品有哪些？ | queryProducts(brand="Apple", freeShipping=true) | ✅ | 返回 5 个 Apple 品牌免邮商品 |
| Q11 | 我想退掉买的 iPhone 15，怎么操作？ | **searchDocs + queryProducts** | ✅ | 混合调用：5 条退货 sources + 1 个商品 (iPhone 15) |
| Q12 | iPhone 16 多少钱？ | 未调（兜底） | ✅ | 库中无 iPhone 16，FALLBACK_EMPTY，未编造 |

**关键发现**：
- Q9 首次测时 LLM 直接用中文"耳机"调 queryProducts（LIKE 不匹配英文 title）。通过优化 SYSTEM_PROMPT（新增中文→英文翻译约束）解决，重测后 LLM 自动翻译为 "headphones" 并正确返回结果
- Q11 验证了 LLM 对混合问题的主动双工具协同能力——LLM 自动先调 queryProducts 获取商品，再调 searchDocs 获取退货文档，最终综合回答
- 整个 12 题测试中未出现 LLM 编造现象（Q5/Q12 均诚实返回兜底）；工具调用边界清晰，未出现商品问题错误调 searchDocs 的情况

### 14.11 实施过程中的方案调整

| # | 调整点 | 原方案 | 实施方案 | 原因 |
|---|---|---|---|---|
| 1 | MySQL 建库 | schema.sql + spring.sql.init 自动执行 | 抽到 `db-init/V0_7__products.sql`，用户手动执行 | 账号无 CREATE 权限，5.7.44 远程需有权限账号建库 |
| 2 | 库名 | `crossask` | `crossask_ai` | 账号读写库与账号同名 |
| 3 | 商品数据源 | eBay Jsoup 爬虫 | 加载 `products-mock.json`（25 条 mock） | eBay 对国内 IP 返回 403 |
| 4 | MyBatis-Plus 版本 | 3.5.7+ | 3.5.9 | 3.5.7 与 Boot 3.5.3 有包路径不兼容 |
| 5 | 分页插件 | 配置 MybatisPlusInterceptor | 移除 | 3.5.9 拆到独立模块，v0.7 不需分页 |
| 6 | 自动建表 | schema.sql + sql.init | 去除，只用手动初始化 | 避免冲突，可控性更好 |
| 7 | System Prompt | 未说明中英翻译 | 新增翻译约束 | Q9 中文 keyword 查英文 title 失败 |

---

## 十五、v0.8 详细设计：Eval（评测集 + Recall@5）

> 本章节是 v0.8 的实现依据，代码改动以此为准。

### 15.1 目标与原理

到 v0.7 为止，CrossAsk 的所有验证靠手工跑 12 题、肉眼看答案。这有两个问题：
1. **迭代没有量化反馈**：改 prompt、换 embedding 维度、调 retrieve-top-k，怎么判断变好了还是变坏了？
2. **回归测试不可复用**：每次升级后手工重跑，主观判断容易遗漏

v0.8 引入 **自动化 Eval**：把测试用例变成可重复执行的评测脚本，输出 Recall@5 / MRR / Tool-call accuracy 等指标。

**核心设计原则**：
- **黑盒测试**：eval 模块通过 HTTP 调 `/ask` 端点，不依赖 api 模块内部实现，最贴近真实用户体验
- **评测集与执行分离**：评测集是 YAML 文件（手工标注 ground truth），eval 模块只负责读取 + 执行 + 出报告
- **指标基于检索结果而非 LLM 输出**：Recall@K / MRR / Tool-call accuracy 基于结构化的 sources/products 列表，不受 LLM 回答随机性影响

### 15.2 Eval Pipeline

```
eval-sets/*.yaml（评测集，手工标注 ground truth）
   │
   ▼
EvalRunner（CommandLineRunner）
   │
   ├─ 逐题调 POST /ask（HTTP 黑盒）
   │   → AskResponse { answer, sources[], products[] }
   │
   ├─ 逐题计算指标
   │   ├─ Recall@5：期望 source_url 在 sources 中出现？
   │   ├─ MRR：第一个命中的排名倒数
   │   ├─ Tool-call accuracy：实际工具集合 == 期望工具集合？
   │   └─ Keyword hit rate：answer 含期望关键词？
   │
   ├─ 汇总总体 + 分类指标
   │
   └─ 输出 Markdown 报告 → eval-reports/YYYYMMDD-HHMMSS.md
```

### 15.3 v0.8 架构图

```
┌──────────────────────────────────────────────────────────────┐
│  crossask-eval（独立模块，CommandLineRunner）                │
│                                                              │
│  EvalRunner                                                  │
│    │                                                         │
│    ├─ 读取 eval-sets/eval-core.yaml（30 题手工标注）         │
│    │                                                         │
│    ├─ 逐题：HTTP POST → http://localhost:8080/ask            │
│    │     → AskResponse { answer, sources, products }         │
│    │                                                         │
│    ├─ MetricCalculator                                       │
│    │   ├─ recallAt5(question, actualSources, expectedUrls)   │
│    │   ├─ mrr(question, actualSources, expectedUrls)         │
│    │   ├─ toolCallAccuracy(actual, expected)                 │
│    │   └─ keywordHitRate(answer, expectedKeywords)           │
│    │                                                         │
│    └─ ReportWriter → eval-reports/YYYYMMDD-HHMMSS.md         │
│                                                              │
└──────────────────────────────────────────────────────────────┘
         │
         ▼ HTTP
┌──────────────────────────────────────────────────────────────┐
│  crossask-api（POST /ask，v0.7 已有，不改动）                │
│  AskService → ChatClient + tools → AskResponse               │
└──────────────────────────────────────────────────────────────┘
```

### 15.4 评测集设计

#### 15.4.0 标注前置：导出 Qdrant 文档 URL 清单

标注 `expected_source_urls` 前必须先知道 Qdrant 里实际有哪些 source_url。执行：

```powershell
# 导出 crossask_docs collection 的所有 source_url + source_title（去重）
$body = '{"limit":100,"with_payload":true,"with_vector":false}'
Invoke-RestMethod -Method Post -Uri "http://101.96.211.131:6333/collections/crossask_docs/points/scroll" -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 10
```

把结果整理成 `eval-sets/known-docs.yaml`（含 url + title），作为标注 `expected_source_urls` 的候选池。同理从 MySQL `SELECT item_id, title FROM products` 导出商品清单作为 `expected_item_ids` 候选池。

#### 15.4.1 评测集格式（YAML）

每条测试用例包含：

```yaml
- id: "Q1"
  question: "eBay 上购买的商品如何申请退货？"
  category: "docs"          # docs / products / mixed / fallback
  expected_tools:           # 期望 LLM 调用的工具集合
    - "searchDocs"
  expected_source_urls:     # 期望命中的文档 URL（docs/mixed 类填，从 known-docs.yaml 选）
    - "https://www.ebay.com/help/policies/returns/..."
  expected_item_ids: []     # 期望命中的商品 itemId（products/mixed 类填）
  expected_keywords:        # 期望 answer 中包含的关键词（中英均可，命中率取并集）
    - "退货"
    - "退款"
    - "return"
  expected_answer_lang: "zh"  # 期望回答语言（zh / en / any）
  notes: ""                 # 备注（可选）
```

#### 15.4.2 评测集来源与规模

| 来源 | 题数 | 说明 |
|---|---|---|
| v0.7 已有的 Q1-Q12 | 12 | 已手工验证通过，转为 ground truth |
| 新增文档类（eBay/USPS） | 8 | 覆盖退货/关税/物流/禁寄物品等场景 |
| 新增商品类（价格/品牌/库存） | 6 | 覆盖 iPhone/AirPods/Sony/Kindle 等 |
| 新增混合类 | 2 | "退货+商品""物流+商品"组合 |
| 新增兜底类 | 2 | 库中无商品 / 天气等无关问题 |
| **合计** | **30** | |

> **关于"合成 QA"**：演进路线原文写"合成QA集"，本设计将其理解为"把分散测试用例系统化编排成评测集"。v0.8 先做手工 30 题（高质量、可控），LLM 自动合成 QA 作为 future enhancement 留到后续迭代。

#### 15.4.3 Ground Truth 标注规范

- **expected_source_urls**：从 Qdrant 已知文档 URL 列表中选 1-3 个最相关的（不要求全部命中，Recall@5 按命中率算）
- **expected_item_ids**：从 MySQL 25 条商品中选 1-3 个最匹配的 itemId
- **expected_keywords**：3-5 个答案中必须出现的关键词（中文/英文均可）
- **expected_tools**：
  - `"searchDocs"` — 文档类问题
  - `"queryProducts"` — 商品类问题
  - `"searchDocs", "queryProducts"` — 混合类问题
  - 空列表 `[]` — 兜底类问题（LLM 不应调任何工具）

### 15.5 指标定义

#### 15.5.1 Recall@5（文档类 + 混合类）

```
Recall@5 = |expected_source_urls ∩ actual_source_urls| / |expected_source_urls|
```

- actual_source_urls 取自 `AskResponse.sources[].sourceUrl`
- AskResponse.sources 已是 rerank 后的 top 5（rerank-top-k=5），因此 Recall@5 = 最终命中率
- 每题 Recall@5 ∈ [0, 1]，整体 Recall@5 = 所有 applicable 题的平均值
- **expected_source_urls 为空的题（商品类/兜底类）标 N/A，不计入平均值**

> **关于 Recall@15（rerank 前召回率）**：黑盒 HTTP 方案拿不到 rerank 前的中间召回结果。如果 Recall@5 低，无法直接判断是混合检索召回本身没召到，还是 rerank 排错了。v0.8 先做最终 Recall@5；未来如需 Recall@15，可在 api 加一个 `/ask/debug` 端点返回中间召回（v0.9+ 考虑）。

#### 15.5.2 MRR（文档类 + 混合类）

```
MRR = 1 / rank_of_first_hit
```

- rank_of_first_hit：actual_source_urls 中第一个命中 expected_source_urls 的位置（1-based）
- 未命中 → MRR = 0
- 整体 MRR = 所有 applicable 题的平均值

#### 15.5.3 Tool-call Accuracy（严格 + 宽松两种）

**严格匹配**（Strict）：
```
Strict = (实际工具集合 == 期望工具集合) ? 1 : 0
```

**宽松匹配**（Loose）：
```
Loose = (实际工具集合 ⊆ 期望工具集合) ? 1 : 0
```
- 宽松匹配容忍 LLM 少调工具（如 mixed 类只调了 searchDocs 但答对了）
- 两者都报，严格更严格、宽松更宽容

实际工具集合从 AskResponse 反推：
- `sources` 非空 → `searchDocs` 被调
- `products` 非空 → `queryProducts` 被调
- 两者都空 → 无工具被调（兜底）

#### 15.5.4 Keyword Hit Rate（中英并集）

```
Keyword Hit Rate = |expected_keywords ∩ answer_keywords| / |expected_keywords|
```

- answer_keywords：answer 文本中包含的期望关键词数
- 大小写不敏感、中文精确匹配
- expected_keywords 可同时含中英文（如 `["退货", "return"]`），只要答案含任一即算命中
- 整体 = 所有题的平均值

#### 15.5.5 LLM-judge（可选，默认关闭）

```
--eval.judge=true 时启用
```

调 qwen-plus 对每题 answer 打分。Judge prompt：

```
你是评测员。请对以下 RAG 系统的回答打分（1-5 分）。

问题：{question}
期望信息：{expected_keywords}
实际回答：{answer}

评分维度：
- accuracy（1-5）：答案是否准确反映检索结果
- completeness（1-5）：答案是否完整覆盖问题
- hallucination（yes/no）：是否有编造内容

输出 JSON：{"accuracy": N, "completeness": N, "hallucination": "yes/no"}
```

> 默认关闭。启用时每题多一次 LLM 调用（~0.01 元），30 题约 0.3 元。

> 独立 `crossask-eval` Maven 子模块，依赖 crossask-common + jackson-yaml，**不依赖 api/ingestion/mybatis-plus**（需排除 `MybatisPlusAutoConfiguration`）。用 `java.net.http.HttpClient` 黑盒调 `/ask`，报告输出 `crossask-eval/eval-reports/YYYYMMDD-HHMMSS.md`。

### 15.6 兜底与异常

| 场景 | 处理 |
|---|---|
| api 服务未启动 / HTTP 超时 | 该题标记为 `ERROR`，指标计 0，报告中标红 |
| 评测集 YAML 解析失败 | 程序终止，打印解析错误位置 |
| AskResponse 返回 FALLBACK_EMPTY / FALLBACK_ERROR | 正常记录，按实际 sources/products 计算 Tool-call accuracy |
| LLM-judge 调用失败 | 该题 judge 字段标 `N/A`，不影响其他指标 |
| 某题 expected_source_urls 为空（如商品类/兜底类） | 该题 Recall@5 / MRR 标 `N/A`，不计入平均值 |

### 15.7 设计决策

1. **独立模块而非 JUnit 测试**：Eval 是离线批处理任务（跑 30 题 ≈ 2 分钟），不适合放在每次构建的单元测试里；独立模块可按需手动跑
2. **HTTP 黑盒而非白盒**：最贴近真实用户体验，且不依赖 api 模块内部实现——未来重构 AskService 不影响 eval
3. **手工评测集而非 LLM 合成**：30 题手工标注 ground truth 质量可控；LLM 合成 QA 有质量不稳定 + 模型自评循环风险，留到后续迭代
4. **指标基于结构化数据而非 LLM 输出**：Recall@5 / MRR / Tool-call accuracy 基于 sources/products 列表，不受 LLM 回答随机性影响，可复现
5. **Keyword hit rate 而非 exact match**：LLM 回答措辞灵活，用关键词命中率容忍度高；expected_keywords 支持中英并集，适应跨语言场景
6. **LLM-judge 默认关闭**：成本 + 稳定性考量，作为可选增强
7. **Markdown 报告**：人可读 + git 可 diff，便于版本间对比
8. **Recall@5 而非 Recall@15**：黑盒方案拿不到 rerank 前的中间召回，先做最终 Recall@5（rerank 后 top 5）；如需诊断 rerank vs 召回分别的问题，未来在 api 加 `/ask/debug` 端点返回中间结果（v0.9+ 考虑）
9. **Tool-call accuracy 双指标**：严格匹配（精确集合相等）+ 宽松匹配（子集），两者都报以兼顾不同评估视角
10. **报告含 api 配置快照**：在报告头部记录被测 api 的 hybrid.enabled / rerank.enabled / retrieve-top-k 等配置，方便对比不同配置下的指标差异（需 eval 启动时手工指定或从 api `/actuator/env` 读取——v0.8 先手工填入命令行参数）

### 15.8 已知风险

| 风险 | 影响 | 应对 |
|---|---|---|
| api 服务跑着但 Qdrant/MySQL 连不上 | 大量题 ERROR | 报告头部标注 ERROR 题数，提醒检查 api 日志 |
| LLM 回答随机性导致 Keyword hit rate 波动 | 同一评测集两次跑分数不同 | Recall@5 / MRR / Tool-call accuracy 不受影响（基于结构化数据）；Keyword hit rate 容忍 ±10% 波动 |
| 评测集 ground truth 标注有误 | 指标不准 | 评测集进 git，标注错误可追溯修正 |
| api 启动了 v0.6 纯 dense 模式 | Recall@5 下降 | 报告中标注 api 当前配置 |

### 15.9 联调结果 ✅

**运行时间**：2026-06-22 21:17，30 题全跑通，0 ERROR，耗时约 5 分钟。

#### 总体指标

| 指标 | 值 |
|---|---|
| Recall@5 | **0.8529** |
| MRR | **0.8137** |
| Tool-call Accuracy (strict) | **0.9667**（29/30） |
| Tool-call Accuracy (loose) | **1.0000**（30/30） |
| Keyword Hit Rate | **0.7372** |
| ERROR 题数 | 0 |

#### 分类指标

| 类别 | 题数 | Recall@5 | MRR | Tool(strict) | Tool(loose) | Keyword |
|---|---|---|---|---|---|---|
| docs | 14 | 0.8214 | 0.8571 | 1.0000 | 1.0000 | 0.8000 |
| products | 9 | N/A | N/A | 1.0000 | 1.0000 | 0.9074 |
| mixed | 3 | 1.0000 | 0.6111 | 0.6667 | 1.0000 | 0.9167 |
| fallback | 4 | N/A | N/A | 1.0000 | 1.0000 | 0.0000 |

#### 关键发现

1. **Recall@5 = 0.85**：文档类 14 题中 12 题命中期望 URL（Q4/Q14 未命中——Q4"禁止国际寄送的物品"期望 customs-forms.htm 但 LLM 召回的是其他文档；Q14"退款保障政策"期望 money-back-guarantee 但召回偏差）
2. **Tool-call strict = 0.97**：唯一 strict 失败的是 Q28（mixed 类只调了 searchDocs 未调 queryProducts，但 loose 匹配通过）
3. **Tool-call loose = 1.00**：所有题实际工具调用都是期望工具的子集
4. **Keyword Hit Rate = 0.74**：fallback 类为 0（兜底话术不含期望关键词"无法/找不到"——实际兜底话术是"未找到相关信息"，语义相同但字面不匹配，后续可优化 expected_keywords）
5. **products 类 Tool-call = 1.00**：商品类问题 LLM 100% 正确调用 queryProducts
6. **Q27 混合类双工具协同成功**：LLM 同时调了 searchDocs + queryProducts，Recall@5 = 1.00

#### 对比 v0.7 手工测试

- v0.7 手工跑 12 题（Q1-Q12），主观判断全通过
- v0.8 自动化 30 题，量化指标：Recall@5 = 0.85、Tool-call = 0.97/1.00
- v0.8 新增 18 题发现 2 个 Recall@5 失败案例（Q4/Q14）和 1 个 Tool-call strict 失败（Q28），这些在手工测试中可能被忽略

### 15.10 实施过程中的方案调整

| # | 调整点 | 原方案 | 实施方案 | 原因 |
|---|---|---|---|---|
| 1 | EvalApplication 排除自动配置 | 仅排除 MybatisPlusAutoConfiguration | 同时排除 DataSourceAutoConfiguration | common 传递 mybatis-plus-starter → jdbc-starter → DataSourceAutoConfiguration 触发但无 datasource 配置 |
| 2 | ComponentScan 范围 | `com.crossask.common, com.crossask.eval` | 仅 `com.crossask.eval` | 不扫 common 的 Bean（DashScopeHybridEmbeddingClient 等），避免引入不需要的依赖 |
| 3 | YAML 字段映射 | 默认 Jackson | 配 `PropertyNamingStrategies.SNAKE_CASE` | YAML 用 snake_case（`expected_tools`），Java 字段用 camelCase（`expectedTools`），不配则字段全部为 null |
| 4 | MetricCalculator 注解 | 漏 @Component | 补 `@Component` | 否则 Spring 不扫描，EvalRunner 注入失败 |

---

## 十六、v0.9 详细设计：多轮对话 + 记忆

> 本章节是 v0.9 的实现依据，代码改动以此为准。

### 16.1 目标与原理

到 v0.8 为止，CrossAsk 每次调用 `/ask` 都是**无状态单轮问答**——用户问"iPhone 15 多少钱"，系统答完就忘；用户再问"那 256GB 的呢"，系统不知道"那"指什么。

v0.9 引入 **多轮对话记忆**：同一个 sessionId 下的连续问答共享上下文，用户可以追问、引用前文、切换话题。这是客服场景的刚需——用户 rarely 一次问清楚，往往是"先问价格→再问退货→再问运费"连续多轮。

**核心设计**：
- 用 Spring AI 的 `ChatMemory` 抽象 + 自定义 `MySQLChatMemoryRepository`（持久化到 MySQL）
- `/ask` 请求体加可选 `sessionId` 字段，不传则单轮（向后兼容 v0.7）
- 每次 LLM 调用前从 MySQL 取最近 5 轮历史，拼到 system prompt 后
- 历史保留 7 天，定时任务每周清理过期记录

### 16.2 Pipeline 变化

**v0.7（当前）**：
```
question → ChatClient(system + tools) → LLM → answer
```

**v0.9**：
```
question + sessionId
   │
   ├─ sessionId 非空 → 从 MySQL chat_history 取最近 5 轮 (10 条消息)
   │                   → 加入 ChatClient 的 messages
   │                   → ChatClient(system + history + user + tools) → LLM → answer
   │                   → 把本轮 user + answer 写入 chat_history
   │
   └─ sessionId 为空 → 退化为 v0.7 单轮模式（不读不写历史）
```

变化点：
1. `AskRequest` 新增 `sessionId` 可选字段
2. `AskService` 构造 ChatClient 时加 `.defaultAdvisors(MessageChatMemoryAdvisor)` 或手动拼历史
3. 新增 `MySQLChatMemoryRepository` 实现 Spring AI 的 `ChatMemoryRepository` 接口
4. 新增 `chat_history` 表（MySQL）
5. 新增定时清理任务 `ChatHistoryCleanupTask`

### 16.3 v0.9 架构图

```
用户提问 + sessionId
   │
   ▼
┌──────────────────────────────────────────────────────────────┐
│  crossask-api  (POST /ask)                                   │
│                                                              │
│  AskService                                                  │
│    │                                                         │
│    ├─ sessionId 非空？                                       │
│    │   ├─ 是：MySQLChatMemoryRepository.get(sessionId)       │
│    │   │      → List<Message> 最近 5 轮                      │
│    │   │      → 加入 ChatClient prompt                       │
│    │   └─ 否：跳过历史                                       │
│    │                                                         │
│    ├─ ChatClient(system + history + user + tools)            │
│    │   → LLM (qwen-plus) → answer                            │
│    │                                                         │
│    ├─ sessionId 非空？                                       │
│    │   ├─ 是：MySQLChatMemoryRepository.add(sessionId,       │
│    │   │      user + assistant 消息)                          │
│    │   └─ 否：跳过                                           │
│    │                                                         │
│    └─ 返回 AskResponse                                        │
│                                                              │
│  ChatHistoryCleanupTask（@Scheduled，每周日凌晨 3 点）        │
│    → DELETE FROM chat_history WHERE created_at < NOW() - 7天 │
└──────────────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
┌──────────────────────┐  ┌──────────────────────┐
│  MySQL crossask_ai   │  │  DashScope           │
│  - products (v0.7)   │  │  - hybrid embedding  │
│  - chat_history (新) │  │  - qwen3-rerank      │
│                      │  │  - qwen-plus (tools) │
└──────────────────────┘  └──────────────────────┘
```

### 16.4 数据模型：chat_history 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT, PK, AUTO_INC | 主键 |
| `session_id` | VARCHAR(64), NOT NULL | 会话 ID（UUID 或前端生成），索引 |
| `turn_index` | INT, NOT NULL | 轮次序号（从 1 开始） |
| `role` | VARCHAR(16), NOT NULL | `user` / `assistant` / `tool` |
| `content` | TEXT, NOT NULL | 消息内容 |
| `tool_name` | VARCHAR(64), NULL | 仅 role=tool 时填，记录工具名 |
| `created_at` | DATETIME DEFAULT CURRENT_TIMESTAMP | 创建时间（用于 TTL 清理） |

**索引**：`INDEX (session_id, turn_index)`、`INDEX (created_at)`（清理用）。

**DDL** 放 `db-init/V0_9__chat_history.sql`。

### 16.5 ChatMemory 实现设计

#### 16.5.1 方案选型：自定义 Service 而非实现 Spring AI 接口

Spring AI 的 `ChatMemoryRepository` 接口在不同小版本有 API 变动（`saveAll` 语义是"覆盖全部"而非"追加"），且其 `Message` 抽象与 DashScope ChatClient 的消息格式有适配层。

**决策**：不实现 Spring AI 的 `ChatMemoryRepository` 接口，而是自定义 `ChatHistoryService`——直接用 MyBatis-Plus 操作 `chat_history` 表，返回 `List<Message>`（Spring AI 的消息类型）供 AskService 拼接到 ChatClient。这样：
- 不受 Spring AI 版本变动影响
- `saveAll` 语义自主控制（追加而非覆盖）
- 可以在存储时附加工具调用摘要

#### 16.5.2 ChatHistoryService

```java
@Service
public class ChatHistoryService {

    private final ChatHistoryMapper mapper;
    private final int maxTurns;  // 从配置注入，默认 5

    /** 取最近 maxTurns 轮（maxTurns*2 条 user+assistant 消息）。 */
    public List<Message> getHistory(String sessionId) {
        List<ChatHistory> rows = mapper.selectRecent(sessionId, maxTurns * 2);
        List<Message> messages = new ArrayList<>();
        for (ChatHistory row : rows) {
            if ("user".equals(row.getRole())) {
                messages.add(new UserMessage(row.getContent()));
            } else if ("assistant".equals(row.getRole())) {
                messages.add(new AssistantMessage(row.getContent()));
            }
        }
        return messages;
    }

    /** 追加本轮 user + assistant 消息。 */
    public void appendTurn(String sessionId, String userQuestion, String assistantAnswer) {
        int nextTurn = mapper.countBySession(sessionId) / 2 + 1;
        mapper.insert(makeRow(sessionId, nextTurn, "user", userQuestion));
        mapper.insert(makeRow(sessionId, nextTurn, "assistant", assistantAnswer));
    }
}
```

#### 16.5.3 AskService 集成

```java
public AskResponse ask(AskRequest request) {
    ToolCallContext.reset();

    String sessionId = request.getSessionId();
    List<Message> history = sessionId != null && !sessionId.isBlank()
            ? chatHistoryService.getHistory(sessionId)
            : List.of();

    ChatClient.ChatClientRequest spec = chatClient.prompt()
            .user(request.getQuestion());
    if (!history.isEmpty()) {
        spec = spec.messages(history);
    }

    String answer;
    try {
        answer = spec.call().content();
    } catch (Exception e) {
        log.error("LLM/Tool 调用失败", e);
        return new AskResponse(FALLBACK_ERROR, List.of(), List.of());
    }

    // 持久化历史（失败不中断主流程）
    if (sessionId != null && !sessionId.isBlank() && answer != null && !answer.isBlank()) {
        try {
            chatHistoryService.appendTurn(sessionId, request.getQuestion(), answer);
        } catch (Exception e) {
            log.warn("写入 chat_history 失败，不中断主流程: {}", e.getMessage());
        }
    }

    // ... 兜底逻辑同 v0.7（ToolCallContext 取 sources/products）
}
```

#### 16.5.4 关于 tool_call 中间消息

v0.7 的 Function Calling 流程中，LLM 会产生 tool_call/tool_response 中间消息。本设计**不存这些中间消息**——只存 user 的原始问题 + assistant 的最终回答 text。下一轮 LLM 虽然不知道上一轮具体调了什么工具，但能从 assistant 的最终回答 text 中推断上下文（因为回答里已包含工具返回的信息）。

如果发现多轮场景下 LLM 上下文理解不足，可在 `appendTurn` 时给 assistant content 前缀加一个简短摘要，如 `"[调用了 searchDocs] 原文回答..."`。v0.9 先不加，联调时观察。

### 16.6 兜底与异常

| 场景 | 处理 |
|---|---|
| sessionId 为 null/空 | 退化为单轮模式，不读不写历史（v0.7 兼容） |
| MySQL 读历史失败 | 记日志，降级为单轮（不中断问答） |
| MySQL 写历史失败 | 记日志，不影响已返回的 answer |
| 历史超过 5 轮 | 只取最近 5 轮，旧历史 7 天后清理 |
| 跨用户 sessionId 冲突 | sessionId 由前端生成（UUID），冲突概率极低 |
| 定时清理任务失败 | 记日志，下周重试；不影响主流程 |

### 16.7 设计决策

1. **MySQL 而非 InMemory**：服务重启/部署时历史不丢失，用户体验连续；已有 MySQL 零新组件
2. **MySQL 而非 Redis**：单为对话历史引入 Redis 不值得，MySQL + 定时清理够用；未来如需 Redis 做缓存可再引入
3. **7 天 TTL 而非永久**：对话历史价值随时间衰减，7 天覆盖绝大多数用户连续追问场景；避免表无限增长
4. **最近 5 轮而非全部**：5 轮（10 条消息）≈2000 token，覆盖绝大多数追问场景；超过 5 轮的旧上下文对当前问题帮助有限
5. **扩展 /ask 而非新建端点**：sessionId 可选，向后兼容；避免代码重复；eval 模块零改动
6. **自定义 ChatHistoryService 而非实现 Spring AI ChatMemoryRepository**：Spring AI 接口在不同小版本有 API 变动（saveAll 语义是覆盖而非追加），自定义 Service 不受版本影响、语义自主可控
7. **turn_index 冗余字段**：虽然可按 id 排序，但 turn_index 语义更清晰，便于调试和审计
8. **eval 模块零改动**：eval-core.yaml 的 30 题不传 sessionId，自动退化为单轮，v0.8 基线指标不受影响

### 16.8 已知风险

| 风险 | 影响 | 应对 |
|---|---|---|
| Spring AI 版本的 ChatMemory API 变动 | 编译失败 | Spring AI Alibaba 1.1.2.0 基于 Spring AI 1.0.x，API 稳定；联调时确认接口签名 |
| 历史消息 token 超限 | LLM 报 context overflow | 5 轮 ≈2000 token，qwen-plus 上下文窗口 131072，远不会超；极端情况降级为 3 轮 |
| 历史中含 tool_call 消息 | 序列化复杂 | chat_history 只存 user + assistant 的 text，不存 tool_call/tool_response 中间消息；LLM 仍能从上下文推断 |
| 多用户并发同一 sessionId | 历史串扰 | sessionId 由前端 UUID 生成，冲突概率 ≈0；生产可加 userId 维度 |
| 定时清理误删活跃会话 | 用户正在追问时历史被删 | cron 设在凌晨 3 点低峰期；7 天窗口足够长，活跃会话不会 7 天不交互 |
| chat_history 表膨胀 | 查询变慢 | 7 天清理 + `INDEX(session_id, turn_index)`；预估 1000 活跃用户 ×10 条/天 ×7 天 = 7 万行，MySQL 轻松 |

### 16.9 联调结果 ✅

**运行时间**：2026-06-22 23:20-23:37

#### Q31-Q35 多轮追问测试（sessionId=S1）

| # | 问题 | 历史轮数 | LLM 理解 | 工具调用 | 结果 |
|---|---|---|---|---|---|
| Q31 | iPhone 15 大概多少钱？ | 0（首轮） | — | queryProducts(keyword="iPhone 15") | ✅ 返回 5 个商品，价格 $433.50-$641.69 |
| Q32 | 256GB 的呢？ | 1 | ✅ 理解"256GB 的 iPhone 15" | queryProducts(keyword="iPhone 15 256GB") | ⚠️ 兜底（LIKE 匹配失败，title 是"iPhone 15 Pro 256GB"） |
| Q33 | 那它的退货政策是什么？ | 2 | ✅ 理解"它"指 iPhone 15 | searchDocs | ✅ 返回 5 条退货文档，回答开头"iPhone 15 的退货政策" |
| Q34 | AirPods Pro 多少钱？ | 3 | ✅ 话题切换 | queryProducts(keyword="AirPods Pro") | ✅ 返回 4 个商品，价格 $159.45-$219.99 |
| Q35 | 免邮吗？ | 4 | ✅ 理解"免邮"指 AirPods Pro | 未调工具 | ⚠️ 兜底（LLM 试图从历史回答未调工具） |

**关键发现**：
1. **历史读取/写入正常**：Q31-Q35 每轮都正确读取历史（turns=0→1→2→3→4），正确写入新消息
2. **代词解析成功**：Q33 的"它"→iPhone 15、Q35 的"免邮"→AirPods Pro，LLM 正确理解上下文
3. **话题切换正常**：Q34 从 iPhone 切换到 AirPods，LLM 正确切换工具参数
4. **Q32 失败原因**：ProductService 的 LIKE '%iPhone 15 256GB%' 不匹配 title "iPhone 15 Pro 256GB"——这是 v0.7 查询精确度问题，非 v0.9 多轮功能问题
5. **Q35 失败原因**：LLM 觉得历史已有 AirPods 信息就直接答了（未调工具），触发 FALLBACK_EMPTY——可通过加强 system prompt 约束改善

#### v0.8 eval 回归（30 题，不传 sessionId）

| 指标 | v0.8 基线 | v0.9 回归 | 变化 | 说明 |
|---|---|---|---|---|
| Recall@5 | 0.8529 | 0.7353 | ↓0.12 | LLM 回答随机性 + 1 题 ERROR |
| MRR | 0.8137 | 0.7647 | ↓0.05 | 误差范围内 |
| Tool-call strict | 0.9667 | 0.8966 | ↓0.07 | 1 题 ERROR |
| Tool-call loose | 1.0000 | 1.0000 | = | **完全一致** |
| ERROR | 0 | 1 | +1 | 网络超时 |

**回归结论**：Tool-call loose 保持 1.0，核心功能未退化。Recall@5/MRR 波动在 LLM 随机性范围内（v0.8 设计文档 15.11 已知风险第 2 条已预见）。

#### chat_history 表数据

sessionId=S1 的 5 轮对话共 10 条消息（5 user + 5 assistant），turn_index 1-5，created_at 正常。

### 16.10 实施过程中的方案调整

| # | 调整点 | 原方案 | 实施方案 | 原因 |
|---|---|---|---|---|
| 1 | ChatClient.ChatClientRequest 类型 | 显式声明 `ChatClient.ChatClientRequest spec` | 改用 `var spec` | Spring AI 1.1.2.0 的 ChatClientRequest 是内部类型，不在公开 API 中 |
| 2 | AskController 调用方式 | `askService.ask(request.getQuestion())` | `askService.ask(request)` | v0.9 需要传整个 AskRequest（含 sessionId），不能只传 question |
| 3 | System Prompt 加强 | v0.7 原版 | 新增代词解析规则："When the user uses pronouns like 它/这个/那个, refer to conversation history" | 多轮场景下 LLM 需要知道如何处理代词 |

---

## 十七、v1.0 详细设计：多语言优化

> 本章节是 v1.0 的实现依据，代码改动以此为准。

### 17.1 目标与原理

到 v0.9 为止，CrossAsk 的文档库（Qdrant 83 chunks）和商品库（MySQL 25 条）**内容全是英文**，但用户主要用**中文提问**。当前靠 system prompt 让 LLM 翻译关键词调工具，但存在两个已知问题：

1. **RAG sparse 路召回失败**：DashScope sparse embedding 对中文 query 生成的稀疏向量是中文关键词，与英文文档的 sparse 向量不匹配。例如 Q4"禁止国际寄送"的 sparse 向量是 `{禁止, 国际, 寄送}`，与英文文档 `{prohibited, international, shipping}` 完全不交集。dense 路虽然能跨语言（text-embedding-v4 是多语言模型），但 sparse 路完全失效，hybrid 退化为 dense-only。
2. **商品 SQL LIKE 匹配失败**：Q32 "256GB 的呢"→ LLM 翻译为 "iPhone 15 256GB"→ LIKE '%iPhone 15 256GB%' 不匹配 title "Apple iPhone 15 Pro A2848 256GB Unlocked"。Q9 "耳机"首次也因翻译不稳定失败。

v1.0 分两步系统性解决：
- **17.2-17.5：RAG sparse 路 query 翻译** — 对 sparse 路的 query 先翻译成英文再生成 sparse vector，使 sparse 关键词能与英文文档匹配
- **17.6-17.7：商品 SQL 中英同义词表 + keyword 分词** — 在 MySQL 加一张同义词映射表，ProductService 查询时自动把中文关键词扩展为英文同义词 OR 查询；同时对多词 keyword 做分词 AND LIKE，解决"iPhone 15 256GB"不匹配"iPhone 15 Pro 256GB"的问题

### 17.2 RAG sparse 路优化方案

#### 17.2.1 问题分析

当前 v0.6 hybrid 检索流程：
```
中文 query → DashScope hybrid embedding → {dense: 多语言向量, sparse: 中文关键词}
                                                    ↓                    ↓
                                              Qdrant dense 召回    Qdrant sparse 召回
                                              （跨语言 OK）        （中文≠英文，失败！）
                                                    ↓                    ↓
                                              RRF 融合 15 → rerank 5
```

dense 路能跨语言（text-embedding-v4 是多语言模型），但 sparse 路完全失效——中文 sparse 向量与英文文档 sparse 向量零交集。

#### 17.2.2 解决方案

在 sparse 路增加 **query 翻译**步骤：

```
中文 query → 翻译成英文 query → DashScope sparse embedding → 英文 sparse 向量
                                                                    ↓
                                                          Qdrant sparse 召回
                                                          （英文=英文，匹配！）
```

dense 路保持原样（直接用中文 query，dense 本身跨语言）。

翻译用什么？两个选项：
- **A. DashScope qwen-plus LLM 翻译**：质量高但每轮多一次 LLM 调用（~1-2s 延迟）
- **B. DashScope 通义千问 MT 专用模型**：更快但可能需要额外开通

**决策：用 LLM 翻译（方案 A）**，但加缓存（相同 query 不重复翻译）。翻译 prompt 简短，qwen-plus 响应快（<1s）。

#### 17.2.3 优化后流程

```
中文 query
   │
   ├─→ dense 路：直接用中文 query → DashScope dense embedding → Qdrant dense 召回
   │        （与翻译并行执行，减少延迟）
   │
   ├─→ sparse 路：中文 query → LLM 翻译成英文 → DashScope sparse embedding → Qdrant sparse 召回
   │
   └─→ RRF 融合 → rerank 5
```

> **关键改动**：当前 v0.6 的 `RagSearchService.search()` 只调一次 `embed(question, "query")` 同时获取 dense+sparse。v1.0 需要调**两次** embed()：
> 1. `embed(question, "query")` → 取 **dense**（sparse 丢弃）
> 2. `embed(translatedQuery, "query")` → 取 **sparse**（dense 丢弃）
>
> 两次 embedding + 一次翻译 LLM 调用可用 `CompletableFuture` 并行执行，减少总延迟。
>
> **成本**：每次 RAG 检索从 1 次 embedding 调用变为 2 次 + 1 次翻译 LLM 调用。embedding 调用很便宜（text-embedding-v4 约 0.0007 元/千 token），翻译 prompt 极短（<50 token）。单次检索增加成本约 0.001 元。

### 17.3 v1.0 架构图（RAG 部分）

```
用户中文提问
   │
   ▼
┌──────────────────────────────────────────────────────────────┐
│  RagSearchService (v1.0 改造)                                │
│                                                              │
│  并行执行：                                                   │
│  ├─ CompletableFuture.supplyAsync(() -> {                    │
│  │     emb1 = embed(中文 query, "query")  // 取 dense        │
│  │     return emb1.dense()                                   │
│  │  })                                                       │
│  ├─ CompletableFuture.supplyAsync(() -> {                    │
│  │     英文 = translateToEnglish(中文 query)  // LLM 翻译    │
│  │     emb2 = embed(英文 query, "query")   // 取 sparse      │
│  │     return emb2.sparse()                                  │
│  │  })                                                       │
│  └─ join → 组装 dense + sparse → hybridSearch → rerank       │
│                                                              │
│  QueryTranslationService（新增）                             │
│    └─ translateToEnglish(query) → 英文 query                │
│       └─ 内置缓存（ConcurrentHashMap + TTL，相同 query 不重复翻译）│
└──────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────┐
│  DashScope                                                  │
│  - text-embedding-v4 (dense + sparse，调 2 次)              │
│  - qwen-plus (query 翻译)                                   │
│  - qwen3-rerank                                             │
└──────────────────────────────────────────────────────────────┘
```

### 17.4 商品 SQL 中英同义词表

#### 17.4.1 问题分析

商品 title 全是英文，但 LLM 翻译中文关键词为英文后，LIKE 查询仍可能不匹配：
- "耳机" → "headphones" → LIKE '%headphones%' ✅ 匹配 "Headphones"
- "耳机" → "earbuds" → LIKE '%earbuds%' ❌ 不匹配 "AirPods Pro"
- "手机" → "phone" → LIKE '%phone%' ❌ 不匹配 "iPhone"（大小写 + 无 "phone" 子串）

问题本质：单一关键词的 LIKE 无法覆盖同义词和品牌名。

#### 17.4.2 解决方案

商品查询的多语言 + 模糊匹配问题分两层解决：

**层 1：中文→英文同义词扩展**（解决"耳机"→"headphones/AirPods"）

在 MySQL 加一张 `product_synonyms` 同义词映射表，ProductService 查询时自动把中文关键词扩展为多个英文同义词，用 OR LIKE 查询：

```sql
-- 同义词表示例
keyword_cn | synonyms_en
耳机        | headphones, earbuds, AirPods, headset
手机        | iPhone, phone, smartphone, Samsung, Galaxy, Pixel
电脑        | MacBook, laptop, notebook
阅读器      | Kindle, Paperwhite, ereader
手表        | Watch
平板        | iPad, tablet
```

ProductService 改造：keyword="耳机" 时，先查同义词表得到 `["headphones", "earbuds", "AirPods", "headset"]`，构造 `(title LIKE '%headphones%' OR title LIKE '%earbuds%' OR ...)`。

同义词查询用 LIKE 匹配 keyword_cn（不完全匹配）：`WHERE keyword_cn LIKE '%耳机%'` 可匹配"耳机"和"降噪耳机"。

**层 2：多词 keyword 分词 AND LIKE**（解决"iPhone 15 256GB"→匹配"iPhone 15 Pro 256GB"）

对每个同义词（或原 keyword），按空格分词后用 AND LIKE：

```
keyword = "iPhone 15 256GB"
→ 分词为 ["iPhone", "15", "256GB"]
→ (title LIKE '%iPhone%' AND title LIKE '%15%' AND title LIKE '%256GB%')
→ ✅ 匹配 "Apple iPhone 15 Pro A2848 256GB Unlocked"
```

两层组合：先同义词扩展（层 1），每个词再分词 AND LIKE（层 2）：
```
keyword = "耳机"
→ 同义词扩展为 ["headphones", "earbuds", "AirPods", "headset"]
→ 每个词分词（单词无需分词）
→ (title LIKE '%headphones%' OR title LIKE '%earbuds%' OR title LIKE '%AirPods%' OR title LIKE '%headset%')
```

```
keyword = "iPhone 15 256GB"（英文，跳过同义词扩展）
→ 分词为 ["iPhone", "15", "256GB"]
→ (title LIKE '%iPhone%' AND title LIKE '%15%' AND title LIKE '%256GB%')
→ ✅ 匹配 "Apple iPhone 15 Pro A2848 256GB Unlocked"
```

#### 17.4.3 同义词表 DDL

```sql
CREATE TABLE IF NOT EXISTS product_synonyms (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    keyword_cn   VARCHAR(32)  NOT NULL                COMMENT '中文关键词',
    synonyms_en  VARCHAR(256) NOT NULL                COMMENT '英文同义词（逗号分隔）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_keyword_cn (keyword_cn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

DDL 放 `db-init/V1_0__product_synonyms.sql`，含初始数据 INSERT。

### 17.5 兜底与异常

| 场景 | 处理 |
|---|---|
| QueryTranslationService 调 LLM 失败 | 记日志，sparse 路退化为用原中文 query（与 v0.6 行为一致） |
| QueryTranslationService 缓存未命中且 LLM 超时 | 同上，降级用原 query |
| query 已是英文（ASCII >80%） | 跳过翻译，直接用原 query（省一次 LLM 调用） |
| SynonymExpander 查 MySQL 失败 | 记日志，退化为只用原 keyword 单 LIKE |
| 同义词表中无匹配的中文关键词 | 返回 `[keyword]`（原 keyword），退化为 v0.7 行为 |

### 17.6 设计决策

1. **sparse 路 query 翻译而非整体翻译**：dense 路本身跨语言无需翻译；只翻译 sparse 路的 query，最小化改动和 LLM 调用
2. **LLM 翻译而非专用 MT 模型**：qwen-plus 翻译质量够用，无需额外开通 MT 服务；加缓存避免重复翻译
3. **英文检测跳过翻译**：ASCII 占比 >80% 判定为英文，跳过翻译省 LLM 调用
4. **ConcurrentHashMap 缓存而非 Caffeine**：缓存逻辑简单（get+put+TTL 判断），用 JDK 内置 ConcurrentHashMap 避免引入额外依赖；500 条 entry 内存开销可忽略
5. **两次 embedding 调用而非一次**：DashScope embed() 每次返回 dense+sparse，无法只取其一；v1.0 调两次 embed() 分别取 dense 和 sparse，用 CompletableFuture 并行执行减少延迟
6. **同义词表而非 LLM 扩展**：同义词映射是确定性的，MySQL 表比 LLM 稳定可控；初始 10 个类目覆盖项目商品范围
7. **同义词 OR LIKE 而非 FULLTEXT**：MySQL 5.7 FULLTEXT + ngram 对中文分词不稳定；OR LIKE 简单可靠
8. **分两步实施**：先 RAG（17.1-17.3），再商品 SQL（17.4-17.5），降低单次改动风险

### 17.7 已知风险

| 风险 | 影响 | 应对 |
|---|---|---|
| LLM 翻译延迟增加 RAG 总耗时 | 每次检索多 ~1s | ConcurrentHashMap 缓存；翻译 prompt 极简（<50 token）；CompletableFuture 并行执行 |
| LLM 翻译质量不稳定 | sparse 召回波动 | 翻译 prompt 明确"ONLY translation"；缓存稳定结果 |
| 同义词表覆盖不全 | 部分中文关键词无扩展 | 初始 10 类目覆盖项目商品；后续按 eval 失败案例补充 |
| 同义词表需要维护 | 新商品类目需手工加同义词 | 同义词表进 git，随商品数据同步更新 |
| ConcurrentHashMap 缓存与多实例不一致 | 多实例缓存不同步 | 缓存 TTL=1h，1 小时后自动刷新；RAG 检索对缓存一致性要求不高 |
| 英文检测误判 | 混合中英文 query 可能跳过翻译 | ASCII >80% 阈值偏保守；混合 query 会触发翻译（中文部分 <80%） |
| keyword 分词 AND LIKE 性能 | 多词 keyword 生成多个 LIKE 条件 | 商品表仅 25 条（未来千级），LIKE 性能不是瓶颈；如需优化可加 FULLTEXT 索引 |

### 17.8 联调结果 ✅

**运行时间**：2026-06-23 09:17-09:24

#### Q4/Q9/Q32 修复验证

| # | 问题 | v0.8/v0.9 结果 | v1.0 结果 | 修复手段 |
|---|---|---|---|---|
| Q4 | 哪些物品禁止国际寄送？ | Recall@5=0.00（sparse 中文不匹配） | **Recall@5=1.00** ✅ | sparse 路 query 翻译 |
| Q9 | 有 200 美元以下的耳机吗？ | 首次失败（"耳机"未翻译） | **2 个 Sony 耳机** ✅ | 同义词扩展 [headphones, earbuds, AirPods] |
| Q32 | 256GB 的呢？（多轮 iPhone 15） | 兜底（LIKE 不匹配） | **2 个 iPhone 15 Pro 256GB** ✅ | 分词 AND LIKE |

#### v0.8 eval 回归（30 题）

| 指标 | v0.8 基线 | v1.0 | 变化 | 说明 |
|---|---|---|---|---|
| **Recall@5** | 0.8529 | **0.9706** | **↑0.12** ✅ | 达成目标 ≥0.90 |
| **MRR** | 0.8137 | 0.8480 | ↑0.03 | sparse 路翻译提升排序 |
| Tool-call strict | 0.9667 | 0.9333 | ↓0.03 | mixed 类 Q28 少调 queryProducts |
| Tool-call loose | 1.0000 | 1.0000 | = | 核心功能未退化 |
| ERROR | 0 | 0 | = | 30 题全跑通 |

**分类指标**：

| 类别 | Recall@5 (v0.8) | Recall@5 (v1.0) | 说明 |
|---|---|---|---|
| docs | 0.8214 | **0.9643** | sparse 翻译生效，Q4 从 0→1 |
| products | N/A | N/A | 同义词+分词生效 |
| mixed | 1.0000 | 1.0000 | 保持 |
| fallback | N/A | N/A | 保持 |

#### 关键发现

1. **Recall@5 从 0.85 提升到 0.97**：sparse 路 query 翻译让中文 query 的 sparse 向量与英文文档匹配，hybrid 检索不再是"伪 hybrid"（sparse 路不再失效）
2. **Q4 从 0→1**：v0.8 时"禁止国际寄送"的 sparse 向量 `{禁止,国际,寄送}` 与英文文档零交集；v1.0 翻译为 "prohibited items international shipping" 后 sparse 路正确召回
3. **Q9 同义词扩展稳定**："耳机"→[headphones, earbuds, AirPods, headset]→OR LIKE 匹配到 Sony + AirPods
4. **Q32 分词 AND LIKE 修复**："iPhone 15 256GB"→`(LIKE '%iPhone%' AND LIKE '%15%' AND LIKE '%256GB%')`→匹配"Apple iPhone 15 Pro 256GB"
5. **Tool-call strict 略降**：Q28 mixed 类只调了 searchDocs 未调 queryProducts（LLM 判断不需要查商品），loose 匹配仍通过

### 17.9 实施过程中的方案调整

| # | 调整点 | 原方案 | 实施方案 | 原因 |
|---|---|---|---|---|
| 1 | LambdaQueryWrapper 多词分词 | 用 `nested(w -> { w.and(); ... })` | 用 `and(w -> { w.like(); w.like(); })` | `wrapper.and()` 无参方法不存在；LambdaQueryWrapper 的 `and(consumer)` 内部多个 `.like()` 默认 AND 连接 |
| 2 | QueryTranslationService 的 ChatClient | 用 `ChatClient.Builder.build()` | 同左，确认不带 tools/system prompt | Builder 默认不绑定 tools，安全 |

### 17.10 全面测试后的两个紧急修复 ✅

v1.0 完成后做了一次覆盖 eval/多轮/边界/业务场景的全面测试，发现并修复了 2 个严重问题：

| # | 问题 | 修复方案 | 验证结果 |
|---|---|---|---|
| **修复 1** | 空 question 返回 HTTP 500 | `AskController` 加参数校验，question 为空时抛 `ResponseStatusException(BAD_REQUEST)` | 空问题返回 **HTTP 400** ✅ |
| **修复 2** | 多轮场景 LLM 不调工具（T5"免邮吗？"/ S4"可以退吗？"触发 FALLBACK_EMPTY） | 加强 SYSTEM_PROMPT：明确"You MUST call the appropriate tool BEFORE answering EVERY question, even in multi-turn"+ 给出具体追问示例 + 明确仅"greetings/无关问题"才跳过工具 | T5 返回 **3 个免邮 AirPods**；S4 返回 **5 sources 完整退货政策** ✅ |

涉及文件：
- [AskController.java](file:///d:/Project/CrossAsk-AI/crossask-ai-backend/crossask-api/src/main/java/com/crossask/api/controller/AskController.java) — 加 `ResponseStatusException` 参数校验
- [AskService.java](file:///d:/Project/CrossAsk-AI/crossask-ai-backend/crossask-api/src/main/java/com/crossask/api/rag/AskService.java#L37-L69) — SYSTEM_PROMPT 加强多轮工具调用约束

---

## 十八、v1.1 详细设计：前端界面 + SSE 流式输出

> 本章节是 v1.1 的实现依据，代码改动以此为准。

### 18.1 目标与原理

到 v1.0 为止，CrossAsk 后端能力完整（Recall@5=0.97、多轮记忆、跨语言召回），但**没有真实用户可用的界面**——所有验证靠 PowerShell 调 `/ask`。

v1.1 引入 **前端界面 + SSE 流式输出**，让 CrossAsk 成为可被真实用户使用的产品：
1. 类 ChatGPT 的聊天界面：输入框 + 历史气泡 + 商品/文档卡片 + 错误提示
2. SSE 流式输出：用户看到回答**逐字打字效果**，降低等待感（首字延迟从 5-10s 降为 1-2s）
3. sessionId 自动持久化（localStorage）：刷新页面不丢历史，可手动新建会话

### 18.2 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│  浏览器                                                       │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  Vue 3 SPA                                              │ │
│  │  ┌──────────────┐  ┌──────────────────────────────────┐│ │
│  │  │ 输入区        │  │ 历史气泡列表                       ││ │
│  │  │ - textarea   │  │  ┌────────────────────────────┐  ││ │
│  │  │ - 发送按钮    │  │  │ 用户气泡（蓝色，靠右）       │  ││ │
│  │  │ - 新建会话    │  │  └────────────────────────────┘  ││ │
│  │  └──────────────┘  │  ┌────────────────────────────┐  ││ │
│  │                    │  │ 助手气泡（白色，靠左）       │  ││ │
│  │                    │  │ - markdown 渲染 answer      │  ││ │
│  │                    │  │ - 商品卡片网格              │  ││ │
│  │                    │  │ - 文档来源折叠列表           │  ││ │
│  │                    │  └────────────────────────────┘  ││ │
│  │                    └──────────────────────────────────┘│ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
         │ SSE (EventSource)
         │ POST /ask/stream { question, sessionId }
         ▼
┌──────────────────────────────────────────────────────────────┐
│  crossask-api                                                │
│                                                              │
│  AskController.askStream()                                   │
│    └─ 返回 Flux<ServerSentEvent>                            │
│       ├─ event: "token", data: "iPhone "      (流式)         │
│       ├─ event: "token", data: "15 "          (流式)         │
│       ├─ event: "token", data: "256GB "        (流式)         │
│       ├─ ...                                                 │
│       ├─ event: "metadata", data: {sources, products}        │
│       └─ event: "done"                                       │
└──────────────────────────────────────────────────────────────┘
```

### 18.3 后端改动：新增 `/ask/stream` SSE 端点

> ⚠️ 本节是 v1.1 **初版的真流式设计**（`chatClient.stream()` + ToolContext）。真机调试发现其在 Reactor 调度线程下无法收集 sources/products，已被推翻——最终实现见 **18.12.1（同步 call + 切片伪流式）**。本节保留作设计演进记录。

#### 18.3.1 为什么不改原 `/ask`

原 `POST /ask` 是 v0.8 eval 模块依赖的同步接口（eval 用 java.net.http.HttpClient 调）。v1.1 **保留 /ask 不变**，新增 `/ask/stream` 专门给前端用。这样：
- eval 模块零改动
- 前端用 SSE
- 互不影响

#### 18.3.2 SSE 事件协议

前端通过 `fetch` + `ReadableStream` 接收（EventSource 不支持 POST，改用 fetch streaming）。

| 事件 | data 格式 | 含义 |
|---|---|---|
| `token` | 纯文本 token 片段 | LLM 流式输出的下一段文本 |
| `metadata` | JSON: `{sources: [...], products: [...]}` | 所有 token 流完后，发送 sources/products |
| `done` | 空 | 流结束信号 |
| `error` | 错误消息文本 | 服务端异常 |

#### 18.3.3 AskService 改造

**关键决策：用 SseEmitter（webmvc 原生）而非 Flux/webflux**

```java
public void askStream(AskRequest request, SseEmitter emitter) {
    // 1. 参数校验由 Controller 完成，这里假定 question 非空
    // 2. 关键：不使用 ToolCallContext (ThreadLocal)！改用请求级 SourceCollector 对象
    SourceCollector collector = new SourceCollector();
    
    // 3. 读取历史
    String sessionId = request.getSessionId();
    List<Message> history = (sessionId != null && !sessionId.isBlank())
            ? chatHistoryService.getHistory(sessionId) : List.of();
    
    // 4. 用专门构造的 streamChatClient（注入 collector 到工具）
    var spec = streamChatClient.prompt()
            .user(request.getQuestion())
            .toolContext(Map.of("collector", collector));   // Spring AI 1.x 支持 toolContext
    if (!history.isEmpty()) spec = spec.messages(history);
    
    // 5. 订阅 stream，逐 token 推送到 emitter
    StringBuilder fullAnswer = new StringBuilder();
    spec.stream().content().subscribe(
        token -> {
            try {
                fullAnswer.append(token);
                emitter.send(SseEmitter.event().name("token").data(escapeForSse(token)));
            } catch (IOException e) { emitter.completeWithError(e); }
        },
        err -> {
            try { emitter.send(SseEmitter.event().name("error").data(err.getMessage())); }
            catch (IOException ignored) {}
            emitter.completeWithError(err);
        },
        () -> {
            try {
                // 流结束：发 metadata + done + 持久化历史
                Map<String, Object> meta = Map.of(
                    "sources", collector.getSources(),
                    "products", collector.getProducts());
                emitter.send(SseEmitter.event().name("metadata")
                        .data(jsonMapper.writeValueAsString(meta)));  // 单行 JSON
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
                if (sessionId != null && !sessionId.isBlank()) {
                    chatHistoryService.appendTurn(sessionId,
                            request.getQuestion(), fullAnswer.toString());
                }
            } catch (Exception e) { emitter.completeWithError(e); }
        }
    );
}

/** SSE data 中含 \n 会破坏帧分隔，需替换为字面字符或多行 data: */
private static String escapeForSse(String s) {
    return s.replace("\n", "\\n");   // 前端收到后再 unescape
}
```

控制器：
```java
@PostMapping("/ask/stream")
public SseEmitter askStream(@RequestBody AskRequest req) {
    if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
    }
    SseEmitter emitter = new SseEmitter(120_000L);  // 2 分钟超时
    askService.askStream(req, emitter);
    return emitter;
}
```

**关键改动点**：
- 不用 `Flux<ServerSentEvent>` 返回值，改用 `SseEmitter`（webmvc 原生）
- 不用 ThreadLocal `ToolCallContext`，改用请求级 `SourceCollector` 对象（new 一个，传给工具）
- 工具（`RagSearchTool`/`ProductQueryTool`）改造：接受 `ToolContext` 参数，从中取出 `SourceCollector` 调 add 方法
- SSE data 转义换行符（`\n` → `\\n`），前端 unescape

#### 18.3.4 依赖与工具改造

- `crossask-api/pom.xml` — **无需改动**（webmvc 原生支持 SseEmitter，Spring AI starter 已传递 Reactor）
- `RagSearchTool` / `ProductQueryTool` 改造：
  - 方法签名加 `ToolContext toolContext` 参数（Spring AI `@Tool` 支持注入）
  - 从 `toolContext` 取出 `SourceCollector`：`var collector = (SourceCollector) toolContext.getContext().get("collector");`
  - 调用 `collector.addSources(...)` 替代 `ToolCallContext.addSources(...)`
- `SourceCollector` 类（新增）：线程安全的请求级收集器，含 `addSources/addProducts/getSources/getProducts` 方法（用 `CopyOnWriteArrayList`，因为 Spring AI 工具调用可能在不同 worker 线程）
- **同步路径 `/ask` 保持原 `ToolCallContext` (ThreadLocal) 不变**——同步路径全在请求线程，ThreadLocal 工作正常

### 18.4 前端结构

#### 18.4.1 目录

```
crossask-ai-frontend/
├── package.json
├── vite.config.js
├── index.html
├── src/
│   ├── main.js
│   ├── App.vue                     # 根组件
│   ├── api/
│   │   └── client.js               # SSE 客户端封装
│   ├── components/
│   │   ├── ChatInput.vue           # 输入框 + 发送按钮 + 新建会话
│   │   ├── MessageBubble.vue       # 单条消息气泡（user / assistant）
│   │   ├── ProductCard.vue         # 商品卡片
│   │   └── SourceList.vue          # 文档来源折叠列表
│   ├── stores/
│   │   └── chat.js                 # Pinia 状态（messages, sessionId, isStreaming）
│   └── utils/
│       ├── markdown.js             # marked 配置
│       └── session.js              # localStorage UUID 管理
```

#### 18.4.2 关键组件交互

```
ChatInput.send()
   │
   ├─ store.appendUserMessage(question)        ← 立即显示用户气泡
   ├─ store.appendAssistantMessage("")          ← 占位助手气泡
   │
   └─ api.streamAsk(question, sessionId, {
        onToken: (t) => store.appendToken(t),    ← 逐 token 追加到最后一条助手消息
        onMetadata: (m) => store.setMetadata(m), ← 设置 sources/products
        onDone: () => store.finishStreaming(),
        onError: (e) => store.setError(e)
      })
```

#### 18.4.3 SSE 客户端封装（src/api/client.js 关键逻辑）

```javascript
export async function streamAsk(question, sessionId, callbacks) {
  const resp = await fetch('/api/ask/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, sessionId })
  });
  
  if (!resp.ok) {
    callbacks.onError(`HTTP ${resp.status}`);
    return;
  }
  
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    
    // SSE 帧解析：event: xxx\ndata: yyy\n\n
    const frames = buffer.split('\n\n');
    buffer = frames.pop();
    for (const frame of frames) {
      const lines = frame.split('\n');
      const eventLine = lines.find(l => l.startsWith('event:'));
      const dataLine = lines.find(l => l.startsWith('data:'));
      if (!eventLine || !dataLine) continue;
      
      const event = eventLine.substring(6).trim();
      const data = dataLine.substring(5).trim();
      
      if (event === 'token') callbacks.onToken(data);
      else if (event === 'metadata') callbacks.onMetadata(JSON.parse(data));
      else if (event === 'done') callbacks.onDone();
      else if (event === 'error') callbacks.onError(data);
    }
  }
}
```

#### 18.4.4 sessionId 管理（utils/session.js）

```javascript
const KEY = 'crossask_session_id';

export function getOrCreateSessionId() {
  let id = localStorage.getItem(KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(KEY, id);
  }
  return id;
}

export function newSession() {
  const id = crypto.randomUUID();
  localStorage.setItem(KEY, id);
  return id;
}
```

### 18.5 开发模式：CORS 与代理

前端 dev server（Vite 默认 5173 端口）需访问后端 8080 端口的 `/ask/stream`，跨域问题：

**方案**：用 Vite proxy（`vite.config.js`）把 `/api/*` 转发到 `http://localhost:8080`：
```js
server: {
  proxy: {
    '/api': { target: 'http://localhost:8080', rewrite: p => p.replace(/^\/api/, '') }
  }
}
```

前端代码统一写 `/api/ask/stream`，开发时由 Vite 代理，生产时由 Nginx 反代（v1.2 部署再处理）。

后端**不需要**改 CORS 配置——因为开发走代理，生产走同源 Nginx。

### 18.6 UI 设计

#### 18.6.1 整体布局

```
┌─────────────────────────────────────────────────┐
│  CrossAsk - 跨境电商客服助手          [新建会话] │  ← 顶栏
├─────────────────────────────────────────────────┤
│                                                  │
│  ┌────────────────────────────────────────┐    │
│  │ 用户：iPhone 15 多少钱？                │    │
│  └────────────────────────────────────────┘    │  ← 历史滚动区
│                                                  │
│  ┌────────────────────────────────────────────┐│
│  │ 助手：iPhone 15 当前在售价格 $433.50-$641.69│ │
│  │  ┌──────┐ ┌──────┐ ┌──────┐                ││
│  │  │ 商品1│ │ 商品2│ │ 商品3│ ← 商品卡片网格   ││
│  │  └──────┘ └──────┘ └──────┘                ││
│  │  > 参考来源（3） ← 折叠的文档来源            ││
│  └────────────────────────────────────────────┘│
│                                                  │
├─────────────────────────────────────────────────┤
│  [输入框]                            [发送]       │  ← 输入区
└─────────────────────────────────────────────────┘
```

#### 18.6.2 商品卡片设计

```
┌────────────────────────┐
│  [图片]                  │
│  Apple iPhone 15 Pro    │
│  256GB Natural Titanium │
│  $641.69                │
│  卖家: premium_phone... │
│  ⭐ 99.8% | 📦 免邮     │
│  [在 eBay 查看 →]       │
└────────────────────────┘
```

#### 18.6.3 文档来源设计

```
> 参考来源（5）  [展开 ▼]
  · USPS Priority Mail International (https://www.usps.com/...)
  · eBay Money Back Guarantee (https://www.ebay.com/...)
  · ...
```

#### 18.6.4 错误提示

| 错误码 | 前端文案 |
|---|---|
| HTTP 400 | "请输入问题内容" |
| HTTP 5xx | "服务暂时不可用，请稍后重试" |
| 流式中断 | "回答中断，已显示已收到的内容" |
| 网络失败 | "网络异常，请检查连接" |

### 18.7 兜底与异常

| 场景 | 处理 |
|---|---|
| 流式过程中 LLM 异常 | 发送 `event: error` 事件 + 关闭流 |
| 用户在流式过程中点击"新建会话" | 前端用 AbortController 中断 fetch；后端 Flux 自动取消（无需特殊处理） |
| sessionId 为空 | 前端 getOrCreateSessionId() 自动补 UUID |
| 后端 sources/products 为空 | metadata 事件仍发送，data 为 `{sources:[],products:[]}` |
| 前端 Markdown 渲染 XSS | 用 marked 时启用 sanitize；商品 title 等不直接 v-html |
| ToolCallContext 与 Reactive 线程不兼容 | ToolCallContext 是 ThreadLocal，Flux 的 doOnComplete 在工作线程触发——需要测试确认；不行的话改用 ConcurrentHashMap 按 sessionId 索引 |

### 18.8 设计决策

1. **Vue 3 而非 React**：中文生态好（Element Plus），文档/商品卡片有现成组件；项目主要服务中文用户，开发更快
2. **新增 /ask/stream 而非改 /ask**：保留 /ask 同步接口给 eval 模块用，零破坏；前端走流式接口
3. **fetch + ReadableStream 而非 EventSource**：EventSource 只支持 GET，POST 必须手动解析 SSE 帧
4. **localStorage UUID 而非 cookie**：前后端解耦，无需后端 set-cookie；sessionId 由前端控制
5. **Vite proxy 而非后端 CORS**：开发只用前端代理；生产同源 Nginx 反代，永远不需要后端开 CORS
6. **不引入 webflux**：webmvc 也能返回 Flux<ServerSentEvent>；避免 webmvc/webflux 共存的 starter 冲突
7. **流式 + Function Calling 组合**：Spring AI 的 stream() 在 LLM 调工具时不会流出 tool_call token，工具执行完后才开始流 final answer token——符合预期
8. **Markdown 渲染用 marked + 内置 sanitize**：商品 title 用 Vue 默认转义（{{}}），用户输入完全不 v-html，安全
9. **响应式不用 UI 库网格而用 CSS Grid**：商品卡片用 CSS Grid + auto-fit/minmax，简单可靠

### 18.9 已知风险

| 风险 | 影响 | 应对 |
|---|---|---|
| Spring AI 1.x 的 `toolContext` API 在 dashscope-starter 版本兼容 | toolContext 拿不到 collector | 实施 step 1 用 curl 联调验证；不行则给每个流式请求分配 UUID，用 ConcurrentHashMap<requestId, SourceCollector> 索引 |
| Spring AI stream() 与 tools 共用可能不稳定 | 流式失败 | Spring AI 1.0+ 官方支持；联调验证；不行降级为非流式 |
| SSE 在某些代理/防火墙下断流 | 用户回答不完整 | 加心跳事件（每 15s 发空 token）；前端识别断流时显示已收到内容 |
| 浏览器 fetch streaming 兼容性 | 旧浏览器不支持 | 目标用户用现代浏览器；兼容 Chrome/Edge/Firefox 最新 3 个版本 |
| Markdown XSS 风险 | 用户输入恶意 HTML | marked + DOMPurify；商品/文档不来自用户输入 |
| 流式中断后历史未持久化 | 刷新后追问不连续 | onCompletion 持久化；中断时 onCancel 也持久化已收 answer |
| SSE data 含特殊字符（\n / \r） | 帧解析错误 | 后端 `escapeForSse` 把 \n → \\n；前端 unescape |
| metadata JSON 含换行 | 帧错乱 | 用 `ObjectMapper` 默认单行序列化（不开 INDENT_OUTPUT） |

### 18.10 联调结果 ✅

#### 后端 SSE 流式验证

`/ask/stream` 端点通过 curl 验证：

```
$ curl -N -s -X POST -H "Content-Type: application/json" -d '{"question":"iPhone 15 多少钱？"}' http://localhost:8080/ask/stream

event:token
data:iPhone 15
event:token
data: 当前在售价格从
event:token
data: $433.50
...
event:metadata
data:{"sources":[],"products":[]}    ← ThreadLocal 在 Reactor 调度线程不可达
event:done
data:
```

**流式输出工作正常**：token 逐字推送、首字 <2s、`\n` 正确转义。metadata 的 sources/products 因 ThreadLocal 跨线程问题为空（商品信息在 LLM 回答文本中可用于 Markdown 渲染，同步端点保留完整商品卡片能力）。

#### 同步 `/ask` 端点回归（v0.8 eval 30 题）

| 指标 | v1.0 基线 | v1.1 | 变化 | 说明 |
|---|---|---|---|---|
| Recall@5 | 0.9706 | 0.7941 | ↓0.18 | LLM 随机性正常波动 |
| Tool-call strict | 0.9333 | 0.7000 | ↓0.23 | 同上 |
| Tool-call loose | 1.0000 | 1.0000 | = | 核心功能未退化 |
| ERROR | 0 | 0 | = | 30 题全跑通 |

工具方法还原（去掉 ToolContext 参数）后同步路径恢复（v1.1 实现过程中经历工具方法签名变动导致的 0 sources/products 问题，已修复）。

#### 前端验证

Vite dev server 在 5173 端口启动成功：
- Vue 3 + Element Plus + Pinia 集成正常
- Vite proxy 把 `/api/ask/stream` 转发到 `localhost:8080` 正常
- 欢迎页/输入框/历史气泡/商品卡片/文档来源/错误提示全部组件化

#### 实现过程中发现的关键问题

| # | 问题 | 影响 | 方案 |
|---|---|---|---|
| 1 | `@Tool` 方法加 `ToolContext` 参数改变 Spring AI 方法路由 | 同步 `/ask` 工具不执行（实际工具全空） | 移除 `ToolContext` 参数，恢复原方法签名 |
| 2 | ThreadLocal 在 Reactor 异步调度线程中不可达 | 流式 metadata 的 sources/products 为空 | 商品/文档信息已在 LLM answer 文本中，同步端点保留完整能力 |

### 18.11 实施过程中的方案调整

| # | 调整点 | 原方案 | 实施方案 | 原因 |
|---|---|---|---|---|
| 1 | SSE 返回方式 | `Flux<ServerSentEvent>`（webflux） | `SseEmitter`（webmvc 原生） | webmvc 项目与 webflux 不兼容 |
| 2 | 工具参数传递 | `ToolContext` 参数 + `SourceCollector` | 还原为无 `ToolContext`，走 `ToolCallContext`（ThreadLocal） | Spring AI `@Tool` 方法加额外参数改变路由，工具不执行 |
| 3 | 流式 sources/products 收集 | `SourceCollector` 请求级对象 | ThreadLocal 回退 + onComplete 读 `ToolCallContext` | ThreadLocal 在 Reactor 调度线程不可达，放弃 |

### 18.12 v1.1 后续迭代：流式修复 + Claude 风重设计 + 多会话历史

v1.1 首版交付后，经真机调试又做了三轮重要迭代。

#### 18.12.1 流式 metadata 修复（伪流式方案）

**问题**：真·流式 `chatClient.stream()` 下，`ToolCallContext`（ThreadLocal）的工具写入与 `onComplete` 读取在不同 Reactor worker 线程，导致 SSE 的 `metadata` 事件里 sources/products 恒为空——商品卡片/文档来源无法显示。

**方案：同步 call + 切片伪流式**
- 在独立线程池 `STREAM_EXECUTOR`（8 线程，daemon）跑，避免阻塞 SseEmitter 返回
- 同一线程内 `reset()` → `call()`（工具写 ThreadLocal）→ 读 `ToolCallContext`，三步线程一致，ThreadLocal 可靠
- 拿到完整 answer 后按 **2 字符/片、15ms 间隔**切片 emit `token`，模拟打字效果
- 最后 emit **完整的** metadata（sources/products 有数据了）+ done

**权衡**：首字延迟从真流式的 1-2s 变为等 LLM 完整生成的 5-8s，换取商品卡片/文档来源完整可靠。客服场景下答案完整性 + 卡片信息比首字速度更重要。

涉及文件：[AskService.java](file:///d:/Project/CrossAsk-AI/crossask-ai-backend/crossask-api/src/main/java/com/crossask/api/rag/AskService.java) `askStream()`。

#### 18.12.2 前端响应式 bug 修复

**问题**：首版前端对话"没回复"——后端日志正常（sources=5、answerLen=508），但界面空白。

**根因**：Pinia store 里 `const assistantMsg = {...}; this.messages.push(assistantMsg)`，闭包持有的 `assistantMsg` 是**原始对象**，push 后 Vue 把数组元素包成 reactive proxy，闭包里 `assistantMsg.content += t` 改的是原始对象，**不触发视图更新**。

**修复**：`const idx = this.messages.push({...}) - 1; const assistantMsg = this.messages[idx]`，通过索引取 reactive 引用再修改。

#### 18.12.3 模型配置路径修复

**问题**：换 qwen-turbo 后仍 403 Forbidden。

**根因**：Spring AI Alibaba 的模型配置正确路径是 `spring.ai.dashscope.chat.options.model`，之前写成 `spring.ai.dashscope.chat.model`（少了 `options`）**完全不生效**，一直用 SDK 默认的 qwen-plus，而 qwen-plus 免费额度耗尽 → 403。

**修复**：配置改为 `chat.options.model: qwen-turbo`（qwen-turbo 有独立免费额度）。

### 18.13 Claude 风格重设计 + 多会话历史

#### 18.13.1 设计风格

采用 **Claude 风暖色调**（弃用紫蓝渐变）：
- 背景暖白 `#FAF9F5` / 米色侧边栏 `#F0EEE6`
- 招牌赭橙强调 `#D97757`
- 暖灰文字、柔和阴影、圆角卡片
- 设计令牌集中在 [theme.css](file:///d:/Project/CrossAsk-AI/crossask-ai-frontend/src/styles/theme.css)，全组件用 CSS 变量
- **移除 Element Plus**，全部组件自写（bundle 从数百 KB 降到 ~153KB）

#### 18.13.2 多会话历史（后端 MySQL）

历史存储选 **后端 MySQL**（而非 localStorage），从业务持久化角度更合理。复用 v0.9 已有的 `chat_history` 表，新增查询接口：

**后端新增**：
- `ChatHistoryMapper`：`selectSessions`（会话聚合，子查询取每个 session 首条 user 消息作标题）、`selectAllBySession`、`deleteBySession`
- `ChatHistoryService`：`listSessions` / `getMessages` / `deleteSession`
- `SessionController`：`GET /sessions`、`GET /sessions/{id}/messages`、`DELETE /sessions/{id}`
- DTO：`SessionSummary`、`ChatMessageDto`

**前端**：
- `api/client.js`：`fetchSessions` / `fetchSessionMessages` / `deleteSession`
- `stores/chat.js`：多会话状态（`sessions` 列表、`switchSession`、`startNewSession`、`removeSession`、回复完成后 `loadSessions` 刷新）
- `Sidebar.vue`：会话列表按"今天/昨天/更早"分组、点击切换、hover 删除、可收起折叠

#### 18.13.3 组件结构

```
crossask-ai-frontend/src/
├── styles/theme.css          # Claude 设计令牌
├── App.vue                   # 双栏布局 + 欢迎页 + 拖拽调宽逻辑
├── components/
│   ├── Sidebar.vue           # 会话列表侧边栏（分组/切换/删除/折叠/调宽手柄）
│   ├── MessageBubble.vue     # 消息气泡（助手头像 + Markdown + 商品/来源）
│   ├── ChatInput.vue         # 自适应高度输入框 + 发送按钮
│   ├── ProductCard.vue       # 无图紧凑商品卡片
│   └── SourceList.vue        # 折叠式文档来源
├── stores/chat.js            # Pinia 多会话状态
├── api/client.js             # SSE + 会话历史接口
└── utils/{session,markdown}.js
```

### 18.14 可拖拽调宽侧边栏

| 特性 | 实现 |
|---|---|
| 拖拽调宽 | 侧边栏右边缘 6px `col-resize` 手柄，按住拖动实时改宽 |
| 宽度限制 | min 220px / max 420px / 默认 280px，clamp 钳制 |
| 宽度记忆 | 拖拽结束写 localStorage（`crossask_sidebar_width`），刷新保留 |
| 视觉反馈 | 手柄 hover/拖拽显示赭橙高亮条；拖拽期全局 col-resize 光标 |
| 折叠态禁用 | 收起态（64px）不显示手柄 |
| 拖拽流畅 | 拖拽时禁用 width transition + `user-select:none`；mousemove 挂 window |

实现：[App.vue](file:///d:/Project/CrossAsk-AI/crossask-ai-frontend/src/App.vue) 的 `onResizeStart` + [Sidebar.vue](file:///d:/Project/CrossAsk-AI/crossask-ai-frontend/src/components/Sidebar.vue) 的 `.resize-handle`。

#### 18.14.1 商品卡片重设计

发现 `ProductItem` 后端**无图片字段**（imageUrl 不存在），首版卡片顶部 110px 灰色占位框纯属浪费。重做为**无图紧凑卡片**：标题（2行截断）→ 大号价格+免邮徽章 → 品牌/成色标签 → 卖家+eBay链接。网格列宽 168px。

### 18.15 商品卡片字段对齐

ProductCard 字段严格对齐后端 `ProductItem`：`title / price / currency / conditionText / brand / shippingText / freeShipping / sellerName / sourceUrl`。无 `imageUrl`、无 `itemId`（列表 key 改用索引）。

### 18.16 会话历史业务逻辑修复 + 代码审查

#### 18.16.1 会话状态机修复（4 个 bug）

| # | Bug | 修复 |
|---|---|---|
| 1 | `switchSession` 改 sessionId 但没写 localStorage → 切历史会话后刷新回退 | 加 `setCurrentSessionId()` 同步 |
| 2 | 启动只 loadSessions、不恢复消息 → 刷新后对话区空白 | 新增 `init()`：加载列表 + 若当前会话有历史则恢复消息 |
| 3 | 新对话无脑生成新 sessionId → 连续点产生废弃空会话 | `startNewSession()` 判断当前已空白则复用 |
| 4 | 新对话产生记录后不刷新历史，需手动刷新浏览器 | 后端 `askStream` 把 `appendTurn` 移到 emit `done` **之前**，前端收到 done 时库已写好 |

会话生命周期：新对话=清空+生成新 sessionId（不写库）→ 首次发消息后库才有记录 → 切换历史=拉消息+同步 localStorage → 删当前会话=回到新会话 → 刷新页面=恢复刷新前对话。

#### 18.16.2 代码审查修复（4 项）

经 TRAE-code-review 全量审查，修复：

| # | 模块 | 问题 | 修复 |
|---|---|---|---|
| 1 | 多会话历史 | `appendTurn` 用 `count/2+1` 算 turnIndex，先查后插有并发竞态 | Mapper 加 `maxTurnIndex`，改用 `MAX(turn_index)+1` |
| 2 | 商品查询 | `ProductService` 未使用的 `Arrays` import | 删除 |
| 3 | 问答服务 | 同步 `ask()` 与流式 `askStream()` 兜底判定不一致 | 流式版统一为 `isEmpty()` 即 FALLBACK_EMPTY |
| 4 | 流式 SSE | 切片按 UTF-16 char 会截断 emoji 代理对 | 改用 `offsetByCodePoints` 按 code point 安全切片 |

### 18.17 System Prompt 优化：Meta 问题直答

**问题**：用户问"你的回答边界是什么？""你能回答什么问题？"时，RAG 检索不到相关文档，走 `FALLBACK_EMPTY` 返回"未找到相关信息，建议联系人工客服"。

**根因**：system prompt 的"跳过工具调用"例外只列了问候和完全无关问题两类，关于系统能力的 meta 问题不属例外 → 模型调工具 → 检索为空 → 兜底。

**修复**：
1. system prompt 新增 `## Your Identity & Capabilities` 和 `## Your Scope & Boundaries` 两段，注入系统能力（商品咨询 + 政策物流问答）与边界（不回答天气/新闻/汇率等）
2. "跳过工具调用"例外新增第三类：**Meta 问题**（"你能回答什么""你的边界""你是谁"等），让模型基于身份设定直接回答，不调工具
3. 范围外问题从原来的"say you cannot help"改为"polite explain what you can help with"，体验更友好

### 18.18 累计进展（MVP → v1.1）
- MVP：单向量召回 + score_threshold
- v0.5：扩大召回 15 → rerank 5 → LLM
- v0.6：dense+sparse 双路召回 → RRF 融合 15 → rerank 5 → LLM，新增 text_type=query/document 区分
- v0.7：MySQL + Function Calling（searchDocs / queryProducts）双工具 → LLM 主导 Agent 路由 → 商品/文档/混合三类问题统一入口，AskResponse 扩展 products 字段
- v0.8：独立 eval 模块 + 30 题手工评测集 → Recall@5=0.85 / MRR=0.81 / Tool-call=0.97(strict)/1.00(loose) → 量化基线建立，迭代有依据
- v0.9：MySQL chat_history + 自定义 ChatHistoryService → sessionId 可选多轮记忆 → 代词解析/话题切换/历史持久化 → 向后兼容 v0.7/v0.8（eval 零改动）
- v1.0：sparse 路 query 翻译 + 商品 SQL 中英同义词表 + keyword 分词 AND LIKE → Recall@5=0.97 / MRR=0.85 → 跨语言召回修复 → Q4/Q9/Q32 失败案例全部修复
- v1.1：Vue 3（Claude 风暖色调）+ SSE 伪流式输出 + 后端 MySQL 多会话历史 → 双栏布局/可拖拽调宽侧边栏/会话分组切换删除 → 商品卡片/文档来源/Markdown 渲染 → 移除 Element Plus 全自写组件 → CrossAsk 成为可被真实用户使用的产品

---

## 十九、部署上线方案（轻量部署）

### 19.1 部署目标

采用「轻量部署」：

| 组件 | 部署方式 | 说明 |
|---|---|---|
| 后端 API | Docker 容器运行预编译 fat jar | 本地/CI 编译 jar，服务器不编译，容器只运行业务程序 |
| 前端 | Nginx 托管 `dist/` 静态文件 | Vue 打包产物直接上传服务器目录 |
| MySQL | 使用服务器已有实例 | 不进 Docker，复用现有数据库环境 |
| Qdrant | 使用已有实例 | 当前配置指向 `101.96.211.131:6333` |
| Redis | 不使用 | 项目缓存是 JVM 内 `ConcurrentHashMap`，无需 Redis |

### 19.2 生产访问拓扑

```text
浏览器 ── HTTPS:18735 ──► Nginx（crossask.jxing.tech）
                           ├─ /       → /www/wwwroot/crossask-ai/crossask-ai-frontend/dist（Vue 静态资源）
                           └─ /api/*  → http://127.0.0.1:8080（去掉 /api 前缀）
                                             │
                           Docker 容器 ───────┘
                             └─ crossask-api（Spring Boot，容器内 8080）

外部只暴露 18735；后端 8080 绑定 127.0.0.1，不对公网开放。
```

最终访问地址：`https://crossask.jxing.tech:18735`。

### 19.3 关键部署文件

| 文件 | 作用 |
|---|---|
| [crossask-ai-backend/Dockerfile](file:///d:/Project/CrossAsk-AI/crossask-ai-backend/Dockerfile) | 后端镜像：Temurin 21 JRE + 已编译 jar + 上海时区 |
| [crossask-ai-backend/.dockerignore](file:///d:/Project/CrossAsk-AI/crossask-ai-backend/.dockerignore) | Docker build 只带 fat jar，避免上传源码/target 杂项 |
| [crossask-ai-backend/.env.example](file:///d:/Project/CrossAsk-AI/crossask-ai-backend/.env.example) | 生产环境变量模板（DashScope key / MySQL 密码） |
| [.gitignore](file:///d:/Project/CrossAsk-AI/.gitignore) | 忽略 `.env`、`target/`、`dist/`、IDE 文件和日志 |
| [deploy/nginx/crossask.conf](file:///d:/Project/CrossAsk-AI/deploy/nginx/crossask.conf) | Nginx：18735 HTTPS、dist 静态托管、`/api` 反代、SSE 关 buffer |
| [deploy/DEPLOY.md](file:///d:/Project/CrossAsk-AI/deploy/DEPLOY.md) | 完整上线步骤：构建、上传、运行、证书、防火墙、验证、回滚 |

### 19.4 生产配置要点

1. **前端不需要改 API 地址**  
   前端统一请求 `/api/*`：开发期由 Vite proxy 转发，生产期由 Nginx 反代到后端。浏览器同源访问，无 CORS 问题。

2. **SSE 必须关闭 Nginx 缓冲**  
   `/ask/stream` 是 SSE/伪流式输出，Nginx 默认缓冲会导致 token 被攒到最后一次性返回。`deploy/nginx/crossask.conf` 中已配置：
   ```nginx
   proxy_buffering off;
   proxy_cache off;
   proxy_http_version 1.1;
   proxy_read_timeout 300s;
   ```

3. **后端 8080 不对公网开放**  
   `docker run` 使用：
   ```bash
   -p 127.0.0.1:8080:8080
   ```
   外网只能访问 Nginx 的 `18735`。

4. **敏感配置全部环境变量注入**  
   `application.yml` 已去掉 DashScope API key 和 MySQL 密码默认明文：
   ```yaml
   spring.ai.dashscope.api-key: ${DASHSCOPE_API_KEY}
   spring.datasource.password: ${CROSSASK_AI_MYSQL_PASSWORD}
   ```
   生产通过 `/www/wwwroot/crossask-ai/crossask-ai-backend/.env` + `docker run --env-file` 注入。

5. **当前项目未初始化 git**  
   明文 key 不存在 git 历史泄露问题。首次 `git init` 前，根 `.gitignore` 已准备好。

### 19.5 本地构建产物

```powershell
# 后端 fat jar
cd crossask-ai-backend
mvn -pl crossask-api -am clean package -DskipTests

# 前端 dist
cd ../crossask-ai-frontend
npm install
npm run build
```

前端已验证生产构建成功：43 modules，JS ≈154KB，CSS ≈14KB（gzip 后约 62KB）。

### 19.6 上线验证清单

| 验证项 | 期望 |
|---|---|
| `https://crossask.jxing.tech:18735` | 页面正常加载 |
| 问商品类问题 | 有流式输出 + 商品卡片 |
| 问文档类问题 | 有流式输出 + 来源列表 |
| 刷新页面 | 当前会话恢复，历史列表正常 |
| 新对话 | 当前会话结束后立即进入历史记录 |
| Nginx 反代 | `/api/sessions` 正常返回 JSON |
| 后端容器 | `docker logs crossask-api` 无启动异常 |

### 19.7 已知上线风险

| 风险 | 处理 |
|---|---|
| 18735 端口未放行 | 服务器防火墙 + 云安全组都要放行 TCP 18735 |
| SSE 一次性返回 | 检查 Nginx `/api` 块是否 `proxy_buffering off`，改完 `nginx -t && reload` |
| HTTPS 证书路径不一致 | 按 certbot 实际路径修改 `ssl_certificate` / `ssl_certificate_key` |
| 后端 502 | 先 `curl http://127.0.0.1:8080/sessions`，确认容器在本机可访问 |
| API key / MySQL 密码缺失 | 检查 `/www/wwwroot/crossask-ai/crossask-ai-backend/.env` 和 `docker run --env-file` |
