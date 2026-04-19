package vip.mate.wiki;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import vip.mate.wiki.job.WikiProcessingJobService;

/**
 * Wiki module auto-configuration
 *
 * @author MateClaw Team
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WikiProperties.class)
@RequiredArgsConstructor
public class WikiAutoConfiguration {

    private final WikiProcessingJobService wikiProcessingJobService;

    /**
     * RFC-030: Recover stuck wiki processing jobs on startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverWikiJobs(ApplicationReadyEvent event) {
        wikiProcessingJobService.recoverOnStartup();
    }
}
