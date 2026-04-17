# Upgrading MateClaw

## 1.0.x → 1.1.0

**TL;DR** — Most users have nothing to do. Restart with 1.1.0, Flyway's built-in repair heals known checksum drift, Ollama auto-discovery rewrites the bad `:latest` defaults, and everything else self-converges. Docker Compose deployments need a one-time `.env` update.

See `docs/en/releases/1.1.0.md` for the feature changelog.

---

## For everyone

### ⚠️ What happens automatically (no action)

- **Flyway migration self-heal** — 1.1.0 rewrote all MySQL migrations V2–V14 to replace unsupported `ADD COLUMN IF NOT EXISTS` syntax (Gitee #IIYHLJ). `FlywayRepairConfig` runs `flyway.repair()` on every boot, so the new checksums auto-accept and migration resumes from wherever your schema is.
- **Ollama default model** — if your 1.0.x run auto-picked a model tag Ollama no longer has (commonly `deepseek-r1:latest`), on 1.1.0 restart `OllamaAutoDiscoveryRunner` detects the broken default and re-picks a tag-capable model (e.g. `deepseek-r1:7b`, `qwen3:latest`), preferring one that supports function calling.
- **Stale `mate_model_config` rows** — idempotent seed data reconciles on each startup.

### 📋 Recommended pre-upgrade steps

1. Back up your database — `mateclaw` schema on MySQL, or `data/mateclaw.mv.db` on H2.
2. Back up `data/` directory (skill workspaces, uploaded files, memory files).
3. Note your current default model in Settings → Models in case you want to switch back.

### 🚀 Upgrade

```bash
git pull
cd mateclaw-server
mvn clean package -DskipTests
# then restart your service per your deployment method
```

Or for Desktop app users: just update to 1.1.0 via the in-app updater or re-download.

---

## For Docker Compose deployments

**One-time migration step required** — 1.1.0 refuses to start with default hardcoded passwords.

### 1. Copy-paste merge the new `.env.example` keys

```bash
cp .env .env.backup
# open .env.example — it has new required keys:
#   DB_PASSWORD=       (was default 'mateclaw123', now MUST be overridden)
#   DB_ROOT_PASSWORD=  (new, required for MySQL root)
#   JWT_SECRET=        (new, strongly recommended)
#   MATECLAW_CORS_ALLOWED_ORIGINS=  (new, strongly recommended for prod)
```

### 2. Set strong values in your `.env`

```env
# STRONG passwords — at least 16 chars, mixed case + digits + symbols
DB_PASSWORD=<your-strong-db-user-password>
DB_ROOT_PASSWORD=<different-strong-root-password>

# 32+ char random string — generate with: openssl rand -base64 48
JWT_SECRET=<your-jwt-secret>

# Production CORS allowlist — comma-separated, no wildcards
MATECLAW_CORS_ALLOWED_ORIGINS=https://mateclaw.example.com
```

If any of `DB_PASSWORD` / `DB_ROOT_PASSWORD` / `DASHSCOPE_API_KEY` is missing, `docker compose up` will fail fast with a clear error — this is intentional.

### 3. Existing MySQL volume compatibility

If you already ran 1.0.x with the old default password (`mateclaw123`), **your existing MySQL volume still has the old root password inside**. You have two options:

**Option A — keep existing password** (fastest, least secure):
Set `DB_ROOT_PASSWORD=mateclaw123` and `DB_PASSWORD=mateclaw123` in `.env` to match. Upgrade works. Then rotate after upgrade using `ALTER USER ... IDENTIFIED BY ...` inside the MySQL container.

**Option B — fresh volume with new password** (cleanest, loses DB if not backed up):
```bash
docker compose down -v   # ⚠️ deletes mysql_data volume; back up first
# edit .env with new strong password
docker compose up -d
```
Then re-import your backup if you kept one.

### 4. Restart

```bash
docker compose up -d
docker compose logs -f mateclaw-server   # watch for "Flyway Successfully applied N migrations"
```

Expected log lines during boot:
- `Flyway Successfully applied N migrations to schema mateclaw`
- `Ollama: auto-activated default model '<actual-tag>'` (if you use Ollama — should NOT say `:latest` any more)
- `[Security] Using default JWT secret!` → means you forgot to set `JWT_SECRET` — fix and restart

---

## For local dev / H2 deployments

No action required. `mvn spring-boot:run` picks up the latest migrations on next start, Flyway repair handles checksum drift, H2 file at `data/mateclaw.mv.db` is preserved.

---

## Known migration quirks

### 1. If you manually fiddled with `flyway_schema_history`

In 1.0.x some users hit Flyway version collisions (V8/V9 and V9/V10) which 1.1.0 fixes by renumbering. If you manually deleted rows from `flyway_schema_history` you may see `Validate failed` on 1.1.0 startup — run:

```sql
-- MySQL
DELETE FROM flyway_schema_history WHERE success = 0;
```

Then restart. `FlywayRepairConfig` will rebuild history from current schema state.

### 2. If your Ollama models are all in the no-tools family

After upgrade, agents that require tool calling will log a warning on first invocation:

```
Ollama: auto-activated default model '...' but its family does not support tool calling
```

Fix — pull a tool-capable model, or switch default in Settings → Models:

```bash
ollama pull qwen3
# or
ollama pull llama3.1:8b
# or
ollama pull mistral-nemo
```

### 3. If you had custom tools using `extract_document_text` / wiki tools

Wiki chunk schema changed (new `embedding` + `embedding_model` columns on `mate_wiki_chunk`). Your existing wiki pages work unchanged; only semantic search is new and requires an embedding model to be configured in Settings → Models (a default DashScope embedding is seeded).

---

## Rolling back to 1.0.x

Not recommended (some new tables / columns don't exist in 1.0.x), but possible if you backed up the DB before upgrade:

```bash
git checkout v1.0.418
# restore DB backup
docker compose up -d   # or mvn spring-boot:run
```

If you need to keep the new data but downgrade the app, you're in unsupported territory — open a Gitee issue.

---

## Getting help

- **Logs first**: `mateclaw-server/logs/mateclaw.log` + `mateclaw-error.log` have everything. Flyway decisions are at INFO level in main log.
- **Doctor tab**: in-app Settings → Doctor runs basic health checks
- **Gitee**: https://gitee.com/matevip_admin/mateclaw/issues — include your upgrade path (1.0.?? → 1.1.0), profile (H2 / MySQL), and the last 100 lines of startup log
