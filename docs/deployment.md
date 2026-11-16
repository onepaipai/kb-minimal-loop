# 部署

## 前置条件

- JDK 17、Maven 3.9+（本机 Maven 默认 Java 11 时，启动前需 `export JAVA_HOME` 指向 JDK 17）
- Neon 建好项目和数据库，连接串使用**直连端点**（不要 `-pooler` 端点）
- DeepSeek 平台 API Key、阿里云 DashScope API Key

## 环境变量（5 个，全部必填，不写死进代码）

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | `sk-xxx` |
| `DASHSCOPE_API_KEY` | DashScope API Key | `sk-xxx` |
| `DB_URL` | Neon JDBC 直连地址 | `jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` |
| `DB_USER` | 数据库用户名 | `neondb_owner` |
| `DB_PASSWORD` | 数据库密码 | `******` |

## 启动

Git Bash：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx
export DB_URL='jdbc:postgresql://ep-xxx.xxx.aws.neon.tech/neondb?sslmode=require'
export DB_USER=neondb_owner
export DB_PASSWORD=xxx
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
mvn clean spring-boot:run
```

启动时 Spring 自动执行 `schema.sql`（建 extension、建表、建 HNSW 索引，全部 `IF NOT EXISTS`，可重复执行）。

## 运行期配置

- 上传上限：单文件 20MB（PDF 带图体积大），请求总量 30MB，超出直接拒绝；超过 5MB 的旧说法已作废，以 `application.yml` 的 `spring.servlet.multipart` 为准。
- 上传的 `.txt` 必须 UTF-8 编码，否则中文会乱码。
- Tika 包体积大，打包与首次解析偏慢属正常；Tika 不含 OCR，扫描版 PDF 返回 400。
