package vip.mate.llm.failover.probe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.llm.failover.ProbeResult;
import vip.mate.llm.failover.ProviderProbeStrategy;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.oauth.OpenAIOAuthService;

/**
 * Probes the ChatGPT Responses provider by validating its OAuth credentials.
 * No HTTP call: chatgpt.com/backend-api has no free liveness endpoint, and a
 * real chat completion costs tokens — so we ping nothing and instead verify
 * the OAuth state stored locally:
 * <ol>
 *   <li>access token present (and refreshable if near expiry, via
 *       {@link OpenAIOAuthService#ensureValidAccessToken()}),</li>
 *   <li>account id resolvable (header required by every Responses call).</li>
 * </ol>
 * If both succeed without throwing, the credentials are usable. A real auth
 * outage is then surfaced reactively on first chat (HARD-removed by
 * {@code NodeStreamingChatHelper}).
 */
@Slf4j
@Component
public class ChatGPTOAuthStatusProbe implements ProviderProbeStrategy {

    private final OpenAIOAuthService oauthService;

    public ChatGPTOAuthStatusProbe(OpenAIOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_CHATGPT;
    }

    @Override
    public ProbeResult probe(ModelProviderEntity provider) {
        long start = System.currentTimeMillis();
        try {
            String token = oauthService.ensureValidAccessToken();
            if (!StringUtils.hasText(token)) {
                return ProbeResult.fail(System.currentTimeMillis() - start, "OAuth access token missing");
            }
            String accountId = oauthService.getAccountId();
            if (!StringUtils.hasText(accountId)) {
                return ProbeResult.fail(System.currentTimeMillis() - start,
                        "OAuth account id missing — re-login required");
            }
            return ProbeResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.debug("[Probe] chatgpt OAuth status failed: {}", e.getMessage());
            return ProbeResult.fail(latency, shortMessage(e));
        }
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
