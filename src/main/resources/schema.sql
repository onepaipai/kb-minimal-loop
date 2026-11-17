CREATE EXTENSION IF NOT EXISTS vector;
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
