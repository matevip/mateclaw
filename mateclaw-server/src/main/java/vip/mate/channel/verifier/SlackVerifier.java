package vip.mate.channel.verifier;

import com.slack.api.Slack;
import com.slack.api.methods.response.auth.AuthTestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates a Slack bot token via {@code auth.test}. The {@code app_token}
 * (used for Socket Mode) is intentionally not probed here because Slack
 * does not expose a no-side-effect endpoint for it — {@code apps.connections.open}
 * actually opens a WSS, which we do not want during a wizard step. We
 * surface a hint when {@code app_token} is missing so the user knows Socket
 * Mode won't work yet.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class SlackVerifier implements ChannelVerifier {

    @Override
    public String getChannelType() {
        return "slack";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String botToken = string(request.config(), "bot_token");
        String appToken = string(request.config(), "app_token");

        if (botToken == null || botToken.isBlank()) {
            return VerificationResult.failed(0, "Bot Token is required",
                    "bot_token", "Get one from your Slack App → OAuth & Permissions → Bot User OAuth Token (xoxb-…).");
        }

        try {
            AuthTestResponse resp = Slack.getInstance().methods(botToken).authTest(r -> r);
            long ms = System.currentTimeMillis() - t0;
            if (resp.isOk()) {
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", resp.getUserId());
                identity.put("accountName", resp.getUser());
                identity.put("team", resp.getTeam());
                identity.put("teamId", resp.getTeamId());
                identity.put("botId", resp.getBotId());
                String headline = "Connected to " + resp.getTeam() + " as " + resp.getUser();
                if (appToken == null || appToken.isBlank()) {
                    return VerificationResult.failedWithIdentity(ms,
                            headline + " — but App Token missing for Socket Mode",
                            identity, "app_token",
                            "Bot Token is valid. Add an App-Level Token (xapp-…) with connections:write to enable Socket Mode.");
                }
                if (!appToken.startsWith("xapp-")) {
                    return VerificationResult.failedWithIdentity(ms,
                            headline + " — but App Token has wrong prefix",
                            identity, "app_token",
                            "App Token must start with xapp- (App-Level Token), not xoxb- (Bot Token).");
                }
                return VerificationResult.ok(ms, headline, identity);
            }
            String error = resp.getError() != null ? resp.getError() : "auth_failed";
            return VerificationResult.failed(ms, "Slack says: " + error,
                    "bot_token", hintForSlackError(error));
        } catch (Exception e) {
            log.debug("[slack-verify] error: {}", e.getMessage());
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Could not reach Slack: " + e.getClass().getSimpleName(), null, e.getMessage());
        }
    }

    private static String hintForSlackError(String code) {
        return switch (code) {
            case "invalid_auth", "not_authed" ->
                    "Bot Token is invalid. Verify you copied the full xoxb- string from OAuth & Permissions.";
            case "account_inactive" -> "The Slack workspace or user is deactivated.";
            case "token_revoked" -> "Bot Token has been revoked. Reinstall the app to get a fresh token.";
            case "token_expired" -> "Bot Token has expired. Generate a new one in Slack App settings.";
            default -> "Slack rejected the request: " + code;
        };
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
