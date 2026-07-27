package com.github.tarn2206.tooling;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-level source of truth for dependency state shared between the tool window
 * (which populates it) and editor components — inspections, line markers, quick-fixes —
 * that consume it.
 * <p>
 * Holds live {@link Dependency} references; the tool window keeps them alive, and
 * background mutations (update-check completion, OSV enrichment) become visible to
 * consumers without any additional bookkeeping. Editor re-analysis is triggered via
 * {@link DaemonCodeAnalyzer#restart()}, which IntelliJ internally coalesces so repeated
 * calls during a refresh burst are cheap.
 */
@Service(Service.Level.PROJECT)
public final class DependencyStateService {
    private final Project project;

    private final Map<String, Dependency> byCatalogKey = new ConcurrentHashMap<>();
    private final Map<String, Dependency> byPluginKey = new ConcurrentHashMap<>();
    private final Map<String, Dependency> byCoordinate = new ConcurrentHashMap<>();
    private volatile @Nullable VersionCatalog currentCatalog;

    public DependencyStateService(Project project) {
        this.project = project;
    }

    public @Nullable VersionCatalog getCatalog() {
        return currentCatalog;
    }

    public void setCatalog(@Nullable VersionCatalog catalog) {
        this.currentCatalog = catalog;
        notifyChange();
    }

    public void clear() {
        byCatalogKey.clear();
        byPluginKey.clear();
        byCoordinate.clear();
        notifyChange();
    }

    /**
     * Bulk index a module's deps. Called after {@link Dependency} objects are populated.
     */
    public void upsertAll(Iterable<Dependency> deps) {
        for (var dep : deps) {
            index(dep);
        }
        notifyChange();
    }

    /**
     * Called by the tool window whenever a background check mutates a dep's state.
     * <p>
     * We used to debounce this through an Alarm (300ms trailing). That backfired during large
     * refreshes: the alarm was reset by every check completion and never actually fired, so
     * inspections never re-ran against the populated state. Restart is cheap and safe from any
     * thread; IntelliJ coalesces to one re-analysis pass per idle window internally.
     */
    public void notifyChange() {
        if (!project.isDisposed()) {
            DaemonCodeAnalyzer.getInstance(project).restart();
        }
    }

    public @Nullable Dependency byCatalogKey(String key) {
        return key == null ? null : byCatalogKey.get(key);
    }

    public @Nullable Dependency byPluginKey(String key) {
        return key == null ? null : byPluginKey.get(key);
    }

    public @Nullable Dependency byCoordinate(String group, String name) {
        if (group == null || name == null) return null;
        return byCoordinate.get(group + ":" + name);
    }

    /**
     * Returns any dependency that shares this version key and has an available update.
     * Used by editor decorations on {@code [versions]} entries — one marker on the version key
     * itself, rather than N markers on every library using it.
     */
    public @Nullable Dependency anyUpdatableForVersionKey(String versionKey) {
        var cat = currentCatalog;
        if (cat == null || versionKey == null) return null;
        for (var libKey : cat.librariesUsingVersionKey(versionKey)) {
            var dep = byCatalogKey.get(libKey);
            if (dep != null && dep.hasMeaningfulUpdate()) return dep;
        }
        for (var pluginKey : cat.pluginsUsingVersionKey(versionKey)) {
            var dep = byPluginKey.get(pluginKey);
            if (dep != null && dep.hasMeaningfulUpdate()) return dep;
        }
        return null;
    }

    private void index(Dependency dep) {
        if (dep == null || dep.getGroup() == null || dep.getName() == null || !dep.hasGroup()) return;
        byCoordinate.put(dep.getGroup() + ":" + dep.getName(), dep);
        var entry = dep.getCatalogEntry();
        if (entry == null) return;
        switch (entry.kind()) {
            case LIBRARY -> byCatalogKey.put(entry.key(), dep);
            case PLUGIN -> byPluginKey.put(entry.key(), dep);
        }
    }
}
