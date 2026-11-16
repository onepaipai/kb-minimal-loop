# 接口

基础地址 `http://localhost:8080`。错误统一返回 [错误体](#错误体)，不含堆栈。

## 前端页面

浏览器打开 `http://localhost:8080/` 即可用：登录、问答（默认流式，可关）、上传文档、清单与删除（仅管理员可见删除按钮）、用户管理（仅管理员）、改密码。Token 存浏览器 localStorage，8 小时有效，过期后接口返回 401，前端自动回登录页。静态文件在 `src/main/resources/static/`（`index.html`、`styles.css`、`app.js`，无构建步骤）；样式用 Tabler v1.7.5（MIT，GitHub tabler/tabler 的 release 构建，`tabler.min.css` 直接放在 static/ 里本地引入，不依赖外网）；`/api/**` 之外的请求全部放行，所以页面不需要登录就能打开，调接口时才要 Token。

## 鉴权（除登录外所有接口都要带 Token）

```bash
# 1. 登录拿 Token（有效期 8 小时）
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<初始密码>"}'
# 返回 {"token":"...","username":"admin","role":"ADMIN","department":null,"expiresIn":28800}

# 2. 之后每个请求都带上
export TOKEN="<上面返回的 token>"
curl http://localhost:8080/api/documents -H "Authorization: Bearer $TOKEN"
```

- 未带 Token 或 Token 过期返回 401；权限不够（如普通用户调删除）返回 403，错误体与业务错误同一格式。
- 首次启动：库里没有管理员时自动创建一个（账号走 `ADMIN_USERNAME`，默认 `admin`；密码走 `ADMIN_PASSWORD`，不配则随机生成并打在启动日志里），登录后请立即调 `/api/auth/change-password` 改掉。
- 管理员创建用户见 [管理员接口](#post-apiadminusers--创建用户仅-admin)，用户有 `role`（ADMIN/USER）与 `department`（归属部门，管理员为 null）；普通用户只能访问本部门的文档，传别的部门报 403。

## POST /api/ingest —— 上传文档入库

```bash
curl -X POST http://localhost:8080/api/ingest -H "Authorization: Bearer $TOKEN" -F "file=@docs/请假制度.txt"
curl -X POST http://localhost:8080/api/ingest -H "Authorization: Bearer $TOKEN" -F "file=@docs/产品手册.pdf"
```

支持后缀（不区分大小写）：`.txt` 直读 UTF-8；`.pdf/.doc/.docx/.md` 走 Tika 解析；`.java` 走代码知识库链路（见 [架构](architecture.md)）。其他后缀返回 400。

可选参数 `visibility`：该文档的可见性，`all`（全员可见，默认）或一个部门代号（如 `hr`、`finance`）；只允许小写字母、数字、下划线、中划线，别的字符报 400。同名文件重新上传时新 visibility 覆盖旧的。非管理员只能传 `all` 或自己部门的 visibility，传别的部门报 403。

```bash
curl -X POST http://localhost:8080/api/ingest -H "Authorization: Bearer $TOKEN" -F "file=@docs/请假制度.txt" -F "visibility=hr"
```

返回：

```json
{"file":"请假制度.txt","chunks":1,"visibility":"hr"}
```

`chunks` 为入库后实际落库统计。同名文件重复上传时自动整体替换，清单块数不翻倍；替换是先入后删——新块入库成功才删旧块，中途失败旧文档还在。

## POST /api/ask —— 检索问答

```bash
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

返回：

```json
{"answer":"……[1]","sources":["请假制度.txt"]}
```

- `sources` 第 i 个元素对应回答中的 `[i]` 引用编号；`hits` 同时给出每条引用的相关度分数（0~1）与命中的原文块，便于调整 `minScore`。
- 可选入参：`sources` 数组（只在指定文档内检索）、`sessionId`（多轮追问，同会话保留问答历史）、`maxResults`（1–20，默认 5）、`minScore`（0–1，默认 0.55）、`department`（部门代号；有归属部门的用户只能查本部门，传别的报 403，管理员可用该参数指定部门，不传只查全员可见的）。
- 权限过滤在 pgvector 检索内完成（`visibility` 与 `sources` 用 `Filter.and` 组合），没权限的块连 prompt 都不进；已入库但没有 `visibility` 字段的旧块查不到，先跑回填 SQL（见 [架构](architecture.md)）。

```bash
# 定向检索
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"高铁能报多少钱？","sources":["报销制度.txt"]}'
# 多轮追问：第二轮能理解“那 1 天以内的”指代上一轮的请假话题
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？","sessionId":"demo-1"}'
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"那1天以内的呢？","sessionId":"demo-1"}'
# 调整检索参数
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"差旅报销多久内提交？","maxResults":3,"minScore":0.6}'
```

入参越界一律 400：`question` 为空、`maxResults` 超出 1–20、`minScore` 超出 0–1。

## POST /api/ask-stream —— 流式问答（SSE）

与 `/api/ask` 同一套检索、校验与多轮记忆，答案逐 token 以 SSE 推送，超时 120 秒。

```bash
curl -N -X POST http://localhost:8080/api/ask-stream -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

事件顺序：`sources`（引用来源 hits）→ `token`（逐个）→ `done`；出错时连接以错误结束，前端提示重问。

## GET /api/documents —— 已入库文档清单

```bash
curl http://localhost:8080/api/documents -H "Authorization: Bearer $TOKEN"
curl "http://localhost:8080/api/documents?department=hr&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

可选参数 `department`：不传只列全员可见的，传了列全员可见加该部门的（有归属部门的用户只能看本部门，传别的报 403）。分页：`page` 从 0 起，`size` 1–100，默认 20；越界报 400。返回：

```json
{"documents":[{"source":"请假制度.txt","chunks":1,"visibility":"hr"},{"source":"报销制度.txt","chunks":1,"visibility":"all"}],"total":2,"page":0,"size":20}
```

## DELETE /api/document?source=xxx —— 删除指定文档（仅管理员）

```bash
curl -X DELETE "http://localhost:8080/api/document?source=报销制度.txt" -H "Authorization: Bearer $TOKEN"
```

返回（`deletedChunks` 为实际删除的块数，按删除前后统计差值计算）：

```json
{"source":"报销制度.txt","deleted":true,"deletedChunks":1}
```

## POST /api/auth/login —— 登录

```bash
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"<密码>"}'
```

返回 `{"token":"...","username":"admin","role":"ADMIN","department":null,"expiresIn":28800}`；用户名或密码错返回 401（不区分是账号错还是密码错）。

## POST /api/auth/change-password —— 改自己的密码

```bash
curl -X POST http://localhost:8080/api/auth/change-password -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"oldPassword":"<旧密码>","newPassword":"<新密码>"}'
```

原密码不对返回 401，新密码至少 8 位；成功返回 `{"changed":true}`。

## POST /api/admin/users —— 创建用户（仅 ADMIN）

```bash
curl -X POST http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"username":"zhang","password":"<至少8位>","role":"USER","department":"hr"}'
```

`role` 只允许 ADMIN/USER，不传默认 USER；`department` 不传表示管理员（无归属部门）。用户名已存在返回 409。

## GET /api/admin/users —— 用户清单（仅 ADMIN）

```bash
curl http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN"
```

返回用户列表（不含密码 hash）。

## 错误体

所有接口错误统一返回：

```json
{"timestamp":"2026-11-16T03:00:00Z","status":400,"message":"question 不能为空","path":"/api/ask"}
```

- `status` 与 HTTP 状态码一致；`message` 为中文原因；`path` 为请求路径。
- 业务校验失败（如空文件、扫描版 PDF、语法错的 `.java`、入参越界）返回 400；未登录或 Token 过期返回 401；权限不够返回 403；请求过于频繁返回 429；未预期的内部错误返回 500，`message` 固定为“服务内部错误”，详情只记日志。
