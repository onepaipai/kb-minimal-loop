# Rag_Java —— AI 知识库最小闭环

单文档知识库演示：上传 .txt 文档 → 递归切块 → DashScope 向量化 → 存入 Neon Postgres（pgvector）→ 提问时检索最相似片段 → DeepSeek 带 [1][2] 引用作答。

## 技术栈

- Java 17 / Spring Boot 3.3.5 / Maven
- LangChain4j 1.22.0（`langchain4j-pgvector` 显式 pin `1.22.0-beta32`，`langchain4j-open-ai` 同版本）
- 聊天模型：DeepSeek `deepseek-flash`（OpenAI 兼容接口 `https://api.deepseek.com`）
- 向量模型：阿里 DashScope `text-embedding-v4`，1024 维（OpenAI 兼容接口 `https://dashscope.aliyuncs.com/compatible-mode/v1`）
- 向量库：Neon 免费云 Postgres + pgvector（**直连端点，不要 -pooler 端点**）

## 1024 维三处一致（改维度必须同步改三处）

1. `LangChain4jConfig#embeddingModel` → `.dimensions(1024)`
2. `LangChain4jConfig#embeddingStore` → `.dimension(1024)`
3. `src/main/resources/schema.sql` → `embedding vector(1024)`

## 前置条件

- JDK 17、Maven 3.9+（本机 Maven 默认 Java 11 时，启动前需 `export JAVA_HOME` 指向 JDK 17）
- Neon 建好项目和数据库，连接串使用直连端点
- DeepSeek 平台 API Key、阿里云 DashScope API Key

## 环境变量（5 个，全部必填，不写死进代码）

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | `sk-xxx` |
| `DASHSCOPE_API_KEY` | DashScope API Key | `sk-xxx` |
| `DB_URL` | Neon JDBC 直连地址 | `jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` |
| `DB_USER` | 数据库用户名 | `neondb_owner` |
| `DB_PASSWORD` | 数据库密码 | `******` |

## 启动

Git Bash：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx
export DB_URL='jdbc:postgresql://ep-xxx.xxx.aws.neon.tech/neondb?sslmode=require'
export DB_USER=neondb_owner
export DB_PASSWORD=xxx
export JAVA_HOME=/d/Java/Java17
mvn clean spring-boot:run
```

Windows CMD：

```bat
set DEEPSEEK_API_KEY=sk-xxx
set DASHSCOPE_API_KEY=sk-xxx
set DB_URL=jdbc:postgresql://ep-xxx.xxx.aws.neon.tech/neondb?sslmode=require
set DB_USER=neondb_owner
set DB_PASSWORD=xxx
mvn clean spring-boot:run
```

启动时 Spring 自动执行 `schema.sql`（建 extension、建表、建 HNSW 索引，全部 `IF NOT EXISTS`，可重复执行）。

## 接口

### POST /api/ingest —— 上传 .txt 入库

```bash
curl -X POST http://localhost:8080/api/ingest -F "file=@docs/请假制度.txt"
```

返回：

```json
{"file":"请假制度.txt","chunks":1}
```

### POST /api/ask —— 检索问答

```bash
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"食堂几点开"}'
```

返回：

```json
{"answer":"……[1]","sources":["请假制度.txt"]}
```

`sources` 第 i 个元素对应回答中的 `[i]` 引用编号；`hits` 同时给出每条引用的相关度分数（0~1）与命中的原文块，便于调整 `minScore`。入参可选 `sources` 数组，只在指定文档内检索：

```bash
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"高铁能报多少钱？","sources":["报销制度.txt"]}'
```

### GET /api/documents —— 已入库文档清单

```bash
curl http://localhost:8080/api/documents
```

返回：

```json
{"documents":[{"source":"请假制度.txt","chunks":1},{"source":"报销制度.txt","chunks":1}]}
```

### DELETE /api/document?source=xxx —— 删除指定文档

```bash
curl -X DELETE "http://localhost:8080/api/document?source=报销制度.txt"
```

返回：

```json
{"source":"报销制度.txt","deleted":true}
```

注意：重复上传同一文件会追加重复块，本实现不做自动去重；更新文档内容前请先删除旧文档再重新上传。

## 验收清单

1. `mvn clean compile` 通过
2. 设好 5 个环境变量后 `mvn clean spring-boot:run` 启动成功
3. curl 上传 `docs/请假制度.txt` 返回 `chunks > 0`
4. 问“请假超过3天找谁批？”回答“部门经理+HR 共同审批”并带 `[1]` 引用
5. 问“食堂几点开”回答“资料里没找到”

## 注意

- 上传的 .txt 必须 UTF-8 编码，否则中文会乱码
- 表结构由 `schema.sql` 创建，向量库配置为 `createTable=false`、`skipCreateVectorExtension=true`，二者不重复建表
- 元数据存储模式为 `COMBINED_JSONB`，对应 `schema.sql` 中的 `metadata JSONB` 列
- 项目不包含前端、权限过滤、PDF/Word 解析、去重/增量，仅 .txt
