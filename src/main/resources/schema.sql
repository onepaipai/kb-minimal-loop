CREATE EXTENSION IF NOT EXISTS vector;
-- 模糊检索：pg_trgm 供 word_similarity 兜底错别字；trigram 索引加速 ILIKE '%词%' 与 word_similarity
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS chunk_embedding_text_trgm_idx ON chunk_embedding USING gin (text gin_trgm_ops);
CREATE TABLE IF NOT EXISTS chunk_embedding (embedding_id UUID PRIMARY KEY, embedding vector(1024) NOT NULL, text TEXT NULL, metadata JSONB NULL);
CREATE INDEX IF NOT EXISTS chunk_embedding_hnsw_idx ON chunk_embedding USING hnsw (embedding vector_cosine_ops);
CREATE TABLE IF NOT EXISTS chat_memory (id TEXT PRIMARY KEY, messages JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS app_user (id UUID PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, role TEXT NOT NULL, department TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
-- 登录加固字段：TOTP 双因素、失败次数与锁定时间（老库启动时自动补列）
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS totp_secret TEXT NULL;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ NULL;
-- 部门注册表：代号主键，parent_code 搭组织架构树；上传时用到未登记的代号自动建一条（name 默认为代号）
CREATE TABLE IF NOT EXISTS department (code TEXT PRIMARY KEY, name TEXT NOT NULL, parent_code TEXT NULL REFERENCES department(code), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
-- 审计日志：谁、在什么时候、对什么做了什么；只增不改
CREATE TABLE IF NOT EXISTS audit_log (id UUID PRIMARY KEY, username TEXT NOT NULL, action TEXT NOT NULL, target TEXT NULL, detail TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS audit_log_created_idx ON audit_log (created_at DESC);
-- 问答反馈：好评/差评 + 原问答摘要，供管理员看好评率
CREATE TABLE IF NOT EXISTS feedback (id UUID PRIMARY KEY, username TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL, helpful BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
-- 数据库限流：固定窗口计数，多实例共用一张表（key 为 user:用户名 或 ip:地址，窗口按分钟对齐）
CREATE TABLE IF NOT EXISTS rate_limit (rate_key TEXT PRIMARY KEY, window_start TIMESTAMPTZ NOT NULL, request_count INTEGER NOT NULL);
-- Refresh Token：只存 SHA-256 哈希，不存明文；吊销后与过期的一起由定时任务清理
CREATE TABLE IF NOT EXISTS refresh_token (token_hash TEXT PRIMARY KEY, username TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS refresh_token_username_idx ON refresh_token (username);

-- OIDC 企业登录：app_user 绑 IdP 的 subject（唯一），oidc_code 是一次性换码表
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS oidc_subject TEXT NULL UNIQUE;
CREATE TABLE IF NOT EXISTS oidc_code (code_hash TEXT PRIMARY KEY, username TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

-- 异步入库任务：状态 PENDING/RUNNING/DONE/FAILED，7 天前的任务每小时清理
CREATE TABLE IF NOT EXISTS ingest_job (id UUID PRIMARY KEY, source TEXT NOT NULL, username TEXT NOT NULL, visibility TEXT NOT NULL, status TEXT NOT NULL, chunks INTEGER NOT NULL DEFAULT 0, error TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS ingest_job_username_idx ON ingest_job (username, created_at DESC);

-- 站内通知：待审批提醒发给管理员，通过/驳回发给上传人；只增，已读用 is_read 标记
CREATE TABLE IF NOT EXISTS notification (id UUID PRIMARY KEY, username TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, body TEXT NULL, related_source TEXT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS notification_username_idx ON notification (username, created_at DESC);

-- 对外 API Key：只存 SHA-256 哈希，不存明文；吊销后失效；last_used_at 供管理员看调用情况
-- scope：all（除管理接口外都能调，等同于 Key 主人的角色）或 ask（只能问答、清单与机器人接口）；expires_at 为空表示永不过期
CREATE TABLE IF NOT EXISTS api_key (id UUID PRIMARY KEY, key_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL, username TEXT NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, last_used_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), scope TEXT NOT NULL DEFAULT 'all', expires_at TIMESTAMPTZ NULL);
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'all';
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL;
CREATE INDEX IF NOT EXISTS api_key_username_idx ON api_key (username);

-- 原始文件字节：上传时与切块一起存，供下载原格式；与文档版本同生命周期（删除/旧版清理时同步删）
CREATE TABLE IF NOT EXISTS document_blob (source TEXT NOT NULL, version INTEGER NOT NULL, filename TEXT NOT NULL,
    content_type TEXT NULL, file_size INTEGER NOT NULL, bytes BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, version));
CREATE INDEX IF NOT EXISTS document_blob_source_idx ON document_blob (source);

-- 知识盲区：问答没答上（检索无命中或模型明确答“资料里没找到”）时落一条，盲区看板按问题原文聚合
CREATE TABLE IF NOT EXISTS question_miss (id UUID PRIMARY KEY, username TEXT NOT NULL, department TEXT NULL, question TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS question_miss_question_idx ON question_miss (question);

-- 标准答案/置顶 FAQ：管理员维护的权威答案，问答时优先于检索直接返回；keywords 为逗号分隔的匹配关键词
CREATE TABLE IF NOT EXISTS standard_answer (id UUID PRIMARY KEY, question TEXT NOT NULL, answer TEXT NOT NULL, keywords TEXT NULL, department TEXT NOT NULL DEFAULT 'all', enabled BOOLEAN NOT NULL DEFAULT TRUE, username TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

-- 知识条目：在线撰写的知识，正文原文存表供编辑；切块后进 pgvector（source = 标题.md），category/tags 回填到块元数据
CREATE TABLE IF NOT EXISTS knowledge_entry (id UUID PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL UNIQUE, content TEXT NOT NULL, category TEXT NULL, tags TEXT NULL, visibility TEXT NOT NULL, status TEXT NOT NULL, username TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS knowledge_entry_updated_idx ON knowledge_entry (updated_at DESC);

-- 会话与消息（UI 层历史，跨设备同步）：与 chat_memory（喂模型、24 小时清理）相互独立，长期保留
CREATE TABLE IF NOT EXISTS chat_session (username TEXT NOT NULL, id TEXT NOT NULL, title TEXT NULL, department TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (username, id));
CREATE TABLE IF NOT EXISTS chat_message (id UUID PRIMARY KEY, username TEXT NOT NULL, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, refs JSONB NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS chat_message_session_idx ON chat_message (username, session_id, created_at);
CREATE INDEX IF NOT EXISTS chat_session_updated_idx ON chat_session (username, updated_at DESC);
