-- mate_wiki_image_caption_cache: SHA-256 keyed image caption store.
--
-- Cache is shared across all knowledge bases — the same image bytes
-- uploaded twice (in different KBs, by different users, or to the same
-- KB at different times) cost exactly one vision-LLM call total.
--
-- The cache is content-addressed by raw image bytes; perceptual variations
-- (re-encoded JPEG, slightly cropped) are intentionally treated as misses.
-- A second-tier perceptual hash can be added later if the miss rate
-- becomes a cost concern.

CREATE TABLE IF NOT EXISTS mate_wiki_image_caption_cache (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    image_sha256    CHAR(64)     NOT NULL UNIQUE,

    -- Primary output: 2-4 sentence factual caption (≤ ~500 chars).
    caption         CLOB         NOT NULL,

    -- Best-effort OCR text recovered from the image; may be null if the
    -- vision provider didn't surface anything text-like.
    visible_text    CLOB,

    mime_type       VARCHAR(64),

    -- Model identifier reported by the provider (e.g. 'qwen-vl-max',
    -- 'gpt-4o-2024-08-06'); useful for invalidating cache on model upgrade.
    capture_model   VARCHAR(128) NOT NULL,

    -- Provider id from the SPI registry (e.g. 'dashscope-vision').
    provider_id     VARCHAR(64)  NOT NULL,

    -- Wall-clock time the original LLM call took, in milliseconds.
    duration_ms     BIGINT,

    -- How many times this row has served a lookup; bumped lazily.
    hit_count       BIGINT       NOT NULL DEFAULT 0,

    captured_at     TIMESTAMP    NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wicc_sha       ON mate_wiki_image_caption_cache (image_sha256);
CREATE INDEX IF NOT EXISTS idx_wicc_captured  ON mate_wiki_image_caption_cache (captured_at);
