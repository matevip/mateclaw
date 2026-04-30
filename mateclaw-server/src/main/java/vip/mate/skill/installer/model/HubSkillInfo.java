package vip.mate.skill.installer.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * ClawHub marketplace skill summary.
 * <p>
 * The hub API uses {@code displayName} / {@code summary}; older / alternative
 * deployments expose {@code name} / {@code description}. {@link JsonAlias}
 * keeps both wire shapes deserializing into the same fields so the UI never
 * shows blank rows when the upstream renames a key.
 *
 * @author MateClaw Team
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HubSkillInfo {

    @JsonAlias({"displayName"})
    private String name;

    private String slug;

    @JsonAlias({"summary"})
    private String description;

    private String author;
    private String version;
    private String icon;
    private List<String> tags;
    private Integer downloads;
    private String bundleUrl;
}
