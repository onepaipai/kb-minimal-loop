# 接口

基础地址 `http://localhost:8080`。错误统一返回 [错误体](#错误体)，不含堆栈。

## 前端页面

浏览器打开 `http://localhost:8080/` 即可用：企业级外壳——登录页左品牌区右表单；登录后默认进总览仪表盘，统计（入库文档、知识块、注册用户、待审批文档、问答好评率）全部来自真实接口，另有快捷操作与最近入库文档；左侧深色边栏是导航（总览、智能问答、文档管理、审批、部门管理、审计日志、用户管理、改密码）与会话列表（新建、切换、删除对话，记录存服务端 /api/sessions，跨设备同步）；问答是中央对话气泡（默认流式逐字出，引用分数与原文显示在回答下方，回答下有好评/差评按钮），输入框上方有示例问题，Enter 发送、Shift+Enter 换行；上传文档走异步入库（提交后轮询任务状态，完成后刷新清单）、清单与删除、审批、部门管理、审计日志、用户管理、改密码、双因素开关在侧边栏切视图打开。Access Token 存浏览器 localStorage，8 小时有效；过期后前端先用 Refresh Token（7 天）自动换一对新 Token 再重试，换不到才回登录页。静态文件在 `src/main/resources/static/`（`index.html`、`styles.css`、`app.js`，无构建步骤）；样式用 Tabler v1.7.5（MIT，GitHub tabler/tabler 的 release 构建，`tabler.min.css` 直接放在 static/ 里本地引入，不依赖外网）；`/api/**` 之外的请求全部放行，所以页面不需要登录就能打开，调接口时才要 Token。

## 鉴权（除登录外所有接口都要带 Token）

```bash
# 1. 登录拿 Token（Access Token 有效期 8 小时，Refresh Token 有效期 7 天）
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<初始密码>"}'
# 返回 {"token":"...","refreshToken":"...","username":"admin","role":"ADMIN","department":null,"expiresIn":28800}

# 2. 之后每个请求都带上
export TOKEN="<上面返回的 token>"
curl http://localhost:8080/api/documents -H "Authorization: Bearer $TOKEN"

# 3. Access Token 过期后，用 Refresh Token 换一对新的（旧 Refresh Token 立即作废）
curl -X POST http://localhost:8080/api/auth/refresh -H "Content-Type: application/json" \
  -d '{"refreshToken":"<上面返回的 refreshToken>"}'
# 返回新的 {"token":"...","refreshToken":"...","username":"admin","role":"ADMIN","department":null,"expiresIn":28800}

# 4. 退出登录：吊销 Refresh Token
curl -X POST http://localhost:8080/api/auth/logout -H "Content-Type: application/json" \
  -d '{"refreshToken":"<上面返回的 refreshToken>"}'
# 返回 {"loggedOut":true}
```

- 未带 Token 或 Token 过期返回 401；权限不够（如普通用户调删除）返回 403，错误体与业务错误同一格式。
- 首次启动：库里没有管理员时自动创建一个（账号走 `ADMIN_USERNAME`，默认 `admin`；密码走 `ADMIN_PASSWORD`，不配则随机生成并打在启动日志里），登录后请立即调 `/api/auth/change-password` 改掉。
- 角色四档：`ADMIN` 全局、`DEPT_ADMIN` 管本部门、`EDITOR` 可上传待审批、`USER` 只能问答；用户有 `department`（归属部门，管理员为 null）；普通用户只能访问本部门的文档，传别的部门报 403。
- 登录加固：连续 5 次密码错误锁定账号 15 分钟；开了双因素的账号登录时多传 `totpCode`（6 位动态验证码，见 [双因素](#双因素认证totp)）；密码策略：至少 10 位且同时含字母和数字。
- 限流走数据库（多实例共用）：已登录用户每分钟 120 次，未登录按 IP 每分钟 30 次，超了返回 429。
- 企业登录（OIDC）：见 [部署](deployment.md) 配好 IdP 后，登录页出现“企业账号登录”按钮；点它跳去 IdP 登录，回来自动进应用。首次登录自动建一个 USER 账号，管理员用 `PUT /api/admin/users/{username}` 调它的角色和部门。相关接口：`GET /api/auth/oidc/enabled`（查是否开启）、`POST /api/auth/oidc/exchange`（凭回调的一次性码换 Token，5 分钟有效、用过即废）。
- 对外 API Key：给别的系统调数据接口用，不用账号密码登录。请求头带 `X-API-Key: kb_...` 即可（不用再带 Bearer Token），权限就是 Key 创建者的角色；`/api/admin/**` 与 `/api/auth/**` 不认 Key，只能用 JWT。Key 在管理员的“API 密钥”页面创建，明文只显示一次。建 Key 时可限范围：`ask` 只能调问答与清单接口（`POST /api/ask`、`POST /api/ask-stream`、`POST /api/bot/ask`、`POST /api/search`、`POST /api/search/grouped`、`GET /api/documents`、`GET /api/document/versions`），`all`（默认）除管理接口外都能调；可填有效期天数（1–3650，不填永不过期），过期后调任何接口都返回 401。

## POST /api/ingest —— 上传文档入库

```bash
curl -X POST http://localhost:8080/api/ingest -H "Authorization: Bearer $TOKEN" -F "file=@docs/samples/请假制度.txt"
# 换成任意要入库的文件，如 -F "file=@/path/to/产品手册.pdf"
```

支持后缀（不区分大小写）：`.txt` 直读 UTF-8；`.pdf/.doc/.docx/.md` 走 Tika 解析；`.java` 走代码知识库链路（见 [架构](architecture.md)）。其他后缀返回 400。普通用户（USER）不能上传，报 403。

可选参数 `visibility`：该文档的可见性，`all`（全员可见，默认）或一个部门代号（如 `hr`、`finance`）；只允许小写字母、数字、下划线、中划线，别的字符报 400。同名文件重新上传时新 visibility 覆盖旧的。非管理员只能传 `all` 或自己部门的 visibility，传别的部门报 403。用到未登记的部门代号时自动在部门表里建一条（名称默认为代号），管理员可在部门管理里改名、挂父部门。

入库后文档带审批状态：管理员上传直接发布，部门管理员传本部门的文档直接发布，其余一律待审批（`pending`），须管理员或本部门的部门管理员批准后问答才能查到；驳回的文档问答查不到，删了可重新上传。返回：

```json
{"file":"请假制度.txt","chunks":1,"visibility":"hr","status":"approved"}
```

`chunks` 为入库后实际落库统计。同名文件重复上传时自动整体替换，清单块数不翻倍；替换是先入后删——新块入库成功才删旧块，中途失败旧文档还在；版本号每次同名替换 +1（首版 v1）。

## POST /api/ingest-async —— 异步上传文档入库

```bash
curl -X POST http://localhost:8080/api/ingest-async -H "Authorization: Bearer $TOKEN" -F "file=@/path/to/任意文档.pdf"
# 返回 202 {"id":"...","source":"产品手册.pdf","username":"admin","visibility":"all","status":"PENDING","chunks":0,"error":null,"createdAt":"..."}
```

与同步上传同样的校验与审批规则，只是解析与向量化在后台线程做，不占请求线程；大文件、批量上传时用它。提交后轮询任务状态：

```bash
curl http://localhost:8080/api/ingest/jobs/<上面返回的 id> -H "Authorization: Bearer $TOKEN"
# 处理中 {"status":"RUNNING",...}；完成 {"status":"DONE","chunks":12,...}；失败 {"status":"FAILED","error":"文件解析失败（格式损坏或无法识别）",...}
```

任务只能本人或管理员查，别人的任务按不存在处理（404）。任务保留 7 天，过期自动清理。

## POST /api/ask —— 检索问答

```bash
curl -X POST http://localhost:8080/api/ask -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

返回：

```json
{"answer":"……[1]","sources":["请假制度.txt"]}
```

- `sources` 第 i 个元素对应回答中的 `[i]` 引用编号；`hits` 同时给出每条引用的综合分与命中的原文块。检索是混合的：向量检索（语义）+ 关键词检索（SQL ILIKE 候选，中文按二字滑动分词，按词频总分排序），两路用 RRF（倒数排名融合，k=60）合并取前 N；`hits.score` 是 RRF 融合分（约 0–0.03，越大越相关），不再是余弦相似度；`minScore` 只卡向量那一路，关键词命中的块不受它限制。无命中时不调模型，整段版与流式版都直接回“资料里没找到”；整段版回答返回前会删掉超出引用范围的编号。
- 可选入参：`sources` 数组（只在指定文档内检索）、`sessionId`（多轮追问，同会话保留问答历史）、`maxResults`（1–20，默认 5）、`minScore`（0–1，默认 0.55）、`department`（部门代号；有归属部门的用户只能查本部门，传别的报 403，管理员可用该参数指定部门，不传只查全员可见的）。
- 权限过滤在 pgvector 检索内完成（`visibility`、`status` 与 `sources` 用 `Filter.and` 组合），没权限的块和待审批的块连 prompt 都不进；已入库但没有 `visibility` 或 `status` 字段的旧块查不到，先跑回填 SQL（见 [架构](architecture.md)）。

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

可选参数 `department`：不传只列全员可见的，传了列全员可见加该部门的（有归属部门的用户只能看本部门，传别的报 403）。`status` 可选筛待审批（`pending`）/已发布（`approved`）/已驳回（`rejected`）；普通用户与编辑只看已发布的，管理员与部门管理员看全部。分页：`page` 从 0 起，`size` 1–100，默认 20；越界报 400。返回：

```json
{"documents":[{"source":"请假制度.txt","chunks":1,"visibility":"hr","status":"approved","version":1},{"source":"报销制度.txt","chunks":1,"visibility":"all","status":"pending","version":2}],"total":2,"page":0,"size":20}
```

## POST /api/document/approve —— 批准文档发布

```bash
curl -X POST "http://localhost:8080/api/document/approve?source=报销制度.txt" -H "Authorization: Bearer $TOKEN"
```

管理员可批全部，部门管理员只批本部门的；整篇文档的块统一改成已发布，问答开始能查到。返回 `{"source":"报销制度.txt","status":"approved","chunks":1}`，文档不存在返回 404。

## POST /api/document/reject —— 驳回文档

```bash
curl -X POST "http://localhost:8080/api/document/reject?source=报销制度.txt" -H "Authorization: Bearer $TOKEN"
```

权限与批准同规则；整篇文档的块统一改成已驳回，问答查不到。返回 `{"source":"报销制度.txt","status":"rejected","chunks":1}`。

## POST /api/document/rollback —— 回滚到指定旧版

```bash
curl -X POST "http://localhost:8080/api/document/rollback?source=报销制度.txt&version=1" -H "Authorization: Bearer $TOKEN"
```

权限与批准同规则（管理员全部、部门管理员本部门）。同名重新上传时旧版不删，改成 `superseded` 留着回滚：目标版恢复成它被替换之前的状态（没批过的旧版回滚后还是待审批，不会悄悄发布），当前版改成 `superseded`。`version` 必须是比当前版更早的版本号（用 `GET /api/document/versions` 查），否则报 400；目标版不存在报 404。返回 `{"source":"报销制度.txt","version":1,"chunks":1}`。

## GET /api/document/versions —— 某篇文档的全部版本号

```bash
curl "http://localhost:8080/api/document/versions?source=报销制度.txt" -H "Authorization: Bearer $TOKEN"
```

权限与批准同规则。返回 `{"source":"报销制度.txt","versions":[1,2,3]}`（从小到大，含已 superseded 的旧版），回滚下拉框用。

## DELETE /api/document?source=xxx —— 删除指定文档

```bash
curl -X DELETE "http://localhost:8080/api/document?source=报销制度.txt" -H "Authorization: Bearer $TOKEN"
```

管理员可删全部，部门管理员只删本部门的；普通用户与编辑报 403。

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

原密码不对返回 401，新密码至少 10 位且同时含字母和数字；成功返回 `{"changed":true}`。

## 双因素认证（TOTP）

用验证器 App（如 Google Authenticator）绑密钥，登录时再加 6 位动态验证码；企业登录（OIDC）见 [部署](deployment.md)，配好 IdP 后登录页出现“企业账号登录”按钮。

```bash
# 1. 生成密钥（返回 secret 与 otpauth 链接，填进验证器 App）
curl -X POST http://localhost:8080/api/auth/totp/setup -H "Authorization: Bearer $TOKEN"
# 2. 用 App 里的验证码启用
curl -X POST http://localhost:8080/api/auth/totp/enable -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"code":"123456"}'
# 3. 之后登录多传 totpCode
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"<密码>","totpCode":"123456"}'
# 关闭双因素（需校验一次密码）
curl -X POST http://localhost:8080/api/auth/totp/disable -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"password":"<密码>"}'
```

启用前密钥只存不生效；验证码错误返回 401。

## POST /api/feedback —— 问答反馈

```bash
curl -X POST http://localhost:8080/api/feedback -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？","answer":"……","helpful":true}'
```

`helpful` 为 true 好评、false 差评；问答原文截断存 500 字。管理员看好评率见 [反馈统计](#get-apiadminfeedbackstats--问答反馈统计仅-admin)。

## POST /api/admin/users —— 创建用户

```bash
curl -X POST http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"username":"zhang","password":"<至少10位含字母数字>","role":"USER","department":"hr"}'
```

`role` 只允许 ADMIN、DEPT_ADMIN、EDITOR、USER，不传默认 USER；ADMIN 必须无部门，DEPT_ADMIN 必须有所属部门。部门管理员只能创建本部门的用户（传别的部门报 403，不传默认建到自己部门）。用户名已存在返回 409。

## PUT /api/admin/users/{username} —— 改用户角色与部门（仅 ADMIN）

```bash
curl -X PUT http://localhost:8080/api/admin/users/zhang -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"role":"DEPT_ADMIN","department":"hr"}'
```

`role` 与 `department` 都可选，不传保持原值；`department` 传空串表示清空。升 ADMIN 时部门自动清空；用户不存在返回 404。OIDC 首次登录自动建的 USER 账号就用这个接口改角色和部门。

## GET /api/admin/users —— 用户清单

```bash
curl http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN"
```

返回用户列表（不含密码 hash）；管理员看全部，部门管理员只看本部门的人。

## 部门管理（仅 ADMIN）

```bash
# 部门清单
curl http://localhost:8080/api/admin/departments -H "Authorization: Bearer $TOKEN"
# 新建部门：code 为代号，parentCode 为父部门代号（可空）
curl -X POST http://localhost:8080/api/admin/departments -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"code":"tech","name":"技术部","parentCode":null}'
# 删除部门：仍有文档挂在这个可见性下时报 409
curl -X DELETE "http://localhost:8080/api/admin/departments?code=tech" -H "Authorization: Bearer $TOKEN"
```

启动时自动把已入库块里出现过的部门代号登记进来；上传时用到未登记的代号也自动建一条。

## GET /api/admin/audit-logs —— 审计日志（仅 ADMIN）

```bash
curl "http://localhost:8080/api/admin/audit-logs?page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

分页，最新在前。记登录、登录失败、OIDC 登录、上传、删除、审批、驳回、建用户、改用户、改密码、问答（问题原文截断 100 字），返回 `{"logs":[{"username":"admin","action":"UPLOAD","target":"请假制度.txt","detail":"visibility=hr status=approved","createdAt":"..."}],"total":1,"page":0,"size":20}`。

## GET /api/admin/feedback/stats —— 问答反馈统计（仅 ADMIN）

```bash
curl http://localhost:8080/api/admin/feedback/stats -H "Authorization: Bearer $TOKEN"
```

返回 `{"total":10,"helpful":8,"helpfulRate":0.8}`。

## GET /api/notifications —— 站内通知

```bash
curl "http://localhost:8080/api/notifications?page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

自己的通知，最新在前：待审批提醒（发给管理员）、通过/驳回结果（发给上传人）。分页 `page` 从 0 起，`size` 1–100，默认 20。返回 `{"notifications":[{"id":"...","type":"PENDING_APPROVAL","title":"有文档待审批","body":"文档「报销制度.txt」已上传，待你审批。","relatedSource":"报销制度.txt","read":false,"createdAt":"..."}],"total":1,"unread":1,"page":0,"size":20}`。点一条通知标已读：`POST /api/notifications/{id}/read`；全部标已读：`POST /api/notifications/read-all`（返回 `{"updated":3}`）。

## API 密钥管理（仅 ADMIN）

```bash
# 创建：名字 1-64 字；scope 不传默认 all；expiresInDays 不传表示永不过期；返回的 key 明文只出现这一次，关闭后查不到
curl -X POST http://localhost:8080/api/admin/api-keys -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"oa-同步","scope":"ask","expiresInDays":90}'
# 返回 {"id":"...","key":"kb_..."}

# 列表（不含明文）：名字、创建者、范围、有效期至、创建于、最后使用、状态
curl http://localhost:8080/api/admin/api-keys -H "Authorization: Bearer $TOKEN"

# 吊销：不存在或已吊销返回 404
curl -X DELETE http://localhost:8080/api/admin/api-keys/<id> -H "Authorization: Bearer $TOKEN"
# 返回 {"id":"...","revoked":true}
```

Key 归创建者所有，权限就是创建者的角色；库里只存 SHA-256 哈希。调数据接口时请求头带 `X-API-Key: kb_...`（见[鉴权](#鉴权除登录外所有接口都要带-token)），`/api/admin/**` 与 `/api/auth/**` 不认 Key。范围 `ask` 的 Key 只能调问答、机器人问答与清单接口，调入库、删除、审批等返回 401；过了有效期的 Key 调任何接口都返回 401。

## POST /api/bot/ask —— 机器人问答（API Key 或 Token）

给企业微信/钉钉机器人或外部系统对接；鉴权走请求头 `X-API-Key: kb_...`（范围 `ask` 即可）或 Bearer Token。接口与 `/api/ask` 共用同一套检索、标准答案优先与盲区记录，部门权限按 Key 主人（或登录用户）的规则算。IM 平台的回调地址、加解密与验签在企业 IM 管理后台配置后转发到本接口，代码不含这些配置。

入参兼容两种消息体，二选一：通用 `{"question":"..."}`，或钉钉/企微文本消息 `{"msgtype":"text","text":{"content":"..."}}`。`platform` 可选，传 `dingtalk` / `wecom` 时按 IM 回复格式返回，否则返回通用出参。

```bash
# 通用入参 + 通用出参
curl -X POST http://localhost:8080/api/bot/ask -H "X-API-Key: kb_xxx" -H "Content-Type: application/json" \
  -d '{"question":"请假超过3天找谁批？"}'
# 返回 {"answer":"……[1]","sources":["请假制度.txt"]}

# 钉钉/企微入参 + IM 出参
curl -X POST http://localhost:8080/api/bot/ask -H "X-API-Key: kb_xxx" -H "Content-Type: application/json" \
  -d '{"platform":"wecom","msgtype":"text","text":{"content":"请假超过3天找谁批？"}}'
# 返回 {"msgtype":"text","text":{"content":"……[1]"}}
```

`platform` 大小写与首尾空格不敏感；两种消息体都没有问题文本时返回 400“question 不能为空”。

## 标准答案（置顶 FAQ，仅 ADMIN / DEPT_ADMIN）

标准答案是管理员维护的权威答案：命中后问答直接返回，不走检索。匹配规则见 [架构](architecture.md)：问题原文一致（忽略大小写），或问题里包含任一关键词（逗号分隔，忽略大小写）。管理员管全部，部门管理员只管本部门的（`all` 全员的归管理员），普通用户不能调用（403）。

```bash
# 新建：question 1-200 字必填，answer 1-5000 字必填，keywords 可选最长 200 字，department 留空为 all
curl -X POST http://localhost:8080/api/faq -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"请假超过3天找谁批？","answer":"部门经理+HR 共同审批","keywords":"请假,审批","department":"all"}'
# 返回 {"id":"...","question":"...","answer":"...","keywords":"...","department":"all","enabled":true,"createdAt":"...","updatedAt":"..."}

# 清单：管理员看全部，部门管理员只看本部门
curl http://localhost:8080/api/faq -H "Authorization: Bearer $TOKEN"

# 修改：answer/keywords/department/enabled 中不传的字段不更新；标题不可改
curl -X PUT http://localhost:8080/api/faq/{id} -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"answer":"新的权威答案","enabled":false}'

# 删除
curl -X DELETE http://localhost:8080/api/faq/{id} -H "Authorization: Bearer $TOKEN"
# 返回 {"deleted":true}
```

部门管理员建/改条目时生效部门强制为本部门，传别的部门报 403；改、删别人的条目报 403。新建、修改、删除都会记审计日志。

## 知识条目（在线撰写入库）

在线写正文直接入库，不必做成文件上传。正文原文存在 `knowledge_entry` 表供编辑回填，同时复用文件上传的同名替换管线切块向量化（块元数据 `source` 为标题 + `.md`）；审批状态、版本号、可见性与部门权限规则与文件上传完全一致。写权限同上传：普通用户不能写（403），编辑写的待审批，管理员/部门管理员直接发布。

```bash
# 新建：title、content 必填；category、tags、visibility 可选
curl -X POST http://localhost:8080/api/knowledge -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"项目报销指引","content":"高铁二等座实报实销……","category":"财务","tags":"报销,差旅","visibility":"all"}'
# 返回 {"id":"...","title":"...","source":"项目报销指引.md","chunks":3,"visibility":"all","status":"approved"}

# 清单：department 可选（管理员用）；status 可选 pending/approved/rejected；非管理视角只看已发布的（本人写的待审批也能看到）
curl "http://localhost:8080/api/knowledge?department=hr&status=approved" -H "Authorization: Bearer $TOKEN"
# 返回 [{"id":"...","title":"...","source":"...","category":"...","tags":"...","visibility":"all","status":"approved","username":"...","createdAt":"...","updatedAt":"..."}]

# 详情（含正文，编辑回填用）；不可见的人按 404 处理
curl http://localhost:8080/api/knowledge/{id} -H "Authorization: Bearer $TOKEN"

# 更新并重新入库：content/category/tags/visibility 不传的沿用原值，标题不可改
curl -X PUT http://localhost:8080/api/knowledge/{id} -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"content":"新的正文……","visibility":"hr"}'

# 删除：条目与所有版本的块一起删
curl -X DELETE http://localhost:8080/api/knowledge/{id} -H "Authorization: Bearer $TOKEN"
# 返回 {"deleted":true}
```

`status` 传 `pending`、`approved`、`rejected` 之外的值报 400；`department`、`visibility` 只允许小写字母、数字、下划线、中划线。

## 会话历史（/api/sessions）

问答消息落库保存，与喂模型用的短期记忆（24 小时清理）相互独立，长期保留，支持跨设备同步。只查自己的会话，别人的会话按空列表处理。前端已接入，正常用页面即可；下面接口供排查与对接：

```bash
# 我的会话清单：最近更新在前，上限 100 个
curl http://localhost:8080/api/sessions -H "Authorization: Bearer $TOKEN"
# 返回 [{"id":"...","title":"...","updatedAt":"..."}]

# 某个会话的消息回放：时间正序；role 为 user/assistant，refs 为引用列表
curl http://localhost:8080/api/sessions/{sessionId}/messages -H "Authorization: Bearer $TOKEN"
# 返回 [{"role":"user","content":"请假超过3天找谁批？","refs":null,"createdAt":"..."},
#       {"role":"assistant","content":"……[1]","refs":[...],"createdAt":"..."}]

# 删除会话与它的全部消息
curl -X DELETE http://localhost:8080/api/sessions/{sessionId} -H "Authorization: Bearer $TOKEN"
# 返回 {"deleted":true}
```

## 原件下载与文本预览

上传的原始文件字节从入库起保存在 `document_blob` 表（与文档版本同生命周期：删文档、旧版清理时同步删）。历史上传的文件没有原件，只有切块文本。存储可用 `BLOB_ENABLED` 关闭、`BLOB_MAX_SIZE_MB`（默认 10MB）限制单文件体积，超限或关闭时跳过保存但入库照常；在线撰写的知识条目不存原件（它本来就是 .md 正文，预览接口可读）。见 [部署](deployment.md)。

```bash
# 下载原件（不传 version 取当前版）；Content-Disposition 带原文件名，返回原始字节
curl -OJ -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/document/download?source=请假制度.txt&version=1"

# 在线文本预览：按块号拼回当前版全文（含章节前缀，不含原始排版）
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/document/preview?source=请假制度.txt"
# 返回 {"source":"请假制度.txt","text":"..."}
```

`GET /api/documents` 的每条记录带 `hasBlob`：为 true 表示该篇有原件可下载。下载/预览都按文档 visibility 与调用者部门做读权限校验（管理员全部、部门管理员本部门、普通用户本部门与全员可见），越权返回 403；版本不存在或没有原件返回 404。

## POST /api/search —— 仅检索（不生成答案）

只返回命中块，不调聊天大模型；向量化与神经重排仍会调用（检索必需）。入参与 `/api/ask` 同规则（question 必填，sources/department/maxResults/minScore 可选），权限走同一套部门过滤，返回 `ReferenceHit` 列表。前端问答区"仅搜索"按钮走这里。

```bash
curl -X POST http://localhost:8080/api/search -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？","department":"hr"}'
# 返回 [{"index":1,"score":0.83,"source":"请假制度.txt","text":"……"}]
```

命中标准答案时返回一条 `source="标准答案"` 的命中块。scope 为 `ask` 的 API Key 也可调此接口。该接口不写会话历史，只记一条 `SEARCH` 审计日志。

## 批量删除

四类清单都支持批量删除：先统一校验全部行级权限（任一越权整体 403），再逐条删除，返回成功与失败清单。**非原子操作**（pgvector 与 JDBC 无法本地事务统一），失败项单独列出。单次上限 50 条，重复项自动去重。

```bash
# 文档：按 source 批量删
curl -X POST http://localhost:8080/api/document/batch-delete -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"sources":["请假制度.txt","报销制度.txt"]}'

# 标准答案 / 知识条目 / 会话：按 id 批量删
curl -X POST http://localhost:8080/api/faq/batch-delete -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"ids":["<uuid>","<uuid>"]}'
curl -X POST http://localhost:8080/api/knowledge/batch-delete -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"ids":["<uuid>"]}'
curl -X POST http://localhost:8080/api/sessions/batch-delete -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"ids":["web-abc123"]}'
```

返回 `{"requested":2,"deleted":["..."],"failed":[]}`。文档批量删会连关联的知识条目行与原件一起删；会话按当前用户名天然隔离，别人的会话进 `failed`。列表为空或超过 50 条返回 400。

## GET /api/admin/stats/trend —— 近 N 天趋势（仅 ADMIN）

按天聚合问答反馈、知识盲区与入库任务，缺失的日期补 0，返回按日期升序的连续序列；前端总览的 SVG 柱状图用它。

```bash
curl "http://localhost:8080/api/admin/stats/trend?days=7" -H "Authorization: Bearer $TOKEN"
# 返回 [{"date":"2026-11-20","feedback":12,"helpful":10,"misses":2,"ingests":3}, ...]
```

`days` 默认 7，允许 1-30；非管理员返回 403。

## 导出、审批增强、版本对比、消息级操作与标签

```bash
# 导出：dataset 为 audit/miss/docs/sessions，format 为 csv(默认)/json/md，仅 ADMIN
curl -OJ -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/admin/export?dataset=audit&format=csv"
curl -OJ -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/admin/export?dataset=miss&format=md"

# 驳回带理由：理由写进审计 detail 与上传人通知 body
curl -X POST "http://localhost:8080/api/document/reject?source=报销制度.txt&reason=格式不符" \
  -H "Authorization: Bearer $TOKEN"

# 批量审批 / 批量改可见性（先统一校验权限，再逐条执行，非原子）
curl -X POST http://localhost:8080/api/document/batch-review -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sources":["a.txt","b.txt"],"status":"approved"}'
curl -X POST http://localhost:8080/api/document/batch-visibility -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"sources":["a.txt"],"visibility":"hr"}'
# 返回 {"requested":2,"reviewed":[...],"failed":[]} / {"requested":1,"updated":[...],"failed":[]}

# 版本对比：preview 支持 version 参数，不传取当前版
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/document/preview?source=a.txt&version=1"

# 消息级操作（按当前用户名隔离）
curl -X PUT "http://localhost:8080/api/messages/{id}/star?starred=true" -H "Authorization: Bearer $TOKEN"
curl -X DELETE "http://localhost:8080/api/messages/{id}" -H "Authorization: Bearer $TOKEN"
curl "http://localhost:8080/api/messages/starred" -H "Authorization: Bearer $TOKEN"

# 标签/分类字典（动态聚合，不建字典表）
curl "http://localhost:8080/api/admin/tags" -H "Authorization: Bearer $TOKEN"
# 返回 {"categories":[{"name":"财务","cnt":3}],"tags":[{"name":"报销","cnt":5}]}
curl "http://localhost:8080/api/knowledge?category=财务&tag=报销" -H "Authorization: Bearer $TOKEN"

# 按来源分组的检索结果（前端"仅搜索"用）；每条 hit 带 matchedTerms 供高亮
curl -X POST http://localhost:8080/api/search/grouped -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

`/api/search` 与 `/api/search/grouped` 都走同一套检索主干（向量化 + 并行混合检索 + 神经重排），不调聊天大模型；向量路不支持真分页，采用取前 N 截断（`maxResults` 1-20）。引用块点"查看原文"走 `/api/document/preview`，定位到整篇文本（不按块号锚点）。

## 健康检查

```bash
curl http://localhost:8080/actuator/health
```

`/actuator/health` 与 `/actuator/info` 不用登录；`/actuator/metrics` 与 `/actuator/prometheus` 需带 Token（已登录用户）。

## 错误体

所有接口错误统一返回：

```json
{"timestamp":"2026-11-16T03:00:00Z","status":400,"message":"question 不能为空","path":"/api/ask"}
```

- `status` 与 HTTP 状态码一致；`message` 为中文原因；`path` 为请求路径。
- 业务校验失败（如空文件、扫描版 PDF、语法错的 `.java`、入参越界）返回 400；未登录或 Token 过期返回 401；权限不够返回 403；请求过于频繁返回 429；未预期的内部错误返回 500，`message` 固定为“服务内部错误”，详情只记日志。
