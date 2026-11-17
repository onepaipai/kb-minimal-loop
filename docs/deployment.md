# 部署

## 前置条件

- JDK 17、Maven 3.9+（本机 Maven 默认 Java 11 时，启动前需 `export JAVA_HOME` 指向 JDK 17）
- Neon 建好项目和数据库，连接串使用**直连端点**（不要 `-pooler` 端点）
- DeepSeek 平台 API Key、阿里云 DashScope API Key

## 环境变量（12 个，模型与数据库的 5 个必填，其余可选）

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | `sk-xxx` |
| `DASHSCOPE_API_KEY` | DashScope API Key | `sk-xxx` |
| `DB_URL` | Neon JDBC 直连地址 | `jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` |
| `DB_USER` | 数据库用户名 | `neondb_owner` |
| `DB_PASSWORD` | 数据库密码 | `******` |
| `JWT_SECRET` | JWT 签名密钥，至少 32 个字符；不配则启动时随机生成（重启后旧 Token 失效） | `换成你自己的长随机串` |
| `ADMIN_USERNAME` | 初始管理员账号，默认 `admin` | `admin` |
| `ADMIN_PASSWORD` | 初始管理员密码；不配则随机生成并打在启动日志里，登录后立即修改 | `******` |
| `OIDC_ENABLED` | 企业登录开关，默认 `false`（不开则 OIDC 不存在，应用不受影响） | `true` |
| `OIDC_CLIENT_ID` | IdP 上注册的应用 Client ID | `kb-minimal-loop` |
| `OIDC_CLIENT_SECRET` | IdP 上注册的应用 Client Secret | `******` |
| `OIDC_ISSUER_URI` | IdP 的 Issuer 地址（如 Keycloak 的 realm 地址） | `https://idp.example.com/realms/company` |

## 企业登录 OIDC（可选）

在 IdP（Keycloak / 企业微信 / 钉钉 / Azure AD / Authing 等支持 OIDC 的都行）上注册一个应用：

1. 回调地址（Redirect URI）填 `http://<你的域名或 IP>:8080/login/oauth2/code/kb`；
2. 拿回 Client ID、Client Secret、Issuer URI，配上面 4 个环境变量，重启；
3. 登录页出现“企业账号登录”按钮，点它跳去 IdP 登录，回来自动进应用；首次登录自动建一个 USER 账号（用户名取邮箱本地部分），管理员在用户管理里调它的角色和部门。

注意：开启后启动时要能连到 IdP（拉取 discovery 文档），IdP 挂了启动会失败；不开则完全不受影响。用户首次登录后，管理员需在用户管理里把角色从默认 USER 改成需要的角色。

## 启动

Git Bash：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx
export DB_URL='jdbc:postgresql://ep-xxx.xxx.aws.neon.tech/neondb?sslmode=require'
export DB_USER=neondb_owner
export DB_PASSWORD=xxx
export JWT_SECRET='换成你自己的长随机串'
export ADMIN_PASSWORD='初始管理员密码'
export JAVA_HOME=/d/Java/Java17
mvn clean spring-boot:run
```

Windows CMD：

```bat
set DEEPSEEK_API_KEY=sk-xxx
set DASHSCOPE_API_KEY=sk-xxx
set DB_URL=jdbc:postgresql://ep-xxx.xxx.aws.neon.tech/neondb?sslmode=require
set DB_USER=neondb_owner
set DB_PASSWORD=xxx
set JWT_SECRET=换成你自己的长随机串
set ADMIN_PASSWORD=初始管理员密码
mvn clean spring-boot:run
```

启动时 Spring 自动执行 `schema.sql`（建 extension、建表、建 HNSW 索引，全部 `IF NOT EXISTS`，可重复执行；`chat_memory` 与 `app_user` 两张表也是这里建的）。

## 首次登录

启动后库里没有管理员时自动播种一个（见上表 `ADMIN_USERNAME`/`ADMIN_PASSWORD`）：

```bash
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"<初始密码>"}'
```

拿到 `token` 后每个请求带 `Authorization: Bearer <token>`（有效期 8 小时）；登录后立即调 `POST /api/auth/change-password` 改掉初始密码，再用管理员账号创建普通用户（见 [接口](api.md)）。

## 运行期配置

- 上传上限：单文件 20MB（PDF 带图体积大），请求总量 30MB，超出直接拒绝；超过 5MB 的旧说法已作废，以 `application.yml` 的 `spring.servlet.multipart` 为准。
- 上传的 `.txt` 必须 UTF-8 编码，否则中文会乱码。
- Tika 包体积大，打包与首次解析偏慢属正常；Tika 不含 OCR，扫描版 PDF 返回 400。
