# AI 知识库第一步：最小闭环（1 份文档 → 提问 → 带引用回答）

目标：今天 1–2 小时跑通一条线，别的先不做——不做权限、不做前端、不做 PDF 解析。

## 这是远程版：模型调 API，数据库用云端，不用 Docker

Windows 没法用 Docker，所以本地什么都不起，只跑 Java 代码：

- 聊天模型：DeepSeek `deepseek-flash`，base-url `https://api.deepseek.com`，OpenAI 兼容
  （2026-11-10 对 DeepSeek 官网核实：当前模型名是 `deepseek-flash`，旧名 `deepseek-chat` 已下线，别用）
- 向量模型：阿里云百炼 DashScope `text-embedding-v4`，默认 1024 维，
  base-url `https://dashscope.aliyuncs.com/compatible-mode/v1`（北京地域），OpenAI 兼容
- 向量库：云端 Postgres + pgvector，默认用 Neon 免费库，表 `chunk_embedding`，
  `vector(1024)` 必须和向量模型维数一致

**DeepSeek 只能做聊天，不能做向量——它没有公开的 embedding 接口。**
所以别把 embedding 也指到 DeepSeek，那样 ingest 时会直接报错。

## 先准备：两个 Key + 一个云数据库（都不写进代码）

### 1. 云数据库：Neon 免费版

1. 去 neon.tech 注册，New Project 建一个库，数据库名默认 `neondb` 就行。
   免费版 0.5 GB，pgvector 已包含，不用装。
2. 在 Neon 的 SQL Editor 里先跑一次：
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
   启动时 schema.sql 也会跑这行，提前跑一次是先把权限问题暴露出来。
3. 在 Neon 项目页抄连接信息：host、数据库名、用户名、密码。
   Neon 默认给的是 `postgres://user:pass@host/neondb?sslmode=require`，
   改成 JDBC 格式填到环境变量：`jdbc:postgresql://host/neondb?sslmode=require`。
   一定用直连端点（不带 `-pooler` 的那个），别用 pooler 端点。

备选也是同一个道理：Supabase 免费库也行，pgvector 默认已开启，
Dashboard → Database → Extensions 里确认 `vector` 已启用，
连接用 session 模式 5432 端口，别用 6543 的 transaction pooler。

### 2. 两个模型 Key

```bash
# DeepSeek：https://platform.deepseek.com 申请
export DEEPSEEK_API_KEY=sk-你的key

# 阿里云百炼 DashScope：百炼控制台申请 API Key
export DASHSCOPE_API_KEY=sk-你的key

# 云数据库（Neon）：按上面第 3 步填
export DB_URL=jdbc:postgresql://你的host/neondb?sslmode=require
export DB_USER=你的用户名
export DB_PASSWORD=你的密码
```

IDEA 里跑的话，在 Run Configuration 的 Environment variables 里加上面 5 行也行。
Key 和数据库密码只放环境变量，不进 git、不进 application.yml。

## 文件清单（10 个文件，没有 docker-compose.yml）

| 文件 | 干什么 |
|---|---|
| `pom.xml` | Spring Boot 3.3.5 + Java 17 + LangChain4j BOM 1.22.0（核心 1.22.0，pgvector 模块 1.22.0-beta32；LangChain4j 1.x 最低要求 JDK 17） |
| `src/main/resources/application.yml` | 云数据库地址（环境变量 DB_URL/DB_USER/DB_PASSWORD）、DeepSeek / DashScope 地址和模型名、切块 500/100 |
| `src/main/resources/schema.sql` | 建 `chunk_embedding` 表 + HNSW 索引，`vector(1024)`，第一行开 pgvector 扩展 |
| `src/main/resources/docs/请假制度.txt` | 测试文档，3 条规则 |
| `KbApplication.java` | 启动类 |
| `config/LangChain4jConfig.java` | 4 个 Bean：向量模型 / 聊天模型 / 切块器 / 向量库（pgvector 没变） |
| `service/IngestService.java` | 建库：切块 → 向量化 → 存 pgvector |
| `service/RagService.java` | 问答：向量化问题 → 找最像的 5 块（minScore 0.55）→ 照着资料回答 |
| `controller/RagController.java` | `POST /api/ingest`、`POST /api/ask` |

三处 1024 必须对齐：`LangChain4jConfig` 的 `.dimension(1024)`、
`OpenAiEmbeddingModel` 的 `.dimensions(1024)`、schema.sql 的 `vector(1024)`，
向量模型是 `text-embedding-v4`（默认 1024 维）。
换向量模型要三处一起改 + 重建表 + 重新 ingest。

代码结构参考了 2026-10-17 一篇用 Spring Boot + LangChain4j 1.22 + pgvector 跑通的
RAG 实测例子，pom 已把版本对齐到核心 1.22.0 / pgvector 1.22.0-beta32。
但本机没有 JDK / Docker / Maven，**这份代码仍没有在本机编译验证过**——
在 IDEA 里跑 `mvn clean compile`，把第一个报错原样发回来，我按报错修，不要自己猜着改。

## 三步跑起来（不用 docker compose）

```bash
# 0. 先设好 5 个环境变量（见上：2 个 Key + DB_URL/DB_USER/DB_PASSWORD）
# 1. 编译启动（在 kb-minimal-loop 目录下）
mvn clean spring-boot:run

# 2. 上传 1 份文档
curl -F file=@src/main/resources/docs/请假制度.txt http://localhost:8080/api/ingest
# 预期：{"file":"请假制度.txt","chunks":1} 左右（文档短，只切出 1 块是正常的）

# 3. 提问
curl -H "Content-Type: application/json" \
  -d '{"question":"请假超过3天找谁批？"}' \
  http://localhost:8080/api/ask
```

## 验收标准（跑通就是这样）

1. 文档里有的能答对：请假超过 3 天 → 部门经理 + HR 共同审批，并标出来源 `请假制度.txt`。
2. 文档里没有的说不知道：问“公司食堂几点开？”→ 应回答“资料里没找到”，不瞎编。
   （如果它硬编了，先看返回的 `sources` 里有没有相关块——没有就是提示词/模型问题，不是检索问题。）

## 如果卡住，先查这三处

1. 数据库连不上 / 表不存在：先查 DB_URL 是不是直连端点、sslmode 有没有带，
   再查 Neon SQL Editor 里 `CREATE EXTENSION IF NOT EXISTS vector;` 跑过没有，
   表是启动时由 schema.sql 建的（`spring.sql.init.mode=always`）。
2. Key 报错（401 / invalid api key）：先查环境变量有没有设对，DeepSeek 和 DashScope 是两个不同的 Key，别混了。
3. 向量维数报错：看是不是换了向量模型却没改三处 `1024`（见上）。
4. `mvn clean compile` 的第一个报错原样发回来。

## 第一步之后才做

- `.txt` → PDF / Word（Apache Tika）
- 1 个文件 → 一堆文件 + 去重 / 增量更新
- 再之后：企业权限过滤、代码按类/方法切块（第 3–6 周的事，现在不用碰）
