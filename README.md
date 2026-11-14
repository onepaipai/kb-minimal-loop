# Rag_Java —— AI 知识库最小闭环

知识库演示：上传文档（.txt/.pdf/.doc/.docx/.md）→ 递归切块 → DashScope 向量化 → 存入 Neon Postgres（pgvector）→ 提问时检索最相似片段 → DeepSeek 带 [1][2] 引用作答。PDF/DOC/DOCX/MD 由 LangChain4j 的 `ApacheTikaDocumentParser` 解析（内嵌 Tika 3.2.3）。

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
curl -X POST http://localhost:8080/api/ingest -F "file=@docs/产品手册.pdf"
```

返回：

```json
{"file":"请假制度.txt","chunks":1}
```

支持后缀：`.txt` 直读 UTF-8；`.pdf/.doc/.docx/.md` 走 Tika 解析（扩展名不区分大小写）。扫描版 PDF（纯图片无文字层）返回 400——Tika 不含 OCR，OCR 属后续能力。

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

入参还支持三个可选调优项：`sessionId`（多轮追问，同会话保留问答历史）、`maxResults`、`minScore`：

```bash
# 多轮追问：第二轮能理解“那 1 天以内的”指代上一轮的请假话题
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？","sessionId":"demo-1"}'
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"那1天以内的呢？","sessionId":"demo-1"}'
# 调整检索参数
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"差旅报销多久内提交？","maxResults":3,"minScore":0.6}'
```

会话记忆暂存在服务内存中（每个会话最多保留 20 条消息，约 10 轮），服务重启后会话即失效；生产环境应换持久化存储。

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

返回（`deletedChunks` 为实际删除的块数，按删除前后统计差值计算）：

```json
{"source":"报销制度.txt","deleted":true,"deletedChunks":1}
```

同名文件重复上传时自动先删旧块再入库（整体替换），清单块数不会翻倍。

## 验收清单

1. `mvn clean compile` / `mvn clean package -DskipTests` 通过
2. 设好 5 个环境变量后 `mvn clean spring-boot:run` 启动成功（schema.sql 自动执行）
3. 上传 `docs/请假制度.txt` 返回 `chunks > 0`；`GET /api/documents` 能列出该文件
4. 问“请假超过3天找谁批？”回答“部门经理+HR 共同审批”并带 `[1]` 引用；问“食堂几点开”回答“资料里没找到”
5. 多轮：同 sessionId 先问“请假超过3天找谁批？”再问“那1天以内的呢？”，第二轮能按资料答“向直属主管口头说明即可”且标注引用
6. 定向检索：`sources:["报销制度.txt"]` 问“高铁能报多少钱？”能答“二等座实报实销”
7. 删除：`DELETE /api/document?source=报销制度.txt` 返回的 `deletedChunks` 与清单中该文档块数一致，再查清单该项消失
8. 校验：question 为空、maxResults=0、minScore=2 均返回 400
9. 重复上传：同一文件传两次，清单块数不翻倍（同名整体替换）
10. 接口异常返回 Spring 默认 JSON 错误结构（非堆栈文本）
11. 多格式：上传 .pdf 与 .docx 后 `GET /api/documents` 能列出且块数 >0，各自内容能检索回答并带引用
12. 扫描版 PDF（纯图片无文字层）上传返回 400“解析后无有效文本内容”，不静默存 0 块
13. 上传 .exe 等非白名单后缀返回 400

## 注意

- 上传的 .txt 必须 UTF-8 编码，否则中文会乱码
- 表结构由 `schema.sql` 创建，向量库配置为 `createTable=false`、`skipCreateVectorExtension=true`，二者不重复建表
- 元数据存储模式为 `COMBINED_JSONB`，对应 `schema.sql` 中的 `metadata JSONB` 列
- 项目不包含前端、权限过滤；支持 .txt/.pdf/.doc/.docx/.md，同名文档上传为整体替换，不做内容级去重
- 文档解析仅用 `langchain4j-document-parser-apache-tika`（内嵌 Tika 3.2.3），严禁额外引入 Tika 4.x 或 tika-bom——Tika 4 改了 `Parser.parse` 签名，会编译通过但运行时 `NoSuchMethodError`；该包体积大，打包与首次解析偏慢属正常
- Tika 不含 OCR：扫描版 PDF（纯图片）解析不出文字，返回 400 而非静默入库；OCR 是下一步的事
- 同名文件替换为“先删旧块再入库”，**非原子**：入库中途失败（向量接口报错、断库）会导致旧文档丢失，接入正式数据前必须改为先入后删或加事务/备份
- 接口无鉴权（演示定位）：正式使用需增加鉴权与限流，sessionId 按登录用户隔离并换集中存储
- 项目按约定不含单元测试与 CI，改动后以 `mvn clean package -DskipTests` 与接口实测验证
- 上传超过 5MB 会被拒绝（multipart 上限配置于 application.yml）
