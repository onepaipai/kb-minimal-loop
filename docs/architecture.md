# 架构

## 模块划分

- `config/`：按提供方拆分。`ChatModelConfig`（DeepSeek 聊天 + 流式）、`EmbeddingConfig`（DashScope 向量 + 切块器）、`VectorStoreConfig`（pgvector）。Bean 方法名即 Bean 名，改名会影响注入。
- `controller/`：按领域拆分。`IngestController`（入库）、`AskController`（问答 + 流式）、`DocumentController`（清单 + 删除 + 审批）、`AuthController`（登录 + 改密码 + TOTP 双因素）、`AdminController`（用户管理 + 审计日志 + 反馈统计）、`DepartmentController`（部门管理，仅 ADMIN）、`FeedbackController`（问答反馈）。路由见 [接口](api.md)。
- `service/`：`IngestService`（文档入库）、`CodeIngestService`（Java 单文件入库）、`RagService`（检索问答）、`HybridSearchService`（向量 + 关键词 RRF 融合检索、同义词扩展、pg_trgm 模糊兜底）、`RerankService`（神经重排，失败回退 RRF）、`DashScopeRerankClient`（DashScope qwen3-rerank 直调）、`CitationValidator`（引用文本依据校验）、`DocumentManageService`（清单/删除/审批状态/版本号/回滚）、`MetadataKeys`（块元数据键集中定义）、`JdbcChatMemoryStore`（会话记忆落库）、`ChatMemoryCleanup`（过期会话与限流窗口定时清理）、`DepartmentService`（部门注册表）、`DepartmentBootstrap`（启动时从已入库块回填部门）、`AuditService`（审计日志）、`FeedbackService`（问答反馈统计）、`NotificationService`（站内通知）、`ApiKeyService`（对外 API Key）、`UserService`（在 `security/` 下，用户管理）。
- `security/`：`SecurityConfig`（JWT 过滤链）、`JwtService`（签发/校验）、`JwtAuthenticationFilter`、`ApiKeyAuthFilter`（请求头 `X-API-Key` 鉴权，只对非管理接口生效）、`RateLimitFilter`（数据库固定窗口限流）、`DepartmentScope`（部门与角色权限集中判断）、`AdminBootstrap`（初始管理员播种）、`TotpService`（TOTP 双因素）、`PasswordPolicy`（密码策略）。
- `common/exception/`：`GlobalExceptionHandler` 统一异常处理，`ErrorResponse` 统一错误体。
- `dto/`：接口出入参，不做领域拆分（平铺最直观）。
- `src/test/`：单元测试（`VisibilityTest`、`DepartmentScopeTest`、`PasswordPolicyTest`、`RoleScopeTest`、`TotpServiceTest`、`HybridSearchServiceTest`，不依赖数据库）；CI 见 `.github/workflows/ci.yml`。
- `src/main/resources/static/`：前端企业级单页（`index.html`、`styles.css`、`app.js`，原生 JS + fetch，无构建步骤；样式为 Tabler v1.7.5 的 `tabler.min.css`，MIT，GitHub tabler/tabler 的 release 构建，本地引入；布局：品牌登录分栏、深色侧边栏导航与会话列表、总览仪表盘（统计全部来自真实接口，含待审批数与问答好评率）、中央对话气泡（回答下有好评/差评按钮），会话与消息记录只存浏览器 localStorage；导航含审批、部门管理、审计日志、用户管理、改密码、双因素开关）；Spring Boot 默认 serving，`/` 打开即用，`/api/**` 之外的请求安全配置全部放行。

## 数据流

### 入库

```text
上传文件 → 后缀分流 → 解析成文本 → 切块（800/150，结构感知）→ 向量化（1024 维）
        → 存 pgvector（chunk_embedding，metadata JSONB 带 source/chunk_index/chunk_total/section）
```

- `.txt/.md` 直接按 UTF-8 读（md 保留标题结构供切块器按标题切）；`.pdf/.doc/.docx` 走 Tika 解析（内嵌 Tika 3.2.3）。
- 切块器（`DocumentChunker`）按标题、表格、代码围栏、段落分块：每块带章节路径前缀（如 `【章节：请假制度 > 年假】`）与块号/总数/章节元数据；重叠 150 字只在句末断开，不断句；表格与代码围栏不拦腰切，超长表格按行分组、超长围栏按行分组。已入库的旧文档不受影响，重新上传才会按新规则切。
- 同名文件整体替换，先入后标：新块带本次入库的 `batch` 批次号入库成功后，旧版块不删，改成 `superseded`（问答与清单自动排除），同时把改之前的状态记到块元数据 `prev_status`；入库中途失败（向量接口报错、断库）旧版不动。版本号每次同名替换 +1（首版 v1），存在块元数据 `version` 里。回滚（`POST /api/document/rollback`）：目标版恢复成 `prev_status`（没批过的旧版回滚后还是待审批，不会悄悄发布），当前版改成 `superseded`；审批（批准/驳回）只动当前版的块，不碰已 superseded 的旧版。
- 通知：上传待审批时给管理员发提醒（部门文档再发给该部门的部门管理员），批准/驳回后给上传人发结果（上传人从最近一次入库任务里找，7 天前的任务已被清理时不发）；通知失败不影响上传/审批本身（内部 catch 记 WARN）。对外 API Key：`kb_` + 32 位随机串，库里只存 SHA-256 哈希，明文只在创建时返回一次；请求头 `X-API-Key` 鉴权，权限就是 Key 创建者的角色，`/api/admin/**` 与 `/api/auth/**` 不认 Key。
- 入库带审批状态（块元数据 `status`）：管理员上传直接发布，部门管理员传本部门的文档直接发布，其余一律 `pending` 待审批；问答只查 `approved` 的块，清单默认只列已发布的（管理员与部门管理员看全部）。
- 扫描版 PDF（纯图片无文字层）解析不出文本，返回 400，不静默存 0 块。

### Java 单文件代码知识库

JavaParser 只找边界，按 `Range` 行号切原文保留注释与格式：一个方法一块，类头（包名 + import + 类注释 + 类声明 + 字段）单独一块；每块前加定位头 `// 文件：X.java | 类：完整类名 | 方法：方法签名`，元数据带 `source/kind=code/package/class/method/signature`。嵌套类的方法归属最近一层类；单个方法超过 8000 字符时先按空行断成逻辑段再打包，单段仍超长才按行硬切。先解析后入库（语法错报 400、旧块不动），问答复用 `/api/ask`。

### 问答

```text
问题 → 向量化 → 混合检索（向量 + 关键词，RRF 融合取前 20 个候选，qwen3-rerank 重排取前 5 块，minScore 0.55 只卡向量路）
    → 拼带编号的资料段 + 系统提示词 → DeepSeek 作答，带 [1][2] 引用；回答返回前先删超范围编号、再校验每个引用有没有文本依据；无命中时不调模型，直接回“资料里没找到”
```

- 混合检索：向量路（pgvector 余弦，权限与状态过滤在库内完成）+ 关键词路（SQL ILIKE 候选，问题分词：中文连续汉字按二字滑动切，英文词至少 3 字母，按词频总分排序——同一个词出现 10 次的块比出现 1 次的更相关），两路用 RRF（倒数排名融合，k=60）合并；返回的 `hits.score` 是 RRF 融合分（约 0–0.03），不再是余弦相似度。关键词分词带同义词扩展（`src/main/resources/synonyms.txt`，词=同义词1,同义词2，双向）；ILIKE 命中少于 5 条时 pg_trgm（`word_similarity≥0.2`）模糊兜底，错别字也能找回候选。RRF 融合后默认再用 DashScope `qwen3-rerank` 对前 20 个候选重排取前 5 块（`app.rerank.*`，`RERANK_ENABLED` 开关，Key 复用 `DASHSCOPE_API_KEY`），失败只记日志、回落 RRF 顺序；重排开启时 `hits.score` 是重排相关度（0–1）。
- 系统提示词只允许用本轮资料作答，资料里没有就答“资料里没找到”，不编造；提示词里写死本次资料块数，引用编号只能是 `[1]` 到 `[N]`；整段版回答返回前删掉超出范围的引用编号（如只有 3 块却写了 `[9]`），流式版只对存进记忆的完整答案做这步清洗；再过一遍 `CitationValidator`：引用它的那句话与被引块没有共享有效词时删掉该编号，只删编号不删正文，答案本来就没有引用时原样返回。
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
| 密钥 | 12 个敏感值全部走环境变量，不写死、不进 git（见 [部署](deployment.md)） |
| 表结构 | `schema.sql` 建表（`IF NOT EXISTS`），向量库配置 `createTable=false`、`skipCreateVectorExtension=true`，不重复建表 |
| 元数据 | `COMBINED_JSONB` 模式，对应 `schema.sql` 的 `metadata JSONB` 列；`source` 为文件名，是清单/删除/定向检索的键 |
| Tika | 只用 `langchain4j-document-parser-apache-tika`（内嵌 Tika 3.2.3），严禁 Tika 4.x——改了 `Parser.parse` 签名，编译能过、运行时 `NoSuchMethodError` |

## 鉴权与权限（已落地）

- 登录：`POST /api/auth/login` 用户名密码校验（BCrypt），通过发 JWT（HS256，有效期 8 小时）与 Refresh Token（不透明随机串，有效期 7 天）；除登录、刷新、退出外所有 `/api/**` 都要带 `Authorization: Bearer <token>`，未带或过期返回 401。连续 5 次密码错误锁定账号 15 分钟；开了双因素的账号登录时多传 6 位 `totpCode`（TOTP，RFC 6238，Google Authenticator 兼容；开通走 `/api/auth/totp/setup` 与 `/api/auth/totp/enable`）。
- Refresh Token：`POST /api/auth/refresh` 换一对新 Token（旧的立即吊销，只能用一次）；`POST /api/auth/logout` 吊销 Refresh Token；改密码吊销该用户全部 Token。库里只存 SHA-256 哈希（`refresh_token` 表），不存明文；过期与吊销的行每小时清理一次。前端 401 时自动刷新重试一次，换不到才回登录页。
- OIDC 企业登录：通用 OIDC，默认关闭（`OIDC_ENABLED=false` 时整个链路不存在）。开启后 Spring Security 的 `oauth2Login` 接管 `/oauth2/authorization/kb`，按 `OIDC_ISSUER_URI` 拉 discovery 文档建注册；登录成功后按 IdP 的 subject 找人，找不到新建一个 USER 账号（用户名取邮箱本地部分，密码随机、无法用密码登录），发一个 5 分钟一次性换码（`oidc_code` 表，只存哈希）跳回首页，前端凭码调 `POST /api/auth/oidc/exchange` 换 Token。管理员用 `PUT /api/admin/users/{username}` 调 OIDC 新账号的角色和部门。开启后启动时 IdP 必须可达，否则启动失败。
- 角色四档：`ADMIN` 全局、`DEPT_ADMIN` 管本部门（建本部门用户、删/批本部门文档、看本部门用户清单）、`EDITOR` 可上传（入库后待审批）、`USER` 只能问答。`app_user` 表（用户名唯一、密码 hash、角色、归属部门、TOTP 密钥与启用标志、失败次数与锁定时间）。首次启动库里没有管理员时自动播种一个（账号密码走 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 环境变量，不配密码则随机生成并打在启动日志里）。
- 部门注册表：`department` 表（代号主键、名称、父部门代号搭组织架构树）。启动时把已入库块里出现过的部门代号登记进来；上传时用到未登记的代号自动建一条；已有文档在用的部门不允许删除。
- 审批：文档状态 `pending/approved/rejected` 存在块元数据里；管理员上传直接发布，部门管理员传本部门的文档直接发布，其余待审批；批准/驳回走 `/api/document/approve` 与 `/api/document/reject`（管理员全部，部门管理员只批本部门）；问答只查已发布的块。
- 部门强制：有归属部门的用户只能访问本部门的文档——问答与清单传别的部门报 403，上传只能传 `all` 或自己部门；管理员不受限（问答可用 `department` 参数指定部门）。删除：管理员全部，部门管理员只删本部门的。
- 限流：固定窗口，数据库实现（`rate_limit` 表，多实例共用，窗口按分钟对齐）——已登录用户每分钟 120 次，未登录按 IP 每分钟 30 次，超了返回 429；限流表写失败时放行本次请求（不把整站拖死），过期窗口每小时清理一次。
- 审计日志：`audit_log` 表（只增不改），记登录、登录失败、OIDC 登录、上传、删除、审批、驳回、建用户、改用户、改密码、问答（问题原文截断 100 字）；仅管理员可查。
- 问答反馈：`feedback` 表（好评/差评 + 原问答摘要截断 500 字），管理员看好评率。
- 健康检查：Actuator，`/actuator/health` 与 `/actuator/info` 不用登录，`/actuator/metrics` 与 `/actuator/prometheus` 需登录。
- 会话隔离：见问答一节，会话键带用户名与部门。

### 旧数据回填（必做，否则旧文档查不到）

已入库的块没有 `visibility` 与 `status` 字段，权限与审批过滤会把它们漏掉。先在 Neon 上核对条数，再回填：

```sql
SELECT count(*) FROM chunk_embedding WHERE metadata->>'visibility' IS NULL;
UPDATE chunk_embedding SET metadata = jsonb_set(COALESCE(metadata,'{}'::jsonb), '{visibility}', '"all"')
WHERE metadata->>'visibility' IS NULL;
SELECT count(*) FROM chunk_embedding WHERE metadata->>'visibility' IS NULL;
SELECT count(*) FROM chunk_embedding WHERE metadata->>'status' IS NULL;
UPDATE chunk_embedding SET metadata = jsonb_set(COALESCE(metadata,'{}'::jsonb), '{status}', '"approved"')
WHERE metadata->>'status' IS NULL;
SELECT count(*) FROM chunk_embedding WHERE metadata->>'status' IS NULL;
```

回填后两条核对查询都应为 0。

## 已知缺口（生产化前仍需处理）

- **SSO 代码已接、未启用**：通用 OIDC 已落地（默认关闭），要真正用还需他在 IdP 上注册应用、配 `OIDC_ENABLED`/`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`/`OIDC_ISSUER_URI` 四个环境变量；没配之前登录只有账号密码 + 可选 TOTP 双因素。
- **JWT 密钥轮换**：`JWT_SECRET` 换了之后已签发的 Access Token 全部失效，但 Refresh Token 不受影响（库里存的随机串，不用密钥签名），前端下次请求会自动刷新出新签发的 Access Token，用户无感知。
- **神经重排已接**：RRF 融合后默认调 DashScope `qwen3-rerank` 对前 20 个候选重排（`app.rerank.*`，模型默认 `qwen3-rerank`，`RERANK_ENABLED` 开关，Key 复用 `DASHSCOPE_API_KEY`）；失败只记日志、回落 RRF 顺序。注意：默认走 `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank`（全局端点，官方文档只给了带 WorkspaceId 的地域端点，未单独列出全局端点），若调不通，把 `RERANK_BASE_URL` 改成自己的 `{WorkspaceId}.{地域}.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank`。
- **无服务器与备份方案**：本仓库只给应用与部署说明，服务器、域名、HTTPS 证书、数据库备份（Neon 免费档无自动备份，正式使用定期 `pg_dump`）需另行落实。
- **单测只覆盖纯逻辑**：检索、入库、审批、鉴权等关键链路有集成测试（`src/test/java/com/example/kb/it/`：鉴权、入库问答、审批三条），但它们只在真实库与真实 Key 上跑（`KB_IT_ENABLED=true`，见 [验收](acceptance.md)），平时与 CI 自动跳过。

### 异步入库（已落地）

`POST /api/ingest-async` 立即返回 202 与任务号（`ingest_job` 表，状态 PENDING/RUNNING/DONE/FAILED），解析与向量化在 `ingest-` 线程池（2 常驻、最多 4 并发、排队 100，守护线程）里做；`GET /api/ingest/jobs/{id}` 轮询状态，成功记块数、失败记原因。任务只能本人或管理员查，7 天前的任务每小时清理。同步上传 `/api/ingest` 保留，供 curl 与小文件使用；前端上传默认走异步。
