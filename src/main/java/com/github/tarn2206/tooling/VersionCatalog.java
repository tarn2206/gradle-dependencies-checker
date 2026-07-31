package com.github.tarn2206.tooling;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed representation of a Gradle version catalog (typically {@code gradle/libs.versions.toml}).
 * <p>
 * Provides a reverse lookup from {@code group:name} coordinates to the {@link CatalogEntry} that
 * produced them, a list of parsed plugin entries, and forward lookups from a version key to all
 * libraries/plugins sharing it.
 */
public final class VersionCatalog {
    private static final Logger LOG = Logger.getInstance(VersionCatalog.class);
    private static final String DEFAULT_CATALOG_PATH = "gradle/libs.versions.toml";

    private final File tomlFile;
    private final Map<String, String> versions;                          // version-key -> resolved version string
    private final Map<String, List<CatalogEntry>> byCoordinate;          // "group:name" -> library entries (aliases)
    private final Map<String, CatalogEntry> byKey;                       // catalog key -> entry (libs + plugins)
    private final List<CatalogEntry> plugins;                            // parsed [plugins] entries
    private final Map<String, List<String>> versionKeyToLibraries;       // version-key -> library keys
    private final Map<String, List<String>> versionKeyToPlugins;         // version-key -> plugin keys

    private VersionCatalog(File tomlFile,
                           Map<String, String> versions,
                           Map<String, List<CatalogEntry>> byCoordinate,
                           Map<String, CatalogEntry> byKey,
                           List<CatalogEntry> plugins,
                           Map<String, List<String>> versionKeyToLibraries,
                           Map<String, List<String>> versionKeyToPlugins) {
        this.tomlFile = tomlFile;
        this.versions = Collections.unmodifiableMap(versions);
        this.byCoordinate = Collections.unmodifiableMap(byCoordinate);
        this.byKey = Collections.unmodifiableMap(byKey);
        this.plugins = Collections.unmodifiableList(plugins);
        this.versionKeyToLibraries = Collections.unmodifiableMap(versionKeyToLibraries);
        this.versionKeyToPlugins = Collections.unmodifiableMap(versionKeyToPlugins);
    }

    public static @Nullable VersionCatalog loadForProject(Project project) {
        var basePath = project.getBasePath();
        if (basePath == null) return null;
        var file = new File(basePath, DEFAULT_CATALOG_PATH);
        if (!file.isFile()) return null;
        return parse(file);
    }

    public static @Nullable VersionCatalog parse(File tomlFile) {
        try {
            var result = Toml.parse(tomlFile.toPath());
            if (result.hasErrors()) {
                result.errors().forEach(err -> LOG.warn("TOML parse error in " + tomlFile + ": " + err));
            }
            return build(tomlFile, result);
        } catch (Exception e) {
            LOG.warn("Failed to read version catalog " + tomlFile, e);
            return null;
        }
    }

    private static VersionCatalog build(File tomlFile, TomlParseResult toml) {
        var versions = new HashMap<String, String>();
        var byCoord = new HashMap<String, List<CatalogEntry>>();
        var byKey = new HashMap<String, CatalogEntry>();
        var plugins = new ArrayList<CatalogEntry>();
        var versionKeyToLibs = new HashMap<String, List<String>>();
        var versionKeyToPlugs = new HashMap<String, List<String>>();

        var versionsTable = toml.getTable("versions");
        if (versionsTable != null) {
            for (var key : versionsTable.keySet()) {
                var v = extractVersionString(versionsTable.get(key));
                if (v != null) versions.put(key, v);
            }
        }

        var librariesTable = toml.getTable("libraries");
        if (librariesTable != null) {
            for (var libKey : librariesTable.keySet()) {
                var parsed = parseLibraryEntry(libKey, librariesTable, tomlFile);
                if (parsed == null) continue;

                var coord = parsed.group + ":" + parsed.name;
                var entry = new CatalogEntry(
                        CatalogEntry.Kind.LIBRARY, libKey, null,
                        parsed.versionRef, parsed.inlineVersion,
                        tomlFile, parsed.versionLine);

                // Same coord may legitimately appear under multiple aliases (typical setup:
                // one BOM-managed accessor + one version-carrying accessor for framework-agnostic
                // modules). Track all aliases; downstream consumers pick which one they need.
                byCoord.computeIfAbsent(coord, k -> new ArrayList<>()).add(entry);
                byKey.put(libKey, entry);

                if (parsed.versionRef != null) {
                    versionKeyToLibs.computeIfAbsent(parsed.versionRef, k -> new ArrayList<>()).add(libKey);
                }
            }
        }

        var pluginsTable = toml.getTable("plugins");
        if (pluginsTable != null) {
            for (var pluginKey : pluginsTable.keySet()) {
                var parsed = parsePluginEntry(pluginKey, pluginsTable, tomlFile);
                if (parsed == null) continue;

                var entry = new CatalogEntry(
                        CatalogEntry.Kind.PLUGIN, pluginKey, parsed.pluginId,
                        parsed.versionRef, parsed.inlineVersion,
                        tomlFile, parsed.versionLine);
                plugins.add(entry);
                byKey.put(pluginKey, entry);

                if (parsed.versionRef != null) {
                    versionKeyToPlugs.computeIfAbsent(parsed.versionRef, k -> new ArrayList<>()).add(pluginKey);
                }
            }
        }

        return new VersionCatalog(tomlFile, versions, byCoord, byKey, plugins,
                versionKeyToLibs, versionKeyToPlugs);
    }

    private static @Nullable ParsedLibrary parseLibraryEntry(String libKey, TomlTable libraries, File tomlFile) {
        var value = libraries.get(libKey);
        var libLine = lineOf(libraries, libKey);

        if (value instanceof String s) {
            var parts = s.split(":");
            if (parts.length >= 2) {
                var version = parts.length >= 3 ? parts[2] : null;
                return new ParsedLibrary(parts[0], parts[1], null, version, libLine);
            }
            LOG.warn("Version catalog: malformed shorthand for library '" + libKey + "' in " + tomlFile);
            return null;
        }

        if (!(value instanceof TomlTable lib)) return null;

        String group = null;
        String name = null;

        var module = lib.getString("module");
        if (module != null) {
            var parts = module.split(":");
            if (parts.length == 2) {
                group = parts[0];
                name = parts[1];
            }
        } else {
            group = lib.getString("group");
            name = lib.getString("name");
        }

        if (group == null || name == null) {
            LOG.warn("Version catalog: library '" + libKey + "' missing module/group/name");
            return null;
        }

        var versionInfo = extractVersionFields(lib, libLine);
        return new ParsedLibrary(group, name, versionInfo.versionRef, versionInfo.inlineVersion, versionInfo.line);
    }

    private static @Nullable ParsedPlugin parsePluginEntry(String pluginKey, TomlTable pluginsTable, File tomlFile) {
        var value = pluginsTable.get(pluginKey);
        var libLine = lineOf(pluginsTable, pluginKey);

        // Shorthand: springboot = "org.springframework.boot:4.1.0"
        if (value instanceof String s) {
            var idx = s.lastIndexOf(':');
            if (idx > 0) {
                return new ParsedPlugin(s.substring(0, idx), null, s.substring(idx + 1), libLine);
            }
            LOG.warn("Version catalog: malformed shorthand for plugin '" + pluginKey + "' in " + tomlFile);
            return null;
        }

        if (!(value instanceof TomlTable plugin)) return null;

        var id = plugin.getString("id");
        if (id == null || id.isBlank()) {
            LOG.warn("Version catalog: plugin '" + pluginKey + "' missing 'id'");
            return null;
        }

        var versionInfo = extractVersionFields(plugin, libLine);
        return new ParsedPlugin(id, versionInfo.versionRef, versionInfo.inlineVersion, versionInfo.line);
    }

    /**
     * Extracts version.ref / version fields from a library or plugin table. Shared between both kinds.
     */
    private static VersionInfo extractVersionFields(TomlTable table, int fallbackLine) {
        String versionRef = null;
        String inlineVersion = null;
        int versionLine = fallbackLine;

        var refDotted = table.getString("version.ref");
        if (refDotted != null) {
            versionRef = refDotted;
            var l = lineOf(table, "version.ref");
            if (l > 0) versionLine = l;
        } else {
            var versionObj = table.get("version");
            if (versionObj instanceof String vs) {
                inlineVersion = vs;
                var l = lineOf(table, "version");
                if (l > 0) versionLine = l;
            } else if (versionObj instanceof TomlTable vt) {
                var nestedRef = vt.getString("ref");
                if (nestedRef != null) {
                    versionRef = nestedRef;
                } else {
                    for (var richKey : List.of("strictly", "require", "prefer")) {
                        var rv = vt.getString(richKey);
                        if (rv != null) {
                            inlineVersion = rv;
                            break;
                        }
                    }
                }
                var l = lineOf(table, "version");
                if (l > 0) versionLine = l;
            }
        }
        return new VersionInfo(versionRef, inlineVersion, versionLine);
    }

    private static int lineOf(TomlTable table, String key) {
        try {
            var pos = table.inputPositionOf(key);
            return pos != null ? pos.line() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static @Nullable String extractVersionString(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof TomlTable t) {
            var ref = t.getString("ref");
            if (ref != null) return ref;
            for (var richKey : List.of("strictly", "require", "prefer")) {
                var v = t.getString(richKey);
                if (v != null) return v;
            }
        }
        return null;
    }

    /**
     * Returns the "primary" catalog entry for a coordinate. When multiple aliases exist for the
     * same coord (BOM-managed alias plus a version-carrying alias), prefer one that carries an
     * editable version — it's the actionable one for tool-window updates and jump-to-source.
     * Falls back to the first alias when none is editable (all-BOM-managed coord).
     */
    public @Nullable CatalogEntry findByCoordinate(String group, String name) {
        var all = findAllByCoordinate(group, name);
        if (all.isEmpty()) return null;
        for (var e : all) {
            if (e.hasEditableVersion()) return e;
        }
        return all.get(0);
    }

    /**
     * Returns every catalog entry (alias) that maps to this coordinate. Empty list when unknown.
     */
    public List<CatalogEntry> findAllByCoordinate(String group, String name) {
        if (group == null || name == null) return List.of();
        return byCoordinate.getOrDefault(group + ":" + name, List.of());
    }

    /**
     * O(1) lookup by catalog key. Covers both library and plugin entries.
     */
    public @Nullable CatalogEntry findEntryByKey(String key) {
        return key == null ? null : byKey.get(key);
    }

    /**
     * Best-effort discovery of the BOM that manages a given coordinate. Used to route "click to
     * update" from a BOM-managed entry (no version in the TOML) to the BOM entry that controls
     * the version. Heuristic-only — Gradle Tooling API would tell us authoritatively, but that's
     * not something we currently ask for.
     * <p>
     * BOM signal: the module coord OR the catalog alias key ends with {@code -bom} / {@code .bom}
     * (case-insensitive), and the entry carries an editable version. This is broader than checking
     * the coord alone because some teams name aliases {@code foo-bom} while the module coord is
     * just {@code foo-dependencies}.
     * <p>
     * Disambiguation across multiple BOM candidates:
     * <ol>
     *   <li>Aliases of the same BOM coord collapse into one candidate.</li>
     *   <li>Prefer a BOM whose group matches the target dep's group.</li>
     *   <li>Fall back to the first-seen candidate (alphabetical by catalog key). Better to guess
     *       than to hide the badge entirely — worst case, the click bumps the wrong BOM, which is
     *       trivially reversible.</li>
     * </ol>
     */
    public @Nullable CatalogEntry findManagingBom(String group, String name) {
        if (group == null || name == null) return null;
        var selfCoord = group + ":" + name;
        // Never claim a BOM manages itself.
        if (isBomCoord(selfCoord)) return null;

        // Collect distinct BOM coords with an editable representative entry. Aliases of the same
        // coord collapse (LinkedHashMap keeps deterministic order for the fallback).
        var candidates = new java.util.LinkedHashMap<String, CatalogEntry>();
        for (var e : byCoordinate.entrySet()) {
            var coord = e.getKey();
            for (var entry : e.getValue()) {
                if (!entry.hasEditableVersion()) continue;
                if (isBomCoord(coord) || isBomCoord(entry.key())) {
                    candidates.putIfAbsent(coord, entry);
                    break;
                }
            }
        }

        if (candidates.isEmpty()) {
            LOG.warn("findManagingBom(" + selfCoord + "): no BOM candidates in catalog. "
                    + "Any library entry with editable version whose module coord or alias key ends "
                    + "in '-bom' or '.bom' would count.");
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.values().iterator().next();
        }

        // Multiple BOMs — prefer one whose coord's group matches the target dep's group.
        for (var e : candidates.entrySet()) {
            var coord = e.getKey();
            var colon = coord.indexOf(':');
            if (colon > 0 && coord.substring(0, colon).equals(group)) return e.getValue();
        }

        // Nothing matched by group. Sort candidates by alias key for a stable pick, and take the
        // first — the alternative (returning null) would silently drop badges the user needs.
        var picked = candidates.values().stream()
                .min(java.util.Comparator.comparing(CatalogEntry::key))
                .orElse(null);
        LOG.warn("findManagingBom(" + selfCoord + "): multiple BOM candidates, none in group; "
                + "candidates=" + candidates.keySet() + ", picking '"
                + (picked == null ? "?" : picked.key()) + "'");
        return picked;
    }

    private static boolean isBomCoord(String s) {
        if (s == null) return false;
        var lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("-bom") || lower.endsWith(".bom");
    }

    /**
     * True if the given coord looks like a BOM (module coord ends in {@code -bom} or {@code .bom},
     * case-insensitive). Exposed so callers outside VersionCatalog can decide whether a bump
     * cascades through BOM-managed deps.
     */
    public static boolean looksLikeBomCoord(@Nullable String group, @Nullable String name) {
        if (group == null || name == null) return false;
        return isBomCoord(group + ":" + name);
    }

    /**
     * Returns every library entry that has no editable version (i.e., BOM-managed). Used after a
     * BOM update to invalidate cached {@code latestVersion} on deps whose actual version is about
     * to change transitively via the BOM bump — so their badges go quiet without waiting for the
     * full refresh.
     */
    public List<CatalogEntry> bomManagedLibraries() {
        var result = new ArrayList<CatalogEntry>();
        for (var entries : byCoordinate.values()) {
            for (var e : entries) {
                if (e.kind() == CatalogEntry.Kind.LIBRARY && !e.hasEditableVersion()) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    public List<CatalogEntry> getPlugins() {
        return plugins;
    }

    // --- internals ---

    public List<String> librariesUsingVersionKey(String versionKey) {
        return versionKeyToLibraries.getOrDefault(versionKey, List.of());
    }

    public List<String> pluginsUsingVersionKey(String versionKey) {
        return versionKeyToPlugins.getOrDefault(versionKey, List.of());
    }

    public File tomlFile() {
        return tomlFile;
    }

    public @Nullable String versionFor(String versionKey) {
        return versions.get(versionKey);
    }

    /**
     * Resolves an entry to a concrete version string, or null if the entry has no declared version.
     */
    public @Nullable String resolveVersion(CatalogEntry entry) {
        if (entry.inlineVersion() != null) return entry.inlineVersion();
        if (entry.versionRef() != null) return versionFor(entry.versionRef());
        return null;
    }

    private record ParsedLibrary(String group, String name, String versionRef, String inlineVersion, int versionLine) {
    }

    private record ParsedPlugin(String pluginId, String versionRef, String inlineVersion, int versionLine) {
    }

    private record VersionInfo(String versionRef, String inlineVersion, int line) {
    }
}