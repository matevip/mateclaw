package vip.mate.system.featureflag;

import lombok.Builder;
import lombok.Data;

/**
 * Evaluation context passed to {@link FeatureFlagService#isEnabled}.
 *
 * <p>Either or both of {@code kbId} and {@code userId} may be set. The
 * service uses them to match against per-flag whitelists; if both are null
 * the flag is evaluated against the percentage-rollout dial only.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class FlagContext {

    private Long kbId;
    private Long userId;
    private String role;

    public static FlagContext empty() {
        return FlagContext.builder().build();
    }

    public static FlagContext ofKb(Long kbId) {
        return FlagContext.builder().kbId(kbId).build();
    }

    public static FlagContext ofUser(Long userId) {
        return FlagContext.builder().userId(userId).build();
    }
}
