package vip.mate.channel.verifier;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexes all {@link ChannelVerifier} beans by channel type. Spring injects
 * the full list at construction time; one verifier per channel type is the
 * contract — duplicates log a warning and the last one wins (keeps test
 * doubles overridable without crashing the context).
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelVerifierRegistry {

    private final List<ChannelVerifier> verifiers;
    private Map<String, ChannelVerifier> byType;

    @PostConstruct
    void index() {
        Map<String, ChannelVerifier> map = new HashMap<>();
        for (ChannelVerifier v : verifiers) {
            ChannelVerifier prev = map.put(v.getChannelType(), v);
            if (prev != null) {
                log.warn("Duplicate ChannelVerifier for type '{}' — {} replaces {}",
                        v.getChannelType(), v.getClass().getSimpleName(), prev.getClass().getSimpleName());
            }
        }
        this.byType = Map.copyOf(map);
        log.info("ChannelVerifierRegistry indexed {} verifier(s): {}", byType.size(), byType.keySet());
    }

    public Optional<ChannelVerifier> find(String channelType) {
        return Optional.ofNullable(byType.get(channelType));
    }
}
