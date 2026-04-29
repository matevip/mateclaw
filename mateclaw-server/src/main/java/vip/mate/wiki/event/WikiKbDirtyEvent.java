package vip.mate.wiki.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired after a knowledge base's content meaningfully changes — i.e. raw
 * material ingest commits, or a non-trivial compile lands. Listeners that
 * want to refresh derived artefacts (overview narrative, embeddings drift
 * checks, search index) subscribe here.
 *
 * <p>Carries only {@code kbId}. Listeners debounce or batch on their own.
 * The intent is "this KB is dirty, rebuild downstream when convenient",
 * not "this exact raw was just processed" — the latter already has
 * {@link WikiProcessingEvent}.</p>
 */
@Getter
public class WikiKbDirtyEvent extends ApplicationEvent {

    private final Long kbId;

    public WikiKbDirtyEvent(Object source, Long kbId) {
        super(source);
        this.kbId = kbId;
    }
}
