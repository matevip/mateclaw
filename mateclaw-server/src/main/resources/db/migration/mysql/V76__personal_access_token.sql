-- V76: Personal Access Token (RFC-03 Lane I1).
-- Lets headless / CI / SDK callers authenticate without going through
-- the interactive JWT login flow. Tokens are stored as SHA-256 hashes —
-- a DB compromise reveals which user owns which token but never the
-- plaintext value the user sees once at creation time.
--
-- token_hash is the lookup key (UNIQUE) so the auth filter can do a
-- single indexed query on every authenticated request.

CREATE TABLE IF NOT EXISTS mate_personal_access_token (
    id            BIGINT       NOT NULL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    name          VARCHAR(64),
    token_hash    CHAR(64)     NOT NULL,
    scopes        VARCHAR(255),
    last_used_at  TIMESTAMP    NULL,
    expires_at    TIMESTAMP    NULL,
    enabled       BOOLEAN      DEFAULT TRUE,
    create_time   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0,
    UNIQUE KEY uk_pat_token_hash (token_hash),
    KEY idx_pat_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
