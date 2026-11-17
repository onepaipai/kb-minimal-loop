# 验收清单

1. `mvn clean compile` / `mvn clean package -DskipTests` 通过；`mvn test`（单元测试，不需要数据库与 Key）通过
2. 设好环境变量后 `mvn clean spring-boot:run` 启动成功（schema.sql 自动执行）
3. 上传 `docs/请假制度.txt` 返回 `chunks > 0`；`GET /api/documents` 能列出该文件
4. 问“请假超过3天找谁批？”回答“部门经理+HR 共同审批”并带 `[1]` 引用；问“食堂几点开”回答“资料里没找到”
5. 多轮：同 sessionId 先问“请假超过3天找谁批？”再问“那1天以内的呢？”，第二轮能按资料答“向直属主管口头说明即可”且标注引用
6. 定向检索：`sources:["报销制度.txt"]` 问“高铁能报多少钱？”能答“二等座实报实销”
7. 删除：`DELETE /api/document?source=报销制度.txt` 返回的 `deletedChunks` 与清单中该文档块数一致，再查清单该项消失
8. 校验：question 为空、maxResults=0、minScore=2 均返回 400
9. 重复上传：同一文件传两次，清单块数不翻倍（同名整体替换）
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
25. 前端：打开 `http://localhost:8080/` 是品牌登录分栏；登录后默认进总览仪表盘，文档数、知识块数、用户数、待审批数、问答好评率与最近入库文档都显示，数字与接口一致；左侧边栏切总览、智能问答、文档管理、审批、部门管理、审计日志、用户管理、改密码；问答是对话气泡逐字流式出、引用分数与原文都显示，回答下有点赞/点踩，示例问题点一下进输入框，Enter 发送；上传文档、清单分页与删除、审批、部门管理、审计日志、管理员建用户、改密码、双因素开关都能用；Token 过期后调接口回登录页；普通用户看不到删除按钮与用户管理区
26. 审批：编辑上传一篇文档后返回 `status=pending`，问答查不到它，清单里普通用户看不到、管理员能看到待审批；管理员批准后问答能查到，驳回后问答查不到；同名重新上传后版本号 +1；清单 `?status=pending` 只列待审批的
27. 部门管理：新建部门（code=tech、name=技术部、parentCode 为空）成功，清单里有；上传 visibility=tech 的文档后部门表里自动有 tech；删仍有文档的部门返回 409，文档删掉后能删
28. 登录加固：连续 5 次输错密码后账号锁定 15 分钟（登录返回 401“账号已锁定”），15 分钟后或成功登录后恢复；开通 TOTP 后登录不带验证码返回 401，带正确验证码能登录，带错的返回 401；关闭双因素后登录不再要验证码
29. 混合检索：问“请假超过3天找谁批”与只用关键词能命中的问法（如“高铁二等座”）都能答对并带引用；`hits.score` 是 RRF 融合分；`minScore` 调高后向量分不够但关键词命中的块仍可能出现
30. 问答反馈：对一条回答点好评，管理员调 `/api/admin/feedback/stats` 的 total 加 1、好评率变化
31. 审计日志：上传、删除、审批、建用户、登录、问答后，管理员调 `/api/admin/audit-logs` 能看到对应记录（最新在前，分页正常）；部门管理员调审计日志返回 403
32. 健康检查：`GET /actuator/health` 不用登录返回 `{"status":"UP"}`；`GET /actuator/prometheus` 不带 Token 返回 401，带 Token 能拿到指标
33. 旧数据回填：对已有 visibility 但没有 status 的块跑架构文档里的 status 回填 SQL 后，问答能查到它们；回填前问答查不到（须先回填 visibility 与 status 两条）
