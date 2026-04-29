package vip.mate.channel.verifier;

/**
 * Pre-flight credential verifier for a single channel type.
 * <p>
 * Implementations validate a draft channel configuration by performing the
 * cheapest possible auth probe against the upstream service (e.g. Telegram
 * {@code getMe}, Slack {@code auth.test}, Discord {@code users/@me}). They
 * MUST be side-effect free: no persistence, no shared connection state, no
 * impact on running adapters. Network errors are reported via
 * {@link VerificationResult#failed} rather than thrown.
 * <p>
 * Verifiers are auto-discovered as Spring beans and indexed by
 * {@link #getChannelType()} in {@link ChannelVerifierRegistry}. A channel
 * type without a verifier degrades to a "skipped" verify step in the
 * onboarding wizard — the user can still save and start the channel, but
 * loses the live connection check.
 *
 * @author MateClaw Team
 * @see ChannelVerifierRegistry
 * @see VerificationResult
 */
public interface ChannelVerifier {

    /**
     * Channel type discriminator, e.g. {@code "telegram"}, {@code "slack"}.
     * Must match the {@code channelType} column in {@code mate_channel}.
     */
    String getChannelType();

    /**
     * Validate the draft config. Implementations MUST bound every network
     * call by a 5-second timeout and never throw — wrap upstream errors in
     * {@link VerificationResult#failed}.
     */
    VerificationResult verify(VerificationRequest request);
}
