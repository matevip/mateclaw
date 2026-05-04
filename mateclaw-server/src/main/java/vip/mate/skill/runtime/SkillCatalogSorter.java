package vip.mate.skill.runtime;

import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SkillCatalogSorter {

    private SkillCatalogSorter() {
    }

    public static List<SkillEntity> sortEntities(List<SkillEntity> skills, SkillCatalogSort sort) {
        return sortEntities(skills, sort, Set.of());
    }

    public static List<SkillEntity> sortEntities(List<SkillEntity> skills, SkillCatalogSort sort,
                                                 Set<Long> pinnedSkillIds) {
        if (skills == null || skills.isEmpty()) return List.of();
        return skills.stream()
                .sorted(entityComparator(sort, pinnedSkillIds))
                .toList();
    }

    public static List<ResolvedSkill> sortResolved(List<ResolvedSkill> skills, SkillCatalogSort sort) {
        if (skills == null || skills.isEmpty()) return List.of();
        return skills.stream()
                .sorted(resolvedComparator(sort))
                .toList();
    }

    public static boolean sourceMatches(String actualType, String requestedSource) {
        if (requestedSource == null || requestedSource.isBlank()
                || "all".equalsIgnoreCase(requestedSource)) {
            return true;
        }
        return normalizeType(actualType).equals(normalizeType(requestedSource));
    }

    public static boolean sourceMatches(ResolvedSkill skill, String requestedSource) {
        if (requestedSource == null || requestedSource.isBlank()
                || "all".equalsIgnoreCase(requestedSource)) {
            return true;
        }
        String requested = normalizeType(requestedSource);
        if ("builtin".equals(requested)) return skill.isBuiltin();
        if ("dynamic".equals(requested) && !skill.isBuiltin()) {
            String source = normalizeType(skill.getSource());
            return "database".equals(source) || "directory".equals(source);
        }
        return normalizeType(skill.getSource()).equals(requested);
    }

    public static boolean runtimeMatches(SkillEntity skill, String requestedRuntime) {
        if (requestedRuntime == null || requestedRuntime.isBlank()
                || "all".equalsIgnoreCase(requestedRuntime)) {
            return true;
        }
        String requested = requestedRuntime.trim().toLowerCase(Locale.ROOT);
        if ("disabled".equals(requested)) {
            return !Boolean.TRUE.equals(skill.getEnabled());
        }
        if ("blocked".equals(requested) || "security_blocked".equals(requested)) {
            return "FAILED".equalsIgnoreCase(skill.getSecurityScanStatus());
        }
        if ("ready".equals(requested)) {
            return Boolean.TRUE.equals(skill.getEnabled())
                    && !"FAILED".equalsIgnoreCase(skill.getSecurityScanStatus());
        }
        if ("setup_needed".equals(requested) || "setup-needed".equals(requested)) {
            String tags = skill.getTags();
            return tags != null && tags.toLowerCase(Locale.ROOT).contains("setup");
        }
        return true;
    }

    public static boolean runtimeMatches(ResolvedSkill skill, String requestedRuntime) {
        if (requestedRuntime == null || requestedRuntime.isBlank()
                || "all".equalsIgnoreCase(requestedRuntime)) {
            return true;
        }
        String requested = requestedRuntime.trim().toLowerCase(Locale.ROOT);
        if ("disabled".equals(requested)) return !skill.isEnabled();
        if ("blocked".equals(requested) || "security_blocked".equals(requested)) return skill.isSecurityBlocked();
        if ("ready".equals(requested)) return SkillRuntimeService.passesActiveGate(skill);
        if ("setup_needed".equals(requested) || "setup-needed".equals(requested)) {
            return !skill.isDependencyReady() || "Dependencies Missing".equals(skill.getRuntimeStatusLabel());
        }
        return true;
    }

    public static int sourceRank(String type) {
        return switch (normalizeType(type)) {
            case "builtin" -> 0;
            case "dynamic", "custom" -> 1;
            case "mcp" -> 2;
            case "acp" -> 3;
            default -> 4;
        };
    }

    static Comparator<SkillEntity> entityComparator(SkillCatalogSort sort) {
        return entityComparator(sort, Set.of());
    }

    static Comparator<SkillEntity> entityComparator(SkillCatalogSort sort, Set<Long> pinnedSkillIds) {
        SkillCatalogSort normalized = sort == null ? SkillCatalogSort.RECOMMENDED : sort;
        Set<Long> pinned = pinnedSkillIds == null ? Set.of() : pinnedSkillIds;
        return switch (normalized) {
            case NAME -> Comparator
                    .comparing(SkillCatalogSorter::entityDisplayName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(s -> nullSafe(s.getName()), String.CASE_INSENSITIVE_ORDER);
            case TYPE -> Comparator
                    .comparingInt((SkillEntity s) -> sourceRank(s.getSkillType()))
                    .thenComparing(SkillCatalogSorter::entityDisplayName, String.CASE_INSENSITIVE_ORDER);
            case STATUS -> Comparator
                    .comparingInt(SkillCatalogSorter::entityStatusRank)
                    .thenComparingInt(s -> sourceRank(s.getSkillType()))
                    .thenComparing(SkillCatalogSorter::entityDisplayName, String.CASE_INSENSITIVE_ORDER);
            case UPDATED -> Comparator
                    .comparing((SkillEntity s) -> s.getUpdateTime(), Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(SkillCatalogSorter::entityDisplayName, String.CASE_INSENSITIVE_ORDER);
            case RECOMMENDED -> Comparator
                    .comparingInt((SkillEntity s) -> s.getId() != null && pinned.contains(s.getId()) ? 0 : 1)
                    .thenComparingInt(SkillCatalogSorter::entityStatusRank)
                    .thenComparingInt(s -> sourceRank(s.getSkillType()))
                    .thenComparing(SkillCatalogSorter::entityDisplayName, String.CASE_INSENSITIVE_ORDER);
        };
    }

    static Comparator<ResolvedSkill> resolvedComparator(SkillCatalogSort sort) {
        SkillCatalogSort normalized = sort == null ? SkillCatalogSort.RECOMMENDED : sort;
        return switch (normalized) {
            case NAME -> Comparator.comparing(s -> nullSafe(s.getName()), String.CASE_INSENSITIVE_ORDER);
            case TYPE -> Comparator
                    .comparingInt((ResolvedSkill s) -> resolvedSourceRank(s))
                    .thenComparing(s -> nullSafe(s.getName()), String.CASE_INSENSITIVE_ORDER);
            case STATUS -> Comparator
                    .comparingInt(SkillCatalogSorter::resolvedStatusRank)
                    .thenComparingInt(SkillCatalogSorter::resolvedSourceRank)
                    .thenComparing(s -> nullSafe(s.getName()), String.CASE_INSENSITIVE_ORDER);
            case UPDATED, RECOMMENDED -> Comparator
                    .comparingInt(SkillCatalogSorter::resolvedStatusRank)
                    .thenComparingInt(SkillCatalogSorter::resolvedSourceRank)
                    .thenComparing(s -> nullSafe(s.getName()), String.CASE_INSENSITIVE_ORDER);
        };
    }

    static int entityStatusRank(SkillEntity skill) {
        if ("FAILED".equalsIgnoreCase(skill.getSecurityScanStatus())) return 5;
        if (!Boolean.TRUE.equals(skill.getEnabled())) return 4;
        return 1;
    }

    static int resolvedStatusRank(ResolvedSkill skill) {
        if (skill.isSecurityBlocked()) return 5;
        if (!skill.isEnabled()) return 4;
        if (!SkillRuntimeService.passesActiveGate(skill)) return 2;
        return 1;
    }

    private static int resolvedSourceRank(ResolvedSkill skill) {
        if (skill.isBuiltin()) return 0;
        String source = normalizeType(skill.getSource());
        if ("database".equals(source) || "directory".equals(source)) return 1;
        return sourceRank(source);
    }

    private static String entityDisplayName(SkillEntity skill) {
        if (skill.getNameZh() != null && !skill.getNameZh().isBlank()) return skill.getNameZh();
        if (skill.getNameEn() != null && !skill.getNameEn().isBlank()) return skill.getNameEn();
        return nullSafe(skill.getName());
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) return "";
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if ("custom".equals(normalized)) return "dynamic";
        return normalized;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
