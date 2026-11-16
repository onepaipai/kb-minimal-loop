# 验收清单

1. `mvn clean compile` / `mvn clean package -DskipTests` 通过
2. 设好 5 个环境变量后 `mvn clean spring-boot:run` 启动成功（schema.sql 自动执行）
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
14. Java 单文件：上传 `src/main/java/com/example/kb/service/IngestService.java` 后 `GET /api/documents` 能列出且块数 = 方法数 + 1 个类头块；问“ingest 是怎么入库的”能答对并带文件与方法引用；语法错的 .java 报 400 且旧块还在
15. 流式问答：POST /api/ask-stream 问知识库里有的问题，能逐 token 收到内容，sources 事件先到、done 最后到，答案与 /api/ask 一致并带引用；问资料里没有的问题，流式答案也是“资料里没找到”；maxResults=0 或 minScore=2 报 400
16. 权限过滤：上传 `docs/请假制度.txt`（visibility=hr）后，不带 department 问请假问题答“资料里没找到”；带 department=hr 问能答对并带 `[1]` 引用；带 department=finance 问答“资料里没找到”；`GET /api/documents?department=finance` 里没有这篇，`?department=hr` 里有；同一篇改 visibility=all 重新上传后，不带 department 也能问到
17. 组合过滤：sources=["报销制度.txt"] 且 department=finance，而报销制度是 hr 的，问报销问题答“资料里没找到”（两个过滤同时生效）
18. 串部门：同一 sessionId，先用 department=hr 问请假问题，再用 department=finance 追问“那1天以内的呢？”，第二轮不得引用第一轮的答案、不得答出 hr 文档的内容；visibility 传空字符串或带空格/中文报 400
