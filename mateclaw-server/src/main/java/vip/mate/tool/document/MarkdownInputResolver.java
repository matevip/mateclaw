package vip.mate.tool.document;

import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read one or more markdown files from the workspace, returning a single
 * resolved record that the document-render tools can hand straight to a
 * markdown-to-bytes renderer.
 *
 * <p>All path validation goes through {@link WorkspacePathGuard} so the LLM
 * cannot escape the workspace boundary by passing {@code ../}-prefixed paths.
 * Errors are signalled via {@link ResolveException} carrying a short message
 * the tool layer surfaces verbatim to the model.
 */
public final class MarkdownInputResolver {

    private MarkdownInputResolver() {}

    public record Resolved(String markdown, List<Path> sources, long totalBytes) {
        public int fileCount() {
            return sources.size();
        }
    }

    public static class ResolveException extends Exception {
        public ResolveException(String message) { super(message); }
    }

    /** Read a single markdown file. */
    public static Resolved readSingle(String filePath) throws ResolveException {
        if (filePath == null || filePath.isBlank()) {
            throw new ResolveException("filePath parameter is empty.");
        }
        Path resolved = validate(filePath, -1);
        long size;
        String content;
        try {
            size = Files.size(resolved);
            content = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ResolveException("failed to read markdown — " + e.getMessage());
        }
        if (content.isBlank()) {
            throw new ResolveException("markdown file is empty " + resolved);
        }
        return new Resolved(content, List.of(resolved), size);
    }

    /**
     * Read multiple markdown files in order and join them with one blank line
     * between each. Used by the multi-chapter docx renderer so a long report
     * can live in {@code cover.md} / {@code ch1.md} / {@code ch2.md} and still
     * compile to a single document.
     */
    public static Resolved readManyJoined(List<String> filePaths) throws ResolveException {
        if (filePaths == null || filePaths.isEmpty()) {
            throw new ResolveException("filePaths is empty.");
        }
        StringBuilder combined = new StringBuilder();
        long totalBytes = 0;
        List<Path> resolvedPaths = new ArrayList<>(filePaths.size());
        for (int idx = 0; idx < filePaths.size(); idx++) {
            String raw = filePaths.get(idx);
            if (raw == null || raw.isBlank()) {
                throw new ResolveException("filePaths[" + idx + "] is empty.");
            }
            Path resolved = validate(raw, idx);
            String content;
            try {
                totalBytes += Files.size(resolved);
                content = Files.readString(resolved, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new ResolveException(
                        "filePaths[" + idx + "] read failed — " + e.getMessage());
            }
            if (content.isBlank()) {
                throw new ResolveException("filePaths[" + idx + "] is blank " + resolved);
            }
            if (combined.length() > 0) combined.append("\n\n");
            combined.append(content);
            resolvedPaths.add(resolved);
        }
        return new Resolved(combined.toString(), List.copyOf(resolvedPaths), totalBytes);
    }

    /**
     * Resolve and validate a single path. {@code idx >= 0} formats errors as
     * {@code filePaths[idx]: ...} for the multi-file caller; {@code idx < 0}
     * uses the bare message form for the single-file caller.
     */
    private static Path validate(String raw, int idx) throws ResolveException {
        Path resolved;
        try {
            resolved = WorkspacePathGuard.validatePath(raw);
        } catch (Exception e) {
            throw new ResolveException(prefix(idx) + "path validation failed — " + e.getMessage());
        }
        if (!Files.exists(resolved)) {
            throw new ResolveException(prefix(idx) + "file not found at " + resolved);
        }
        if (!Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
            throw new ResolveException(prefix(idx) + "path is not a readable regular file " + resolved);
        }
        return resolved;
    }

    private static String prefix(int idx) {
        return idx < 0 ? "" : "filePaths[" + idx + "] ";
    }
}
