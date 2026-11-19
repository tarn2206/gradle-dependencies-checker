package com.github.tarn2206.tooling;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Entry points for checking dependencies; coordinates repositories and applies the selected result.
 */
public final class MavenUtils {
    private static final Logger LOG = Logger.getInstance(MavenUtils.class);

    private MavenUtils() { }

    public static void checkForUpdate(Dependency dependency, AppSettings settings) {
        var unstablePatterns = settings.isIgnoreUnstable() ? settings.getUnstablePatterns() : null;
        for (var repository : settings.getRepos()) {
            if (repository.isActive()) {
                var candidate = findLatestVersion(repository.getUrl(), dependency, unstablePatterns);
                if (candidate != null) {
                    var current = dependency.getVersion();
                    if (StringUtils.isBlank(current) || compareVersions(candidate, current) > 0) {
                        dependency.setLatestVersion(candidate);
                    }
                    dependency.setError(null);
                    return;
                }
            }
        }
    }

    private static String findLatestVersion(String repository, Dependency dependency, String unstablePatterns) {
        try {
            for (var url : MavenRepositoryClient.metadataUrls(repository, dependency.getGroup(), dependency.getName())) {
                var result = MavenRepositoryClient.read(url);
                if (result.error() != null) dependency.setError(result.error());
                if (result.metadata() != null) {
                    var latest = selectVersion(result.metadata(), unstablePatterns);
                    if (latest != null) return latest;
                }
            }
        } catch (Exception e) {
            // Exception messages and causes can contain credentials or the original URL.
            LOG.warn("Cannot check repository " + MavenRepositoryClient.sanitizeUrl(repository) + " (" + e.getClass().getSimpleName() + ")");
        }
        return null;
    }

    static int compareVersions(String first, String second) {
        return new ComparableVersion(first).compareTo(new ComparableVersion(second));
    }

    static String selectVersion(MavenMetadata metadata, String unstablePatterns) {
        var highest = metadata.versions().stream()
                              .filter(version -> isAllowed(version, unstablePatterns))
                              .max(MavenUtils::compareVersions)
                              .orElse(null);
        if (highest != null) return highest;
        if (isAllowed(metadata.latest(), unstablePatterns)) return metadata.latest();
        return isAllowed(metadata.release(), unstablePatterns) ? metadata.release() : null;
    }

    private static boolean isAllowed(String version, String unstablePatterns) {
        if (StringUtils.isBlank(version)) return false;
        if (StringUtils.isBlank(unstablePatterns)) return true;
        for (var pattern : unstablePatterns.split(",")) {
            pattern = pattern.trim();
            if (!pattern.isEmpty() && StringUtils.containsIgnoreCase(version, pattern)) return false;
        }
        return true;
    }
}
