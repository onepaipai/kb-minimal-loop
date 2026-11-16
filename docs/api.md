# 接口

基础地址 `http://localhost:8080`。错误统一返回 [错误体](#错误体)，不含堆栈。

## POST /api/ingest —— 上传文档入库

```bash
curl -X POST http://localhost:8080/api/ingest -F "file=@docs/请假制度.txt"
curl -X POST http://localhost:8080/api/ingest -F "file=@docs/产品手册.pdf"
```

支持后缀（不区分大小写）：`.txt` 直读 UTF-8；`.pdf/.doc/.docx/.md` 走 Tika 解析；`.java` 走代码知识库链路（见 [架构](architecture.md)）。其他后缀返回 400。

返回：

```json
{"file":"请假制度.txt","chunks":1}
```

`chunks` 为入库后实际落库统计。同名文件重复上传时自动整体替换，清单块数不翻倍。

## POST /api/ask —— 检索问答

```bash
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

返回：

```json
{"answer":"……[1]","sources":["请假制度.txt"]}
```

- `sources` 第 i 个元素对应回答中的 `[i]` 引用编号；`hits` 同时给出每条引用的相关度分数（0~1）与命中的原文块，便于调整 `minScore`。
- 可选入参：`sources` 数组（只在指定文档内检索）、`sessionId`（多轮追问，同会话保留问答历史）、`maxResults`（1–20，默认 5）、`minScore`（0–1，默认 0.55）。

```bash
# 定向检索
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"高铁能报多少钱？","sources":["报销制度.txt"]}'
# 多轮追问：第二轮能理解“那 1 天以内的”指代上一轮的请假话题
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？","sessionId":"demo-1"}'
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"那1天以内的呢？","sessionId":"demo-1"}'
# 调整检索参数
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"差旅报销多久内提交？","maxResults":3,"minScore":0.6}'
```

入参越界一律 400：`question` 为空、`maxResults` 超出 1–20、`minScore` 超出 0–1。

## POST /api/ask-stream —— 流式问答（SSE）

与 `/api/ask` 同一套检索、校验与多轮记忆，答案逐 token 以 SSE 推送，超时 120 秒。

```bash
curl -N -X POST http://localhost:8080/api/ask-stream -H "Content-Type: application/json" -d '{"question":"请假超过3天找谁批？"}'
```

事件顺序：`sources`（引用来源 hits）→ `token`（逐个）→ `done`；出错时连接以错误结束，前端提示重问。

## GET /api/documents —— 已入库文档清单

```bash
curl http://localhost:8080/api/documents
```

返回：

```json
{"documents":[{"source":"请假制度.txt","chunks":1},{"source":"报销制度.txt","chunks":1}]}
```

## DELETE /api/document?source=xxx —— 删除指定文档

```bash
curl -X DELETE "http://localhost:8080/api/document?source=报销制度.txt"
```

返回（`deletedChunks` 为实际删除的块数，按删除前后统计差值计算）：

```json
{"source":"报销制度.txt","deleted":true,"deletedChunks":1}
```

## 错误体

所有接口错误统一返回：

```json
{"timestamp":"2026-11-16T03:00:00Z","status":400,"message":"question 不能为空","path":"/api/ask"}
```

- `status` 与 HTTP 状态码一致；`message` 为中文原因；`path` 为请求路径。
- 业务校验失败（如空文件、扫描版 PDF、语法错的 `.java`、入参越界）返回 400；未预期的内部错误返回 500，`message` 固定为“服务内部错误”，详情只记日志。
