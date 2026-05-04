package vip.mate.skill.runtime;

public enum SkillCatalogSort {
    RECOMMENDED,
    NAME,
    TYPE,
    STATUS,
    UPDATED;

    public static SkillCatalogSort parse(String value) {
        if (value == null || value.isBlank()) return RECOMMENDED;
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "name" -> NAME;
            case "type", "source" -> TYPE;
            case "status", "runtime" -> STATUS;
            case "updated", "update_time", "updateTime" -> UPDATED;
            default -> RECOMMENDED;
        };
    }
}
