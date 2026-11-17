# kb-minimal-loop —— AI 知识库最小闭环

上传文档（.txt/.pdf/.doc/.docx/.md/.java）→ 递归切块 → DashScope 向量化 → 存入 Neon Postgres（pgvector）→ 提问时混合检索（向量 + 关键词，RRF 融合）→ DeepSeek 带 `[1][2]` 引用作答；另有流式问答（SSE）、Java 单文件代码知识库、四档角色与部门权限、文档审批发布、审计日志、TOTP 双因素、健康检查。

## 技术栈

- Java 17 / Spring Boot 3.3.5 / Maven
- LangChain4j 1.22.0（`langchain4j-pgvector` 显式 pin `1.22.0-beta32`，`langchain4j-open-ai` 同版本）
- 聊天：DeepSeek `deepseek-flash`（`https://api.deepseek.com`）；向量：DashScope `text-embedding-v4`，1024 维（`https://dashscope.aliyuncs.com/compatible-mode/v1`）
- 向量库：Neon 免费云 Postgres + pgvector（直连端点，不要 `-pooler` 端点）

## 快速开始

1. 按 [部署](docs/deployment.md) 建好 Neon 库，拿到 DeepSeek 与 DashScope 的 Key。
2. 设环境变量（见 [部署](docs/deployment.md)，模型与数据库 5 个必填，`JWT_SECRET` 与 `ADMIN_PASSWORD` 建议配上），`mvn clean spring-boot:run`。
3. 冒烟（先登录拿 Token，见 [接口](docs/api.md)；或直接打开 `http://localhost:8080/` 用前端页面操作）：

```bash
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"<初始密码>"}'
export TOKEN="<返回的 token>"
curl -X POST http://localhost:8080/api/ingest -H "Authorization: Bearer $TOKEN" -F "file=@docs/请假制度.txt"
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

## 文档

- [架构](docs/architecture.md)——模块划分、数据流、关键决策与已知缺口
- [接口](docs/api.md)——全部接口、SSE 事件、统一错误体
- [部署](docs/deployment.md)——Neon、环境变量、启动
- [验收](docs/acceptance.md)——33 条验收清单

`docs/*.txt` 为示例文档（请假 / 报销 / 考勤制度），可直接上传验证。前端企业级单页在 `src/main/resources/static/`（`index.html`、`styles.css`、`app.js`，无构建步骤，样式为 Tabler v1.7.5 的 `tabler.min.css`，启动后打开 `http://localhost:8080/`）。

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
│   ├── DocumentController.java        # GET /api/documents、DELETE /api/document、审批
│   ├── AuthController.java            # POST /api/auth/login、/api/auth/change-password、TOTP 双因素
│   ├── AdminController.java           # 用户管理、审计日志、反馈统计
│   ├── DepartmentController.java      # 部门管理（仅管理员）
│   └── FeedbackController.java        # POST /api/feedback
├── security/                          # 鉴权与权限
│   ├── SecurityConfig.java            # JWT 过滤链（登录与健康检查放行，其余 /api/** 要带 Token）
│   ├── JwtService.java                # Token 签发与校验（HS256，8 小时）
│   ├── JwtAuthenticationFilter.java   # Bearer Token → SecurityContext
│   ├── RateLimitFilter.java           # 数据库固定窗口限流（登录用户 120/分钟，未登录按 IP 30/分钟）
│   ├── DepartmentScope.java           # 部门与角色权限集中判断
│   ├── UserService.java               # 用户管理（BCrypt 密码、锁定、TOTP 字段）
│   ├── TotpService.java               # TOTP 双因素（RFC 6238）
│   ├── PasswordPolicy.java            # 密码策略（至少 10 位，含字母和数字）
│   └── AdminBootstrap.java            # 首次启动播种初始管理员
├── service/                           # 每个服务只做一件事
│   ├── IngestService.java             # 文档解析入库（txt 直读，其余走 Tika）
│   ├── CodeIngestService.java         # Java 单文件入库（JavaParser 按方法切块）
│   ├── RagService.java                # 检索问答（整段 + 流式共用 prepare）
│   ├── HybridSearchService.java       # 混合检索（向量 + 关键词，RRF 融合）
│   ├── DocumentManageService.java     # 文档清单、删除、审批状态、版本号
│   ├── DepartmentService.java         # 部门注册表
│   ├── DepartmentBootstrap.java       # 启动时从已入库块回填部门
│   ├── AuditService.java              # 审计日志
│   ├── FeedbackService.java           # 问答反馈统计
│   ├── JdbcChatMemoryStore.java       # 会话记忆落库（chat_memory 表）
│   ├── ChatMemoryCleanup.java         # 过期会话与限流窗口定时清理
│   └── MetadataKeys.java              # 块元数据键集中定义
├── common/                            # 跨层共用
│   ├── Visibility.java                # 可见性 / 部门代号：all 或小写代号，入口统一校验
│   └── exception/                     # 统一异常处理与错误体
│       ├── GlobalExceptionHandler.java
│       └── ErrorResponse.java
└── dto/                               # 接口出入参
```
