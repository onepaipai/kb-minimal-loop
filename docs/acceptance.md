# 验收清单

1. `mvn clean compile` / `mvn clean package -DskipTests` 通过；`mvn test`（单元测试，不需要数据库与 Key）通过
2. 设好环境变量后 `mvn clean spring-boot:run` 启动成功（schema.sql 自动执行）
3. 上传 `docs/请假制度.txt` 返回 `chunks > 0`；`GET /api/documents` 能列出该文件
4. 问“请假超过3天找谁批？”回答“部门经理+HR 共同审批”并带 `[1]` 引用；问“食堂几点开”回答“资料里没找到”
5. 多轮：同 sessionId 先问“请假超过3天找谁批？”再问“那1天以内的呢？”，第二轮能按资料答“向直属主管口头说明即可”且标注引用
6. 定向检索：`sources:["报销制度.txt"]` 问“高铁能报多少钱？”能答“二等座实报实销”
7. 删除：`DELETE /api/document?source=报销制度.txt` 返回的 `deletedChunks` 与清单中该文档块数一致，再查清单该项消失
8. 校验：question 为空、maxResults=0、minScore=2 均返回 400
9. 重复上传：同一文件传两次，清单块数不翻倍（同名整体替换）；内容完全没变时第二次返回 `skipped=true`，不重新向量化；内容变了仍走替换、版本号 +1
10. 接口异常返回统一错误体 `{"timestamp","status","message","path"}`，不含堆栈文本
11. 多格式：上传 .pdf 与 .docx 后 `GET /api/documents` 能列出且块数 >0，各自内容能检索回答并带引用
12. 扫描版 PDF（纯图片无文字层）上传返回 400“解析后无有效文本内容”，不静默存 0 块
13. 上传 .exe 等非白名单后缀返回 400
14. Java 单文件：上传 `src/main/java/com/example/kb/service/IngestService.java` 后 `GET /api/documents` 能列出且块数 = 方法块数 + 1 个类头块（单个方法超 8000 字符会拆成多块）；问“ingest 是怎么入库的”能答对并带文件与方法引用；语法错的 .java 报 400 且旧块还在；含嵌套类的方法引用里标的是嵌套类名，不是外层类名
15. 流式问答：POST /api/ask-stream 问知识库里有的问题，能逐 token 收到内容，sources 事件先到、done 最后到，答案与 /api/ask 一致并带引用；问资料里没有的问题，流式答案也是“资料里没找到”；maxResults=0 或 minScore=2 报 400
16. 权限过滤：上传 `docs/请假制度.txt`（visibility=hr）后，不带 department 问请假问题答“资料里没找到”；带 department=hr 问能答对并带 `[1]` 引用；带 department=finance 问答“资料里没找到”；`GET /api/documents?department=finance` 里没有这篇，`?department=hr` 里有；同一篇改 visibility=all 重新上传后，不带 department 也能问到
17. 组合过滤：sources=["报销制度.txt"] 且 department=finance，而报销制度是 hr 的，问报销问题答“资料里没找到”（两个过滤同时生效）
18. 串部门：同一 sessionId，先用 department=hr 问请假问题，再用 department=finance 追问“那1天以内的呢？”，第二轮不得引用第一轮的答案、不得答出 hr 文档的内容；visibility 传空字符串或带空格/中文报 400
19. 鉴权：不带 Token 调 `/api/ask` 返回 401；用错误密码登录返回 401；登录成功拿到 Token 后问答正常；普通用户（USER，department=hr）调 `/api/ask` 带 department=finance 返回 403，不带 department 时只能查到 hr 与 all 的文档；普通用户调 DELETE 返回 403，管理员调 DELETE 成功；部门管理员调 DELETE 删本部门文档成功、删别的部门返回 403；普通用户上传返回 403，编辑（EDITOR）上传成功
20. 用户管理：管理员创建用户（role=USER，department=hr）成功，再用新账号登录能问答；重复用户名返回 409；密码少于 10 位或不含字母/数字返回 400；改密码后旧密码登录失败、新密码能登录；部门管理员只能创建本部门的用户，创建别的部门返回 403，查用户清单只看到本部门的人
21. 同名替换先入后删：上传 `docs/请假制度.txt` 后，故意让第二次上传失败（如传损坏的同名 .pdf），旧文档仍在且能问到；正常重新上传后块数不翻倍
22. 分页：`GET /api/documents?page=0&size=1` 只返回 1 条且 `total` 为全部文档数；page=-1 或 size=0 或 size=101 返回 400
23. 会话持久化：重启服务后，同一账号同一 sessionId 追问“那1天以内的呢？”仍能接上上一轮；24 小时未更新的会话被清理（查 chat_memory 表无该行）
24. CI：push 到 main 后 GitHub Actions 的 `mvn -B verify` 通过（单元测试不需要数据库与 Key）
25. 前端：打开 `http://localhost:8080/` 是品牌登录分栏；登录后默认进总览仪表盘，文档数、知识块数、用户数、待审批数、问答好评率与最近入库文档都显示，数字与接口一致；左侧边栏切总览、智能问答、文档管理、审批、部门管理、审计日志、用户管理、改密码；问答是对话气泡逐字流式出、引用分数与原文都显示，回答下有点赞/点踩，示例问题点一下进输入框，Enter 发送；上传文档（提交后显示“处理中”，完成后显示块数并刷新清单）、清单分页与删除、审批、部门管理、审计日志、管理员建用户、改密码、双因素开关都能用；Access Token 过期后调接口自动换新成功，只有 Refresh Token 也过期或被吊销才回登录页；普通用户看不到删除按钮与用户管理区
34. Refresh Token：登录返回里有 refreshToken；等 Access Token 过期（或手动调一个过期 Token）后调 `/api/auth/refresh` 拿到新的一对，旧 Refresh Token 再调返回 401；调 `/api/auth/logout` 后该 Refresh Token 再调返回 401；改密码后旧 Refresh Token 再调返回 401，要重新登录；前端放 8 小时以上不用，回来点问答不用重新登录直接能问
35. OIDC 企业登录：在 IdP 注册好应用并配 4 个环境变量后，登录页出现“企业账号登录”按钮，点它跳去 IdP 登录，回来自动进应用；首次登录自动建一个 USER 账号（管理员调 `PUT /api/admin/users/{username}` 改它的角色和部门后生效）；`GET /api/auth/oidc/enabled` 在未开启时返回 `{"enabled":false}` 且按钮不显示
36. 异步入库：调 `POST /api/ingest-async` 立即返回 202 与任务号，轮询 `GET /api/ingest/jobs/{id}` 先后看到 PENDING/RUNNING 再到 DONE（`chunks > 0`），清单里出现该文档；传一个损坏的 PDF，任务最终是 FAILED 且 error 里有原因；别人的任务 id 调查询返回 404
37. 集成测试：配好真实库与真实 Key 后，`KB_IT_ENABLED=true KB_IT_ADMIN_USER=admin KB_IT_ADMIN_PASSWORD=<管理员密码> DB_URL=... DB_USER=... DB_PASSWORD=... DEEPSEEK_API_KEY=... DASHSCOPE_API_KEY=... mvn -Dtest='*IT' test` 三条（鉴权、入库问答、审批）全部通过；不配这些环境变量时 `mvn test` 跳过它们，单元测试仍全过
38. 关键词词频排序：入库两篇文档，一篇只提一次关键词、另一篇提多次，问该关键词时提多次的排在前面（`hits` 顺序）
39. 引用校验与无命中：问一个资料里没有的问题，整段版与流式版都直接回“资料里没找到”且不调模型（看不到模型编造的答案）；有命中时回答里的引用编号都不超出 `hits` 的范围，且每个引用对应的句子与被引块共享有效词，否则编号被删掉（正文不动）
40. 代码入库：上传一个带 public 类的 `.java` 文件能正常入库（之前 `getPrimaryType()` 从字节数组解析时因无文件名永远为空，任何 `.java` 都报 400“文件中没有类定义”）；传一个没有类定义的 `.java` 仍报 400
26. 审批：编辑上传一篇文档后返回 `status=pending`，问答查不到它，清单里普通用户看不到、管理员能看到待审批；管理员批准后问答能查到，驳回后问答查不到；同名重新上传后版本号 +1；清单 `?status=pending` 只列待审批的
27. 部门管理：新建部门（code=tech、name=技术部、parentCode 为空）成功，清单里有；上传 visibility=tech 的文档后部门表里自动有 tech；删仍有文档的部门返回 409，文档删掉后能删
28. 登录加固：连续 5 次输错密码后账号锁定 15 分钟（登录返回 401“账号已锁定”），15 分钟后或成功登录后恢复；开通 TOTP 后登录不带验证码返回 401，带正确验证码能登录，带错的返回 401；关闭双因素后登录不再要验证码
29. 混合检索：问“请假超过3天找谁批”与只用关键词能命中的问法（如“高铁二等座”）都能答对并带引用；`hits.score` 是 RRF 融合分；`minScore` 调高后向量分不够但关键词命中的块仍可能出现
30. 问答反馈：对一条回答点好评，管理员调 `/api/admin/feedback/stats` 的 total 加 1、好评率变化
31. 审计日志：上传、删除、审批、建用户、登录、问答后，管理员调 `/api/admin/audit-logs` 能看到对应记录（最新在前，分页正常）；部门管理员调审计日志返回 403
32. 健康检查：`GET /actuator/health` 不用登录返回 `{"status":"UP"}`；`GET /actuator/prometheus` 不带 Token 返回 401，带 Token 能拿到指标
33. 旧数据回填：对已有 visibility 但没有 status 的块跑架构文档里的 status 回填 SQL 后，问答能查到它们；回填前问答查不到（须先回填 visibility 与 status 两条）
41. 神经重排：`RERANK_ENABLED=true` 时问答的 `hits.score` 是重排相关度（0-1）；重排接口失败或 `RERANK_ENABLED=false` 时问答仍正常，顺序回落 RRF 融合
42. 同义词与错别字：问“休假”能命中只写“请假”的块；问“请加制度”（错别字）仍能命中“请假制度”的块（ILIKE 无命中时 pg_trgm 兜底）
43. 版本回滚：同一文档传两次后版本号是 2，清单里块数只算当前版；调 `POST /api/document/rollback?source=...&version=1` 后清单版本回到 1，问答查到的是旧版内容；回滚到当前版或不存在的版本报 400/404；没批过的旧版回滚后还是待审批，问答查不到
44. 站内通知：编辑上传一篇待审批文档后，管理员的通知列表里多一条“有文档待审批”，未读数加 1；管理员批准后上传人的通知里多一条“文档已通过审批”；点一条标已读后未读数减 1，全部标已读后未读数为 0
45. 对外 API Key：管理员建一个 Key 后，用 `X-API-Key: kb_...` 不带 Token 调 `/api/ask` 能正常问答；调 `/api/admin/users` 返回 401；吊销后调 `/api/ask` 返回 401；建 Key 时返回的明文只出现一次，列表里查不到明文
46. 批量上传：前端一次选 3 个文件提交，逐个入库，列表里每个文件都有自己的状态（已入库 x 块 / 入库失败：原因），3 个都入库后清单里出现 3 篇文档
47. 同名替换原子化：同一文档正在重新上传时问答查到的还是旧版内容，不会同时看到新旧两版的重复块；新版入库成功后问答查到新版内容，旧版进版本列表可回滚
48. 旧版清理：同一文档传 5 次后，版本列表里最多剩当前版加 3 个旧版（每小时清理一次），再早的旧版回滚接口报 404；当前版与待审批的版永远不被清理
49. 流式引用清洗：流式问答发出来的答案里不会出现超出本次块数的引用编号（如只有 3 块却写了 [9]），和整段版同一条规则；引用编号拆成几个 token 发来也一样过滤
50. Excel/PPT 与扫描 PDF：上传 .xlsx/.pptx 能入库，问答能命中里面的文字；扫描版 PDF 在装了 Tesseract 的机器上能入库并命中图片里的文字，没装时上传报 400 并提示安装 Tesseract
51. API Key 有效期与范围：建 Key 时选“仅问答”+ 90 天有效期，调 /api/ask 能用、调 /api/ingest 返回 401；90 天后调 /api/ask 也返回 401；不填有效期的 Key 不会过期
