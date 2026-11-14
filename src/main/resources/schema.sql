CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS chunk_embedding (embedding_id UUID PRIMARY KEY, embedding vector(1024) NOT NULL, text TEXT NULL, metadata JSONB NULL);
CREATE INDEX IF NOT EXISTS chunk_embedding_hnsw_idx ON chunk_embedding USING hnsw (embedding vector_cosine_ops);
