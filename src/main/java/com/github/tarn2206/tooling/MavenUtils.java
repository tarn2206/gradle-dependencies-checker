package com.github.tarn2206.tooling;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.diagnostic.Logger;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

@UtilityClass
public class MavenUtils {
    private static final Logger LOG = Logger.getInstance(MavenUtils.class);
    private static final Comparator<String> VERSION_COMPARATOR = new VersionComparator();
    private static final AppSettings.Repo GRADLE_PLUGIN_PORTAL =
            new AppSettings.Repo(true, "Gradle Plugin Portal", "https://plugins.gradle.org/m2");

    // ---------- Cache: raw latest-version-from-metadata per coord, 15-min TTL ----------
    // Prevents redundant HTTP when refreshes fire in bursts (auto-refresh, multi-module projects).
    private static final long CACHE_TTL_MS = 15L * 60 * 1000;
    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Called when user changes settings — repos or unstable-version rules could invalidate cached results.
     */
    public static void clearCache() {
        cache.clear();
    }

    public static void checkForUpdate(Dependency dependency, AppSettings settings) {
        var repos = settings.getRepos().stream().filter(AppSettings.Repo::isActive).toList();
        checkAgainst(dependency, settings, repos);
    }

    public static void checkForPluginUpdate(Dependency dependency, AppSettings settings) {
        var repos = new ArrayList<AppSettings.Repo>();
        repos.add(GRADLE_PLUGIN_PORTAL);
        settings.getRepos().stream().filter(AppSettings.Repo::isActive).forEach(repos::add);
        checkAgainst(dependency, settings, repos);
    }

    private static void checkAgainst(Dependency dependency, AppSettings settings, List<AppSettings.Repo> repos) {
        var cacheKey = dependency.getGroup() + ":" + dependency.getName();

        // Cache hit → apply raw latest and return without HTTP.
        var cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh()) {
            applyIfNewer(dependency, cached.rawLatestVersion);
            return;
        }

        // Cache miss → fetch from first repo that has metadata.
        for (var repo : repos) {
            var artifactPath = dependency.getGroup().replace('.', '/') + "/" + dependency.getName();
            for (var metadataFile : List.of("maven-metadata.xml", "maven-metadata-local.xml")) {
                var url = combine(repo.getUrl(), artifactPath + "/" + metadataFile);
                var raw = tryFetchRaw(dependency, settings, url);
                if (raw.isPresent()) {
                    cache.put(cacheKey, new CacheEntry(raw.get(), System.currentTimeMillis()));
                    applyIfNewer(dependency, raw.get());
                    dependency.setError(null);
                    return;
                }
            }
        }
    }

    /**
     * Returns raw latest version from metadata at this URL, or empty on 404 / not found.
     */
    private static Optional<String> tryFetchRaw(Dependency dependency, AppSettings settings, String url) {
        try {
            var connection = openConnection(url);
            if (connection instanceof HttpURLConnection http) {
                var code = http.getResponseCode();
                if (code != 200) {
                    if (code != 404) {
                        dependency.setError(code + " " + http.getResponseMessage());
                        LOG.warn(code + " " + sanitize(url));
                    }
                    return Optional.empty();
                }
            }
            try (var in = connection.getInputStream()) {
                var latestVersion = getLatestVersion(in, settings);
                return Optional.ofNullable(latestVersion);
            } catch (FileNotFoundException e) {
                return Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("Failed to check " + sanitize(url) + ": " + e.getMessage());
            dependency.setError(e.getMessage());
            return Optional.empty();
        }
    }

    private static String combine(String a, String b) {
        return a.endsWith("/") ? a + b : a + "/" + b;
    }

    private static String sanitize(String url) {
        try {
            var u = new URI(url);
            if (u.getUserInfo() == null) return url;
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), u.getQuery(), u.getFragment())
                    .toString();
        } catch (URISyntaxException e) {
            return url.replaceAll("://[^/@]+@", "://***@");
        }
    }

    private static URLConnection openConnection(String url) throws GeneralSecurityException, IOException {
        var uri = URI.create(url);
        var userInfo = uri.getUserInfo();

        // Rebuild the URL without embedded credentials before handing it to the JDK. Newer JDKs
        // may NPE or reject URL-embedded userInfo; we always send credentials as a Basic auth
        // header below, so the URL itself never needs to carry them.
        URL targetUrl;
        if (userInfo != null) {
            try {
                targetUrl = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), uri.getQuery(), uri.getFragment()).toURL();
            } catch (URISyntaxException e) {
                throw new IOException("Malformed URL", e);
            }
        } else {
            targetUrl = uri.toURL();
        }

        var connection = targetUrl.openConnection();
        if (connection instanceof HttpsURLConnection httpsConnection) {
            trustAll(httpsConnection);
        }
        if (StringUtils.isNotBlank(userInfo) && connection instanceof HttpURLConnection) {
            var authorization = "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(UTF_8));
            connection.setRequestProperty("Authorization", authorization);
        }
        return connection;
    }

    private static void trustAll(HttpsURLConnection connection) throws GeneralSecurityException {
        var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, new X509TrustManager[]{trustAll}, new SecureRandom());
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
    }

    public static String getLatestVersion(InputStream in, AppSettings settings) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            var doc = factory.newDocumentBuilder().parse(new InputSource(in));
            var root = doc.getDocumentElement();

            var versioning = firstChild(root, "versioning");
            if (versioning == null) return null;

            var allVersions = new ArrayList<String>();
            var versionsNode = firstChild(versioning, "versions");
            if (versionsNode != null) {
                var vList = versionsNode.getElementsByTagName("version");
                for (var i = 0; i < vList.getLength(); i++) {
                    allVersions.add(vList.item(i).getTextContent().trim());
                }
            }

            var latest = textOf(firstChild(versioning, "latest"));
            if (StringUtils.isBlank(latest)) {
                latest = textOf(firstChild(versioning, "release"));
            }
            if (StringUtils.isNotBlank(latest)
                    && (!settings.isIgnoreUnstable() || isStable(latest, settings.getUnstablePatterns()))) {
                return latest;
            }

            return allVersions.stream()
                    .filter(v -> !settings.isIgnoreUnstable() || isStable(v, settings.getUnstablePatterns()))
                    .max(VERSION_COMPARATOR)
                    .orElse(null);
        } catch (Exception e) {
            throw new IOException("Failed to parse maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    private static Element firstChild(Element parent, String tag) {
        var list = parent.getElementsByTagName(tag);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static String textOf(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }

    private static boolean isStable(String version, String patterns) {
        if (StringUtils.isBlank(version)) return false;
        if (StringUtils.isBlank(patterns)) return true;

        var array = patterns.split(",");
        for (var pattern : array) {
            pattern = StringUtils.trimToEmpty(pattern);
            if (!pattern.isEmpty() && StringUtils.containsIgnoreCase(version, pattern)) {
                return false;
            }
        }
        return true;
    }

    private static void applyIfNewer(Dependency dependency, @Nullable String rawLatestVersion) {
        if (rawLatestVersion == null) return;
        if (VERSION_COMPARATOR.compare(rawLatestVersion, dependency.getVersion()) > 0) {
            dependency.setLatestVersion(rawLatestVersion);
        }
    }

    private record CacheEntry(String rawLatestVersion, long timestamp) {
        boolean isFresh() {
            return System.currentTimeMillis() - timestamp < CACHE_TTL_MS;
        }
    }

    static class VersionComparator implements Comparator<String> {
        private static Long tryParse(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public int compare(String a, String b) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;

            var aParts = a.split("[.\\-_]");
            var bParts = b.split("[.\\-_]");
            var len = Math.max(aParts.length, bParts.length);

            for (var i = 0; i < len; i++) {
                var ap = i < aParts.length ? aParts[i] : "0";
                var bp = i < bParts.length ? bParts[i] : "0";

                var aNum = tryParse(ap);
                var bNum = tryParse(bp);

                int cmp;
                if (aNum != null && bNum != null) {
                    cmp = Long.compare(aNum, bNum);
                } else if (aNum != null) {
                    cmp = 1;
                } else if (bNum != null) {
                    cmp = -1;
                } else {
                    cmp = ap.compareToIgnoreCase(bp);
                }
                if (cmp != 0) return cmp;
            }
            return 0;
        }
    }
}