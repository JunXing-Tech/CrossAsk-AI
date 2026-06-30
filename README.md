# CrossAsk AI

CrossAsk AI 是一个面向**跨境电商客服场景**的智能问答系统，聚焦 **eBay 商品咨询** 与 **eBay / USPS 政策物流问答**。它不是通用聊天机器人，而是把商品数据库、政策文档库和大模型工具调用结合起来，模拟真实跨境电商客服的问答流程。

用户可以像咨询客服一样提问：商品多少钱、是否免邮、怎么退货、国际物流多久、某个商品是否适合购买等。系统会自动判断应该查询商品库、检索政策文档，还是两者一起使用，并返回自然语言答案、商品卡片和文档来源。

## 项目解决的问题

跨境电商客服通常需要同时处理两类信息：

| 信息类型 | 典型问题 | 数据来源 | 处理方式 |
|---|---|---|---|
| 商品信息 | 价格、品牌、成色、卖家、运费、商品链接 | MySQL 商品库 | SQL 精确查询 |
| 政策/物流信息 | 退货规则、国际运输、关税、禁运、USPS 时效 | eBay / USPS 文档 | RAG 检索问答 |
| 混合问题 | “我想退掉买的 iPhone 15，该怎么操作？” | 商品库 + 文档库 | Function Calling 多工具协同 |

CrossAsk 的核心目标是：**用一个自然语言入口统一商品查询、政策问答和多轮客服咨询**。

## 典型业务场景

### 商品售前咨询

```text
iPhone 15 现在多少钱？
有 200 美元以内的耳机吗？
Apple 品牌的免邮商品有哪些？
```

系统查询商品库，返回价格、成色、卖家、运费和 eBay 链接，并在前端展示商品卡片。

### 平台政策与物流问答

```text
eBay 上买的商品怎么申请退货？
USPS Priority Mail International 到中国要多久？
国际包裹需要交关税吗？
哪些物品禁止国际寄送？
```

系统检索 eBay / USPS 文档，生成回答并展示来源，避免无依据回答。

### 商品 + 政策混合咨询

```text
我想退掉买的 iPhone 15，该怎么操作？
这个 AirPods 可以退货吗？
免邮的耳机如果不满意怎么退？
```

系统会同时调用商品查询工具和文档检索工具，综合商品信息与平台规则后回答。

### 多轮追问

```text
用户：iPhone 15 多少钱？
助手：这里有几款 iPhone 15...
用户：256GB 的呢？
助手：你刚才问的是 iPhone 15，256GB 版本有...
用户：那它怎么退货？
助手：根据 eBay 退货规则...
```

会话历史持久化到 MySQL，前端支持历史会话列表、切换、删除和新建对话。

## 项目边界

CrossAsk 只回答当前知识库覆盖的问题：

- eBay 商品数据
- eBay 帮助文档
- USPS 物流说明
- 已接入的商品库信息

对于天气、新闻、汇率、其他购物平台、未收录商品等问题，系统会说明资料不足或拒答，避免编造。

## 核心能力

- **商品查询**：查询标题、价格、品牌、成色、卖家、运费和链接
- **政策问答**：基于 eBay / USPS 文档进行 RAG 问答
- **混合问题处理**：商品库 + 文档库联合回答
- **Function Calling**：LLM 自动选择 `searchDocs` / `queryProducts` 工具
- **混合检索**：Dense + Sparse 双路召回，Qdrant RRF 融合，DashScope rerank 精排
- **跨语言优化**：中文 query 翻译优化英文文档召回，商品 SQL 支持中英同义词扩展
- **多轮记忆**：MySQL 持久化会话历史
- **流式回答**：SSE 输出回答片段，并保留商品和来源 metadata
- **前端会话管理**：历史会话、切换、删除、新建、可拖拽侧边栏
- **可视化来源**：商品卡片 + 文档来源列表

## 系统架构

```text
用户浏览器
   │
   ▼
Vue 3 前端（Nginx 托管 dist）
   │  /api/*
   ▼
Spring Boot API（Docker 容器）
   ├─ ChatClient / DashScope LLM
   ├─ RAG 检索链路
   │   ├─ Dense embedding
   │   ├─ Sparse embedding
   │   ├─ Qdrant hybrid search
   │   └─ DashScope rerank
   ├─ Product 查询工具
   │   └─ MySQL products / product_synonyms
   └─ Chat history
       └─ MySQL chat_history
```

## 主要业务流程

### 文档问答

```text
用户问题
  → Dense + Sparse embedding
  → Qdrant 混合检索
  → DashScope rerank 精排
  → 取 Top 文档片段
  → LLM 生成答案
  → 返回 answer + sources
```

### 商品查询

```text
用户问题
  → LLM 判断需要查商品
  → queryProducts 工具
  → 商品关键词中英同义词扩展
  → MySQL 查询 products
  → 返回 ProductItem 列表
  → 前端渲染商品卡片
```

### 混合咨询

```text
用户问题
  → LLM 同时调用 queryProducts + searchDocs
  → 商品结果 + 政策文档结果
  → LLM 综合回答
  → 前端展示答案、商品卡片、文档来源
```

## 技术栈

### 后端

- Java 21
- Spring Boot
- Spring AI Alibaba / DashScope
- Qdrant
- MySQL 5.7+
- MyBatis-Plus
- Maven
- Docker

### 前端

- Vue 3
- Vite
- Pinia
- marked + DOMPurify
- 原生 CSS（Claude 风格暖色设计）
- Nginx 静态托管

## 目录结构

```text
CrossAsk-AI/
├── crossask-ai-backend/
│   ├── crossask-api/          # 在线问答服务
│   ├── crossask-common/       # 通用模型、Mapper、配置
│   ├── crossask-ingestion/    # 文档/商品入库任务
│   ├── crossask-eval/         # 评测模块
│   ├── db-init/               # 数据库初始化 SQL
│   ├── Dockerfile             # 后端镜像构建文件
│   └── .env.example           # 生产环境变量模板
├── crossask-ai-frontend/
│   ├── src/                   # Vue 前端源码
│   └── dist/                  # 前端生产构建产物
├── deploy/
│   ├── DEPLOY.md              # 详细部署文档
│   └── nginx/crossask.conf    # Nginx 配置
├── Agent.md                   # 项目演进与设计记录
└── README.md
```

## 本地开发

### 1. 准备环境变量

后端不在配置文件中写明文密钥，本地运行前需要设置：

```powershell
$env:DASHSCOPE_API_KEY="sk-你的DashScopeKey"
$env:MYSQL_USER="crossask_ai"
$env:CROSSASK_AI_MYSQL_PASSWORD="你的MySQL密码"
```

### 2. 启动后端

```powershell
cd crossask-ai-backend
mvn -pl crossask-api -am spring-boot:run
```

默认地址：

```text
http://localhost:8080
```

### 3. 启动前端

```powershell
cd crossask-ai-frontend
npm install
npm run dev
```

开发地址：

```text
http://localhost:5173
```

开发期 Vite 会把 `/api/*` 代理到后端 `localhost:8080`。

## 生产构建

### 后端 fat jar

```powershell
cd crossask-ai-backend
mvn -pl crossask-api -am clean package -DskipTests
```

产物：

```text
crossask-ai-backend/crossask-api/target/crossask-api-0.1.0-SNAPSHOT.jar
```

### 前端 dist

```powershell
cd crossask-ai-frontend
npm install
npm run build
```

产物：

```text
crossask-ai-frontend/dist/
```

## 部署说明

推荐轻量部署：

- 后端：Docker 容器运行预编译 jar
- 前端：Nginx 托管 `dist/`
- MySQL / Qdrant：使用服务器已有实例
- 外部访问：`https://crossask.jxing.tech:18735`

服务器目录：

```text
/www/wwwroot/crossask-ai/
├── crossask-ai-backend/
│   ├── crossask-api-1.1.tar
│   └── .env
└── crossask-ai-frontend/
    └── dist/
```

完整部署步骤见：

```text
deploy/DEPLOY.md
```

## 重要接口

### 同步问答

```http
POST /ask
Content-Type: application/json

{
  "question": "iPhone 15 多少钱？",
  "sessionId": "optional-session-id"
}
```

### 流式问答

```http
POST /ask/stream
Content-Type: application/json
```

SSE 事件：

| 事件 | 含义 |
|---|---|
| `token` | 回答文本片段 |
| `metadata` | sources / products |
| `done` | 流结束 |
| `error` | 错误 |

### 会话历史

```http
GET /sessions
GET /sessions/{sessionId}/messages
DELETE /sessions/{sessionId}
```

## 数据库表

- `products`：商品数据
- `product_synonyms`：商品中英同义词
- `chat_history`：多轮会话历史

SQL 初始化文件位于：

```text
crossask-ai-backend/db-init/
```

## 评测

`crossask-eval` 用于批量测试 Recall@5、MRR、Tool-call accuracy 等指标。

```powershell
cd crossask-ai-backend
mvn -pl crossask-eval spring-boot:run
```

## 安全说明

- 不要把 `.env` 提交到 Git
- 不要在 `application.yml` 中写明文 API key 或数据库密码
- 生产环境通过环境变量注入：
  - `DASHSCOPE_API_KEY`
  - `MYSQL_USER`
  - `CROSSASK_AI_MYSQL_PASSWORD`
- 后端 8080 建议只绑定 `127.0.0.1`，由 Nginx 反代访问
- SSE 反代必须关闭 Nginx buffering，否则流式输出会失效

## 当前状态

- v1.1：前端 Claude 风格界面、多会话历史、SSE 伪流式、商品卡片、来源展示已完成
- 部署方案：Docker 后端 + Nginx dist + HTTPS 特别端口 18735 已准备
- 项目尚未初始化 Git；根 `.gitignore` 已准备好
