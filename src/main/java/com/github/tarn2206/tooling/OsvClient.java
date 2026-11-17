package com.github.tarn2206.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Queries the OSV.dev API for known vulnerabilities affecting Maven coordinates.
 * <p>
 * Uses batch queries (up to 1000 coordinates per HTTP call) and caches results on disk
 * with a 6-hour TTL. Never throws — network failures are logged and treated as "no vulns known."
 */
@UtilityClass
public final class OsvClient {
    private static final Logger LOG = Logger.getInstance(OsvClient.class);

    private static final String BATCH_URL = "https://api.osv.dev/v1/querybatch";
    private static final String VULN_URL = "https://api.osv.dev/v1/vulns/";
    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(30);
    private static final long CACHE_TTL_MS = 6L * 60 * 60 * 1000; // 6 hours
    private static final int BATCH_SIZE = 500;
    private static final String CACHE_SUBDIR = "dependency-updates-plugin";
    private static final String CACHE_FILENAME = "osv-cache.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    /**
     * Bounded pool for parallel vulnerability-details fetches. 4 concurrent requests is well under any rate limits.
     */
    private static final ExecutorService DETAIL_POOL =
            AppExecutorUtil.createBoundedApplicationPoolExecutor("osv-detail", 4);

    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static volatile boolean cacheLoaded = false;

    /**
     * Populates {@link Dependency#getVulnerabilities()} for each dep in the list.
     * Uses cache where possible, otherwise queries OSV. Failures leave the list
     * as null (untouched) for that dep — the renderer will simply show nothing.
     */
    public static void enrichWithVulnerabilities(List<Dependency> deps) {
        loadCacheIfNeeded();

        var toQuery = new ArrayList<Dependency>();
        for (var dep : deps) {
            if (!isQueryable(dep)) continue;
            var key = coordKey(dep);
            var entry = cache.get(key);
            if (entry != null && !isExpired(entry)) {
                dep.setVulnerabilities(entry.vulnerabilities);
                continue;
            }
            toQuery.add(dep);
        }

        if (toQuery.isEmpty()) return;

        try {
            for (var i = 0; i < toQuery.size(); i += BATCH_SIZE) {
                var chunk = toQuery.subList(i, Math.min(i + BATCH_SIZE, toQuery.size()));
                processBatch(chunk);
            }
            saveCache();
        } catch (Exception e) {
            LOG.warn("OSV vulnerability check failed", e);
        }
    }

    private static boolean isQueryable(Dependency dep) {
        return dep.getGroup() != null && dep.getName() != null && dep.getVersion() != null && dep.hasGroup();
    }

    private static String coordKey(Dependency dep) {
        return dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion();
    }

    // ---------- Batch query + detail fetch ----------

    private static void processBatch(List<Dependency> chunk) throws IOException, InterruptedException {
        var results = queryBatch(chunk);
        if (results == null) return;

        var idsPerDep = mapDepsToVulnIds(chunk, results);
        var rawById = fetchAllVulnDetails(collectUniqueIds(idsPerDep));
        assembleAndCache(chunk, idsPerDep, rawById);
    }

    private static JsonObject buildBatchRequestBody(List<Dependency> chunk) {
        var queries = new JsonArray();
        for (var dep : chunk) {
            var pkg = new JsonObject();
            pkg.addProperty("ecosystem", "Maven");
            pkg.addProperty("name", dep.getGroup() + ":" + dep.getName());
            var q = new JsonObject();
            q.add("package", pkg);
            q.addProperty("version", dep.getVersion());
            queries.add(q);
        }
        var body = new JsonObject();
        body.add("queries", queries);
        return body;
    }

    private static @Nullable JsonArray queryBatch(List<Dependency> chunk) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(BATCH_URL))
                .header("Content-Type", "application/json")
                .timeout(REQ_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(buildBatchRequestBody(chunk))))
                .build();

        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warn("OSV batch query returned HTTP " + resp.statusCode());
            return null;
        }

        var respJson = JsonParser.parseString(resp.body()).getAsJsonObject();
        var results = respJson.getAsJsonArray("results");
        if (results == null || results.size() != chunk.size()) {
            LOG.warn("OSV batch response malformed (results size mismatch)");
            return null;
        }
        return results;
    }

    private static Map<Dependency, List<String>> mapDepsToVulnIds(List<Dependency> chunk, JsonArray results) {
        var idsPerDep = new HashMap<Dependency, List<String>>();
        for (var i = 0; i < results.size(); i++) {
            var dep = chunk.get(i);
            var ids = new ArrayList<String>();
            var result = results.get(i).getAsJsonObject();
            if (result.has("vulns")) {
                for (var vulnEl : result.getAsJsonArray("vulns")) {
                    ids.add(vulnEl.getAsJsonObject().get("id").getAsString());
                }
            }
            idsPerDep.put(dep, ids);
        }
        return idsPerDep;
    }

    private static Set<String> collectUniqueIds(Map<Dependency, List<String>> idsPerDep) {
        var uniqueIds = new HashSet<String>();
        for (var ids : idsPerDep.values()) {
            uniqueIds.addAll(ids);
        }
        return uniqueIds;
    }

    private static Map<String, JsonObject> fetchAllVulnDetails(Set<String> uniqueIds) {
        var rawById = new ConcurrentHashMap<String, JsonObject>();
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var id : uniqueIds) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    var raw = fetchRawVuln(id);
                    if (raw != null) rawById.put(id, raw);
                } catch (Exception e) {
                    LOG.warn("Failed to fetch OSV details for " + id + ": " + e.getMessage());
                }
            }, DETAIL_POOL));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            LOG.warn("OSV detail-fetch batch had failures", e);
        }
        return rawById;
    }

    private static void assembleAndCache(List<Dependency> chunk,
                                         Map<Dependency, List<String>> idsPerDep,
                                         Map<String, JsonObject> rawById) {
        for (var dep : chunk) {
            var vulnIds = idsPerDep.getOrDefault(dep, List.of());
            var vulns = new ArrayList<Vulnerability>();
            for (var id : vulnIds) {
                var raw = rawById.get(id);
                if (raw == null) continue;
                var v = buildVulnerability(id, raw, dep.getGroup(), dep.getName());
                if (v != null) vulns.add(v);
            }
            dep.setVulnerabilities(vulns);
            cache.put(coordKey(dep), new CacheEntry(vulns, System.currentTimeMillis()));
        }
    }

    private static @Nullable JsonObject fetchRawVuln(String id) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(VULN_URL + id))
                .header("Accept", "application/json")
                .timeout(REQ_TIMEOUT)
                .GET()
                .build();
        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warn("OSV vuln fetch returned HTTP " + resp.statusCode() + " for " + id);
            return null;
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static Vulnerability buildVulnerability(String id, JsonObject raw, String group, String name) {
        var severity = extractSeverity(raw);
        var summary = raw.has("summary") && !raw.get("summary").isJsonNull()
                ? raw.get("summary").getAsString()
                : "(no summary)";
        var fixedVersion = extractFixedVersion(raw, group, name);
        var url = "https://osv.dev/vulnerability/" + id;
        return new Vulnerability(id, severity, summary, fixedVersion, url);
    }

    private static Vulnerability.Severity extractSeverity(JsonObject vuln) {
        // Priority 1: database_specific.severity (GHSA uses this — explicit CRITICAL/HIGH/etc.)
        if (vuln.has("database_specific") && vuln.get("database_specific").isJsonObject()) {
            var db = vuln.getAsJsonObject("database_specific");
            if (db.has("severity") && !db.get("severity").isJsonNull()) {
                return parseSeverityLabel(db.get("severity").getAsString());
            }
        }
        // Priority 2: top-level severity array — try to find a plain score.
        if (vuln.has("severity") && vuln.get("severity").isJsonArray()) {
            for (var el : vuln.getAsJsonArray("severity")) {
                var obj = el.getAsJsonObject();
                if (!obj.has("score")) continue;
                var score = obj.get("score").getAsString();
                var s = bucketFromScore(score);
                if (s != Vulnerability.Severity.UNKNOWN) return s;
            }
        }
        return Vulnerability.Severity.UNKNOWN;
    }

    private static Vulnerability.Severity parseSeverityLabel(String s) {
        return switch (s.trim().toUpperCase()) {
            case "CRITICAL" -> Vulnerability.Severity.CRITICAL;
            case "HIGH" -> Vulnerability.Severity.HIGH;
            case "MODERATE", "MEDIUM" -> Vulnerability.Severity.MODERATE;
            case "LOW" -> Vulnerability.Severity.LOW;
            default -> Vulnerability.Severity.UNKNOWN;
        };
    }

    private static Vulnerability.Severity bucketFromScore(String score) {
        try {
            var n = Double.parseDouble(score);
            if (n >= 9.0) return Vulnerability.Severity.CRITICAL;
            if (n >= 7.0) return Vulnerability.Severity.HIGH;
            if (n >= 4.0) return Vulnerability.Severity.MODERATE;
            if (n > 0.0) return Vulnerability.Severity.LOW;
        } catch (NumberFormatException e) {
            // score is a CVSS vector string, not a number — no cheap way to bucket.
        }
        return Vulnerability.Severity.UNKNOWN;
    }

    private static @Nullable String extractFixedVersion(JsonObject vuln, String group, String name) {
        if (!vuln.has("affected") || !vuln.get("affected").isJsonArray()) return null;
        var target = (group + ":" + name).toLowerCase();
        for (var affEl : vuln.getAsJsonArray("affected")) {
            var aff = affEl.getAsJsonObject();
            var pkg = aff.has("package") ? aff.getAsJsonObject("package") : null;
            if (pkg == null || !pkg.has("name")) continue;
            if (!target.equalsIgnoreCase(pkg.get("name").getAsString())) continue;
            if (!aff.has("ranges")) continue;
            for (var rangeEl : aff.getAsJsonArray("ranges")) {
                var range = rangeEl.getAsJsonObject();
                if (!range.has("events")) continue;
                for (var evEl : range.getAsJsonArray("events")) {
                    var ev = evEl.getAsJsonObject();
                    if (ev.has("fixed")) return ev.get("fixed").getAsString();
                }
            }
        }
        return null;
    }

    // ---------- Cache persistence ----------

    private static synchronized void loadCacheIfNeeded() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        var path = cachePath();
        if (!Files.isRegularFile(path)) return;
        try {
            var json = Files.readString(path);
            Map<String, CacheEntry> loaded = GSON.fromJson(json, new TypeToken<Map<String, CacheEntry>>() {
            }.getType());
            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    if (v != null && !isExpired(v)) cache.put(k, v);
                });
            }
        } catch (Exception e) {
            LOG.warn("Failed to load OSV cache from " + path, e);
        }
    }

    private static synchronized void saveCache() {
        var path = cachePath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(cache));
        } catch (IOException e) {
            LOG.warn("Failed to save OSV cache to " + path, e);
        }
    }

    private static Path cachePath() {
        return Paths.get(PathManager.getSystemPath(), CACHE_SUBDIR, CACHE_FILENAME);
    }

    private static boolean isExpired(CacheEntry entry) {
        return System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS;
    }

    /**
     * Package-private for testing.
     */
    static void clearCache() {
        cache.clear();
        cacheLoaded = false;
    }

    // Gson requires a public no-arg constructor OR fields matching JSON — using fields directly.
    // Kept as a class (not record) so Gson deserializes reliably across versions.
    static final class CacheEntry {
        List<Vulnerability> vulnerabilities;
        long timestamp;

        CacheEntry() {
            this.vulnerabilities = Collections.emptyList();
        }

        CacheEntry(List<Vulnerability> vulnerabilities, long timestamp) {
            this.vulnerabilities = Objects.requireNonNullElse(vulnerabilities, Collections.emptyList());
            this.timestamp = timestamp;
        }
    }
}