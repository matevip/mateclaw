package vip.mate.agent.graph.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks source references that were actually observed through tool results.
 */
public record SourceEvidenceLedger(
        Set<String> sourcePaths,
        Set<String> sourceSymbols,
        Set<String> failedPaths
) implements Serializable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JAVA_PATH = Pattern.compile(
            "(?:[A-Za-z]:)?[A-Za-z0-9_./\\\\-]+\\.java\\b");
    private static final Pattern JAVA_FILE_REF = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\.java\\b");
    private static final Pattern JAVA_SYMBOL_REF = Pattern.compile(
            "\\b[A-Z][A-Za-z0-9_]*(?:Controller|Service|ServiceImpl|Node|Tool|Parser|Resolver|Manager|Syncer|Mapper|Entity|Repository|Dispatcher|Executor|Accessor|Builder|Policy|Guard)\\b");
    private static final Pattern DECLARED_TYPE = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Z][A-Za-z0-9_]*)\\b");

    public SourceEvidenceLedger {
        sourcePaths = Set.copyOf(sourcePaths == null ? Set.of() : sourcePaths);
        sourceSymbols = Set.copyOf(sourceSymbols == null ? Set.of() : sourceSymbols);
        failedPaths = Set.copyOf(failedPaths == null ? Set.of() : failedPaths);
    }

    public static SourceEvidenceLedger empty() {
        return new SourceEvidenceLedger(Set.of(), Set.of(), Set.of());
    }

    public static SourceEvidenceLedger fromToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return empty();
        }
        Builder builder = new Builder();
        for (ToolResponseMessage.ToolResponse response : responses) {
            String data = response.responseData();
            if (data == null || data.isBlank()) {
                continue;
            }
            if (isReadFileTool(response.name())) {
                recordReadFile(data, builder);
            } else {
                recordPlainTextEvidence(data, builder);
            }
        }
        return builder.build();
    }

    public SourceEvidenceLedger merge(SourceEvidenceLedger other) {
        if (other == null || !other.hasEvidence()) {
            return this;
        }
        Builder builder = new Builder();
        sourcePaths.forEach(builder::sourcePath);
        sourceSymbols.forEach(builder::symbol);
        failedPaths.forEach(builder::failedPath);
        other.sourcePaths.forEach(builder::sourcePath);
        other.sourceSymbols.forEach(builder::symbol);
        other.failedPaths.forEach(builder::failedPath);
        return builder.build();
    }

    public SourceEvidenceLedger withSourcePath(String path) {
        Builder builder = new Builder();
        sourcePaths.forEach(builder::sourcePath);
        sourceSymbols.forEach(builder::symbol);
        failedPaths.forEach(builder::failedPath);
        builder.sourcePath(path);
        return builder.build();
    }

    public boolean hasEvidence() {
        return !sourcePaths.isEmpty() || !sourceSymbols.isEmpty() || !failedPaths.isEmpty();
    }

    public boolean hasPath(String path) {
        String normalized = normalizePath(path);
        return sourcePaths.contains(normalized) || sourcePaths.stream().anyMatch(p -> p.endsWith("/" + normalized));
    }

    public boolean hasSymbol(String symbol) {
        return sourceSymbols.contains(symbol);
    }

    public Validation validateAnswer(String answer) {
        if (answer == null || answer.isBlank() || !hasEvidence()) {
            return Validation.ok();
        }
        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        LinkedHashSet<String> unsupportedFileStems = new LinkedHashSet<>();
        Matcher fileMatcher = JAVA_FILE_REF.matcher(answer);
        while (fileMatcher.find()) {
            String ref = fileMatcher.group();
            if (!hasFileName(ref)) {
                unsupported.add(ref);
                unsupportedFileStems.add(ref.substring(0, ref.length() - ".java".length()));
            }
        }
        Matcher symbolMatcher = JAVA_SYMBOL_REF.matcher(answer);
        while (symbolMatcher.find()) {
            String ref = symbolMatcher.group();
            if (!unsupportedFileStems.contains(ref) && !sourceSymbols.contains(ref) && !hasFileName(ref + ".java")) {
                unsupported.add(ref);
            }
        }
        return unsupported.isEmpty() ? Validation.ok() : new Validation(false, List.copyOf(unsupported));
    }

    private boolean hasFileName(String fileName) {
        String normalized = normalizePath(fileName);
        return sourcePaths.stream().anyMatch(p -> p.equals(normalized) || p.endsWith("/" + normalized));
    }

    private static boolean isReadFileTool(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "_");
        return normalized.equals("read_file");
    }

    private static void recordReadFile(String data, Builder builder) {
        try {
            JsonNode root = MAPPER.readTree(data);
            String filePath = root.path("filePath").asText("");
            if (root.path("error").asBoolean(false)) {
                builder.failedPath(filePath);
                return;
            }
            builder.sourcePath(filePath);
            String content = root.path("content").asText("");
            recordSymbols(content, builder);
        } catch (Exception ignored) {
            recordPlainTextEvidence(data, builder);
        }
    }

    private static void recordPlainTextEvidence(String text, Builder builder) {
        Matcher matcher = JAVA_PATH.matcher(text);
        while (matcher.find()) {
            builder.sourcePath(matcher.group());
        }
        recordSymbols(text, builder);
    }

    private static void recordSymbols(String text, Builder builder) {
        Matcher matcher = DECLARED_TYPE.matcher(text);
        while (matcher.find()) {
            builder.symbol(matcher.group(1));
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private static final class Builder {
        private final LinkedHashSet<String> sourcePaths = new LinkedHashSet<>();
        private final LinkedHashSet<String> sourceSymbols = new LinkedHashSet<>();
        private final LinkedHashSet<String> failedPaths = new LinkedHashSet<>();

        void sourcePath(String path) {
            String normalized = normalizePath(path);
            if (normalized.isBlank()) {
                return;
            }
            sourcePaths.add(normalized);
            String fileName = Path.of(normalized).getFileName() != null
                    ? Path.of(normalized).getFileName().toString() : normalized;
            if (fileName.endsWith(".java")) {
                sourceSymbols.add(fileName.substring(0, fileName.length() - ".java".length()));
            }
        }

        void symbol(String symbol) {
            if (symbol != null && !symbol.isBlank()) {
                sourceSymbols.add(symbol.trim());
            }
        }

        void failedPath(String path) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) {
                failedPaths.add(normalized);
            }
        }

        SourceEvidenceLedger build() {
            return new SourceEvidenceLedger(sourcePaths, sourceSymbols, failedPaths);
        }
    }

    public record Validation(boolean valid, List<String> unsupportedReferences) {
        public static Validation ok() {
            return new Validation(true, List.of());
        }
    }
}
