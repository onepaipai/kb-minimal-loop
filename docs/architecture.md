# 架构

## 模块划分

- `config/`：按提供方拆分。`ChatModelConfig`（DeepSeek 聊天 + 流式）、`EmbeddingConfig`（DashScope 向量 + 切块器）、`VectorStoreConfig`（pgvector）。Bean 方法名即 Bean 名，改名会影响注入。
- `controller/`：按领域拆分。`IngestController`（入库）、`AskController`（问答 + 流式）、`DocumentController`（清单 + 删除）、`AuthController`（登录 + 改密码）、`AdminController`（用户管理，仅 ADMIN）。路由见 [接口](api.md)。
- `service/`：`IngestService`（文档入库）、`CodeIngestService`（Java 单文件入库）、`RagService`（检索问答）、`DocumentManageService`（清单/删除/按 source 删块）、`MetadataKeys`（块元数据键集中定义）、`JdbcChatMemoryStore`（会话记忆落库）、`ChatMemoryCleanup`（过期会话定时清理）、`UserService`（在 `security/` 下，用户管理）。
- `security/`：`SecurityConfig`（JWT 过滤链）、`JwtService`（签发/校验）、`JwtAuthenticationFilter`、`RateLimitFilter`（固定窗口限流）、`DepartmentScope`（部门权限集中判断）、`AdminBootstrap`（初始管理员播种）。
- `common/exception/`：`GlobalExceptionHandler` 统一异常处理，`ErrorResponse` 统一错误体。
- `dto/`：接口出入参，不做领域拆分（平铺最直观）。
- `src/test/`：单元测试（`VisibilityTest`、`DepartmentScopeTest`，不依赖数据库）；CI 见 `.github/workflows/ci.yml`。
- `src/main/resources/static/`：前端单页（`index.html`、`styles.css`、`app.js`，原生 JS + fetch，无构建步骤）；Spring Boot 默认 serving，`/` 打开即用，`/api/**` 之外的请求安全配置全部放行。

## 数据流

### 入库

```text
上传文件 → 后缀分流 → 解析成文本 → 切块（500/100）→ 向量化（1024 维）
        → 存 pgvector（chunk_embedding，metadata JSONB 带 source）
```

- `.txt` 直接按 UTF-8 读；`.pdf/.doc/.docx/.md` 走 Tika 解析（内嵌 Tika 3.2.3）；`.java` 走独立链路，见下。
- 同名文件整体替换，先入后删：新块带本次入库的 `batch` 批次号入库成功后，再用一条 SQL 删掉该文件非本批次的旧块（`COALESCE(batch,'') <> ?`，旧块可能没有 batch 字段）；入库中途失败（向量接口报错、断库）旧块不动。
- 扫描版 PDF（纯图片无文字层）解析不出文本，返回 400，不静默存 0 块。

### Java 单文件代码知识库

JavaParser 只找边界，按 `Range` 行号切原文保留注释与格式：一个方法一块，类头（包名 + import + 类注释 + 类声明 + 字段）单独一块；每块前加定位头 `// 文件：X.java | 类：完整类名 | 方法：方法签名`，元数据带 `source/kind=code/package/class/method/signature`。嵌套类的方法归属最近一层类；单个方法超过 8000 字符时按行拆成多块。先解析后入库（语法错报 400、旧块不动），问答复用 `/api/ask`。

### 问答

```text
问题 → 向量化 → pgvector 检索最像的 5 块（minScore 0.55）
    → 拼带编号的资料段 + 系统提示词 → DeepSeek 作答，带 [1][2] 引用
```

- 系统提示词只允许用本轮资料作答，资料里没有就答“资料里没找到”，不编造。
- `sessionId` 传入时保留多轮历史（单会话最多 20 条消息，约 10 轮），只用来理解追问中的指代，不当作资料来源。历史存在 `chat_memory` 表里（`JdbcChatMemoryStore`，按 LangChain4j 的 JSON 编解码），重启不丢；24 小时未更新的会话由定时任务每小时清理一次。会话键是 `用户名|sessionId|部门`，不同用户、不同部门各用各的历史。
- 流式版 `/api/ask-stream` 与整段版共用同一套检索与校验，见 [接口](api.md)。

## 1024 维三处一致（改维度必须同步改三处）

1. `EmbeddingConfig.EMBEDDING_DIMENSION`（向量模型 `.dimensions()` 与向量库 `.dimension()` 共用这一个常量）
2. `VectorStoreConfig`（引用上面那个常量）
3. `src/main/resources/schema.sql` → `embedding vector(1024)`

## 关键决策

| 决策 | 结论 |
| --- | --- |
| 部署 | 不用 Docker，Windows 可跑；数据库走 Neon 云库直连端点 |
| 模型 | 聊天与向量分开：DeepSeek 只做聊天（它没有公开 embedding 接口），向量走 DashScope |
| 密钥 | 8 个敏感值全部走环境变量，不写死、不进 git（见 [部署](deployment.md)） |
| 表结构 | `schema.sql` 建表（`IF NOT EXISTS`），向量库配置 `createTable=false`、`skipCreateVectorExtension=true`，不重复建表 |
| 元数据 | `COMBINED_JSONB` 模式，对应 `schema.sql` 的 `metadata JSONB` 列；`source` 为文件名，是清单/删除/定向检索的键 |
| Tika | 只用 `langchain4j-document-parser-apache-tika`（内嵌 Tika 3.2.3），严禁 Tika 4.x——改了 `Parser.parse` 签名，编译能过、运行时 `NoSuchMethodError` |

## 鉴权与权限（已落地）

- 登录：`POST /api/auth/login` 用户名密码校验（BCrypt），通过发 JWT（HS256，有效期 8 小时）；除登录外所有 `/api/**` 都要带 `Authorization: Bearer <token>`，未带或过期返回 401。
- 用户：`app_user` 表（用户名唯一、密码 hash、角色 ADMIN/USER、归属部门）。首次启动库里没有管理员时自动播种一个（账号密码走 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 环境变量，不配密码则随机生成并打在启动日志里）；管理员用 `/api/admin/users` 创建用户。
- 部门强制：有归属部门的用户只能访问本部门的文档——问答与清单传别的部门报 403，上传只能传 `all` 或自己部门；管理员不受限（问答可用 `department` 参数指定部门）。删除仅 ADMIN 能调。
- 限流：固定窗口，内存实现（重启清零，多实例部署需换集中限流）——已登录用户每分钟 120 次，未登录按 IP 每分钟 30 次，超了返回 429。
- 会话隔离：见问答一节，会话键带用户名与部门。

### 旧数据回填（必做，否则旧文档查不到）

已入库的块没有 `visibility` 字段，权限过滤会把它们漏掉。先在 Neon 上核对条数，再回填：

```sql
SELECT count(*) FROM chunk_embedding WHERE metadata->>'visibility' IS NULL;
UPDATE chunk_embedding SET metadata = jsonb_set(COALESCE(metadata,'{}'::jsonb), '{visibility}', '"all"')
WHERE metadata->>'visibility' IS NULL;
SELECT count(*) FROM chunk_embedding WHERE metadata->>'visibility' IS NULL;
```

回填后第二条应为 0。

## 已知缺口（生产化前仍需处理）

- **限流在内存**：单实例够用，多实例部署需换 Redis 之类的集中限流，否则每个实例各算各的。
- **Token 无刷新机制**：JWT 有效期 8 小时，到期重新登录；正式使用可加 refresh token。
- **无审计日志**：谁删了哪篇文档、谁建了用户，目前只在应用日志里有请求记录，没有结构化审计表。
- **JWT 密钥轮换**：`JWT_SECRET` 换了之后已签发的 Token 全部失效，需在低峰期操作并通知重新登录。
