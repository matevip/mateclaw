package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of bytes produced by tools (e.g. {@code DocxRenderTool}) and
 * served by {@link GeneratedFileController}. Entries expire after {@link #TTL}
 * and are evicted lazily on every {@link #put} call.
 *
 * <p>The cache is process-local and intentionally not persisted: a JVM restart
 * invalidates all outstanding download links. The download URL embeds a random
 * {@link UUID}, which acts as the only access credential.
 */
@Slf4j
@Component
public class GeneratedFileCache {

    public static final Duration TTL = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public record Entry(byte[] bytes, String filename, String mimeType, long expireAt) {

        public boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /**
     * Store the given bytes and return a fresh, unguessable identifier.
     * Callers should embed the id in a URL of the form
     * {@code /api/v1/files/generated/{id}}.
     */
    public String put(byte[] bytes, String filename, String mimeType) {
        evictExpired();
        String id = UUID.randomUUID().toString();
        long expireAt = System.currentTimeMillis() + TTL.toMillis();
        entries.put(id, new Entry(bytes, filename, mimeType, expireAt));
        log.debug("Cached generated file id={} filename={} bytes={}", id, filename, bytes.length);
        return id;
    }

    /**
     * Look up an entry. Returns {@link Optional#empty()} if missing or expired
     * (expired entries are removed as a side-effect).
     */
    public Optional<Entry> get(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired()) {
            entries.remove(id, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
    }
}
