package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup security validator — warns about insecure default configurations.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(1)
public class SecurityStartupValidator implements ApplicationRunner {

    private static final String DEFAULT_JWT_SECRET = "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production";

    @Value("${mateclaw.jwt.secret}")
    private String jwtSecret;

    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Value("${mateclaw.cors.allowed-origins:*}")
    private String corsOrigins;

    @Override
    public void run(ApplicationArguments args) {
        boolean hasWarnings = false;

        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  SECURITY WARNING: Using default JWT secret!                ║");
            log.warn("║  Set JWT_SECRET environment variable for production.        ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
            hasWarnings = true;
        }

        if (h2ConsoleEnabled) {
            log.warn("[Security] H2 Console is enabled at /h2-console. Set H2_CONSOLE_ENABLED=false in production.");
            hasWarnings = true;
        }

        if ("*".equals(corsOrigins.trim())) {
            log.warn("[Security] CORS allows all origins. Set MATECLAW_CORS_ALLOWED_ORIGINS in production.");
            hasWarnings = true;
        }

        if (!hasWarnings) {
            log.info("[Security] Startup security check passed.");
        }
    }
}
