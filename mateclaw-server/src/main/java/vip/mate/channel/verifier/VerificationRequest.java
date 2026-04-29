package vip.mate.channel.verifier;

import java.util.Map;

/**
 * Draft channel config submitted to the wizard's Verify step. Carries only
 * what a verifier needs — no entity ID, no audit context — because preflight
 * runs before the row exists in {@code mate_channel}.
 *
 * @author MateClaw Team
 */
public record VerificationRequest(
        String channelType,
        Map<String, Object> config,
        Long workspaceId
) {
}
