CREATE TABLE auth_rate_buckets (
    bucket_key VARCHAR(64) PRIMARY KEY,
    window_start BIGINT NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
CREATE INDEX idx_auth_rate_buckets_window ON auth_rate_buckets(window_start);
