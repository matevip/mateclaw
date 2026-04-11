-- V2: Add workspace base_path for directory restriction (RFC-002)
ALTER TABLE mate_workspace ADD COLUMN IF NOT EXISTS base_path VARCHAR(512);
