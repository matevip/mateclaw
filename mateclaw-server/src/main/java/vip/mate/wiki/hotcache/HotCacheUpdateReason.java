package vip.mate.wiki.hotcache;

/**
 * Why a hot cache row was last rebuilt. Stored as
 * {@link Enum#name()} on {@code mate_wiki_hot_cache.update_reason}.
 */
public enum HotCacheUpdateReason {

    /** A wiki compile job for some raw material in this KB just finished. */
    COMPILE_DONE,

    /** A page in this KB was edited directly (not via compile). */
    PAGE_UPDATED,

    /** A conversation that read pages from this KB ended (debounced). */
    CONVERSATION_END,

    /** Operator hit the "rebuild now" button. */
    MANUAL,

    /** Periodic refresh fired because no event had triggered a rebuild for a while. */
    STALE_CHECK,
}
