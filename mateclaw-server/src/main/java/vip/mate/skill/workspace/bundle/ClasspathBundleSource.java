package vip.mate.skill.workspace.bundle;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link SkillBundleSource} backed by a directory under the classpath.
 *
 * <p>The classpath root is forward-slash separated, has no leading slash,
 * and points at the parent of {@code SKILL.md} — for example
 * {@code skills/pptx} (RFC-044 builtin) or
 * {@code skill-template-bundles/airtable} (RFC-091 wizard overlay).
 *
 * <p>Resolution uses {@link ResourcePatternResolver#CLASSPATH_ALL_URL_PREFIX}
 * so it works in both exploded-classpath dev runs and packaged JAR
 * deployments. Binary files (fonts, .so, .png) are streamed byte-for-byte;
 * no charset assumption is made.
 */
public final class ClasspathBundleSource implements SkillBundleSource {

    private final ResourcePatternResolver resolver;
    private final String classpathRoot;

    public ClasspathBundleSource(ResourcePatternResolver resolver, String classpathRoot) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.classpathRoot = normalize(classpathRoot);
    }

    @Override
    public String origin() {
        return "classpath:" + classpathRoot;
    }

    @Override
    public List<BundleAsset> assets() throws IOException {
        String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + classpathRoot + "/**";
        Resource[] resources = resolver.getResources(pattern);
        String prefix = classpathRoot + "/";
        List<BundleAsset> assets = new ArrayList<>(resources.length);
        for (Resource res : resources) {
            if (!res.isReadable()) continue;
            String relative = relativize(res, prefix);
            // Filter directory entries (their URI ends with "/") and the
            // root itself (whose URI doesn't contain prefix as a substring).
            if (relative == null || relative.isBlank() || relative.endsWith("/")) continue;
            assets.add(new BundleAsset(relative, res::getInputStream));
        }
        return assets;
    }

    private static String normalize(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("classpathRoot must not be blank");
        }
        String trimmed = root.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("classpathRoot must not resolve to empty");
        }
        return trimmed;
    }

    private static String relativize(Resource res, String prefix) {
        try {
            String uri = res.getURI().toString();
            int idx = uri.lastIndexOf(prefix);
            if (idx < 0) return null;
            return uri.substring(idx + prefix.length());
        } catch (IOException e) {
            return null;
        }
    }
}
