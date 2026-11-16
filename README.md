# kb-minimal-loop —— AI 知识库最小闭环

上传文档（.txt/.pdf/.doc/.docx/.md/.java）→ 递归切块 → DashScope 向量化 → 存入 Neon Postgres（pgvector）→ 提问时检索最相似片段 → DeepSeek 带 `[1][2]` 引用作答；另有流式问答（SSE）与 Java 单文件代码知识库。

## 技术栈

- Java 17 / Spring Boot 3.3.5 / Maven
- LangChain4j 1.22.0（`langchain4j-pgvector` 显式 pin `1.22.0-beta32`，`langchain4j-open-ai` 同版本）
- 聊天：DeepSeek `deepseek-flash`（`https://api.deepseek.com`）；向量：DashScope `text-embedding-v4`，1024 维（`https://dashscope.aliyuncs.com/compatible-mode/v1`）
- 向量库：Neon 免费云 Postgres + pgvector（直连端点，不要 `-pooler` 端点）

## 快速开始

1. 按 [部署](docs/deployment.md) 建好 Neon 库，拿到 DeepSeek 与 DashScope 的 Key。
2. 设 5 个环境变量（见 [部署](docs/deployment.md)），`mvn clean spring-boot:run`。
3. 冒烟：

```bash
curl -X POST http://localhost:8080/api/ingest -F "file=@docs/请假制度.txt"
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

## 文档

- [架构](docs/architecture.md)——模块划分、数据流、关键决策与已知缺口
- [接口](docs/api.md)——全部接口、SSE 事件、统一错误体
- [部署](docs/deployment.md)——Neon、环境变量、启动
- [验收](docs/acceptance.md)——15 条验收清单

`docs/*.txt` 为示例文档（请假 / 报销 / 考勤制度），可直接上传验证。

## 代码结构

```text
src/main/java/com/example/kb/
├── KbApplication.java                 # 启动类
├── config/                            # 按提供方拆分，一类 Bean 一个配置
│   ├── ChatModelConfig.java           # DeepSeek 聊天 + 流式聊天
│   ├── EmbeddingConfig.java           # DashScope 向量 + 切块器
│   └── VectorStoreConfig.java         # pgvector 向量库
├── controller/                        # 按领域拆分，一个领域一个 Controller
│   ├── IngestController.java          # POST /api/ingest
│   ├── AskController.java             # POST /api/ask、/api/ask-stream
│   └── DocumentController.java        # GET /api/documents、DELETE /api/document
├── service/                           # 每个服务只做一件事
│   ├── IngestService.java             # 文档解析入库（txt 直读，其余走 Tika）
│   ├── CodeIngestService.java         # Java 单文件入库（JavaParser 按方法切块）
│   ├── RagService.java                # 检索问答（整段 + 流式共用 prepare）
│   ├── DocumentManageService.java     # 文档清单、删除、按 source 删块
│   └── MetadataKeys.java              # 块元数据键集中定义
├── common/exception/                  # 统一异常处理与错误体
│   ├── GlobalExceptionHandler.java
│   └── ErrorResponse.java
└── dto/                               # 接口出入参
```
