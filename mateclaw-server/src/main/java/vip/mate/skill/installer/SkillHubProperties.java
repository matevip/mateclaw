package vip.mate.skill.installer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ClawHub 市场连接配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.skill.hub")
public class SkillHubProperties {

    /** Hub base URL. */
    private String baseUrl = "https://clawhub.ai";

    /** Search API path. */
    private String searchPath = "/api/v1/search";

    /** Skill metadata API path prefix; full path is {@code <skillsPath>/<slug>}. */
    private String skillsPath = "/api/v1/skills";

    /** Bundle ZIP download API path; supports {@code ?slug=&version=}. */
    private String downloadPath = "/api/v1/download";

    /** HTTP request timeout (seconds). */
    private int httpTimeout = 15;

    /** HTTP retry count. */
    private int httpRetries = 3;
}
