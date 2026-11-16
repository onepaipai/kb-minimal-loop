# 架构

## 模块划分

- `config/`：按提供方拆分。`ChatModelConfig`（DeepSeek 聊天 + 流式）、`EmbeddingConfig`（DashScope 向量 + 切块器）、`VectorStoreConfig`（pgvector）。Bean 方法名即 Bean 名，改名会影响注入。
- `controller/`：按领域拆分。`IngestController`（入库）、`AskController`（问答 + 流式）、`DocumentController`（清单 + 删除）。路由见 [接口](api.md)。
- `service/`：`IngestService`（文档入库）、`CodeIngestService`（Java 单文件入库）、`RagService`（检索问答）、`DocumentManageService`（清单/删除/按 source 删块）、`MetadataKeys`（块元数据键集中定义）。
- `common/exception/`：`GlobalExceptionHandler` 统一异常处理，`ErrorResponse` 统一错误体。
- `dto/`：接口出入参，不做领域拆分（7 个类，平铺最直观）。

## 数据流

### 入库

```text
上传文件 → 后缀分流 → 解析成文本 → 切块（500/100）→ 向量化（1024 维）
        → 存 pgvector（chunk_embedding，metadata JSONB 带 source）
```

- `.txt` 直接按 UTF-8 读；`.pdf/.doc/.docx/.md` 走 Tika 解析（内嵌 Tika 3.2.3）；`.java` 走独立链路，见下。
- 同名文件整体替换：先删旧块再入库（**非原子**，见已知缺口）。
- 扫描版 PDF（纯图片无文字层）解析不出文本，返回 400，不静默存 0 块。

### Java 单文件代码知识库

JavaParser 只找边界，按 `Range` 行号切原文保留注释与格式：一个方法一块，类头（包名 + import + 类注释 + 类声明 + 字段）单独一块；每块前加定位头 `// 文件：X.java | 类：完整类名 | 方法：方法签名`，元数据带 `source/kind=code/package/class/method/signature`。先解析后删旧块（语法错报 400、旧块不动），问答复用 `/api/ask`。

### 问答

```text
问题 → 向量化 → pgvector 检索最像的 5 块（minScore 0.55）
    → 拼带编号的资料段 + 系统提示词 → DeepSeek 作答，带 [1][2] 引用
```

- 系统提示词只允许用本轮资料作答，资料里没有就答“资料里没找到”，不编造。
- `sessionId` 传入时保留多轮历史（内存，单会话最多 20 条消息，约 10 轮），只用来理解追问中的指代，不当作资料来源。
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
| 密钥 | 5 个敏感值全部走环境变量，不写死、不进 git |
| 表结构 | `schema.sql` 建表（`IF NOT EXISTS`），向量库配置 `createTable=false`、`skipCreateVectorExtension=true`，不重复建表 |
| 元数据 | `COMBINED_JSONB` 模式，对应 `schema.sql` 的 `metadata JSONB` 列；`source` 为文件名，是清单/删除/定向检索的键 |
| Tika | 只用 `langchain4j-document-parser-apache-tika`（内嵌 Tika 3.2.3），严禁 Tika 4.x——改了 `Parser.parse` 签名，编译能过、运行时 `NoSuchMethodError` |

## 已知缺口（生产化前必处理）

- **无鉴权**：接口谁都能调；正式使用需加鉴权与限流，`sessionId` 按登录用户隔离并换集中存储。
- **同名替换非原子**：先删旧块再入库，中途失败（向量接口报错、断库）会丢旧文档；正式数据前改为先入后删或加事务/备份。
- **会话记忆在内存**：无过期、无清理，重启即失；正式使用换持久化存储。
- **无单元测试与 CI**：改动后以 `mvn clean package -DskipTests` 与接口实测验证。
- **无分页**：`GET /api/documents` 全量返回，文档多时需分页。
