package com.github.tarn2206.tooling;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.util.io.FileUtil;

/**
 * Immutable Gradle data. Mutable UI dependencies are created afresh for each rendering.
 */
public final class BuildSnapshot {
    private final List<ModuleEntry> modules;

    BuildSnapshot(ModuleEntry[] modules) {
        this.modules = List.of(modules);
    }

    public ProjectInfo projectInfo() {
        return projectInfo(modules.stream().filter(m -> m.parentPath() == null).findFirst().orElseThrow());
    }

    private ProjectInfo projectInfo(ModuleEntry module) {
        var children = modules.stream()
                              .filter(m -> module.path().equals(m.parentPath()))
                              .map(this::projectInfo).toList();
        return new ProjectInfo(module.name(), new File(module.buildFile()), children);
    }

    public List<Dependency> dependencies(ProjectInfo info) {
        var module = modules.stream()
                            .filter(m -> FileUtil.filesEqual(new File(m.buildFile()), info.buildFile()))
                            .findFirst().orElseThrow();
        var result = new ArrayList<Dependency>();
        for (var entry : module.dependencies()) {
            var dependency = new Dependency(entry.group, entry.name, entry.version);
            dependency.setError(entry.error);
            var existing = result.stream().filter(d -> d.sameModule(dependency)).findFirst().orElse(null);
            if (existing == null) {
                existing = dependency;
                result.add(existing);
            }
            if (entry.resolved != null && entry.resolved.isValid()) {
                var resolved = new ArrayList<>(existing.getResolvedArtifacts());
                if (!resolved.contains(entry.resolved)) resolved.add(entry.resolved);
                existing.setResolvedArtifacts(List.copyOf(resolved));
            }
        }
        return result;
    }

    public List<Dependency> plugins() {
        return modules.stream().flatMap(module -> module.plugins().stream()).distinct()
                      .map(entry -> {
                          var dependency = entry.id() != null ? Dependency.plugin(entry.id(), entry.version())
                                                              : Dependency.classpathPlugin(entry.group(), entry.name(), entry.version());
                          if (entry.resolved() != null && entry.resolved().isValid())
                              dependency.setResolvedArtifacts(List.of(entry.resolved()));
                          return dependency;
                      }).toList();
    }

    record ModuleEntry(String path, String parentPath, String name, String directory,
                       String buildFile, List<DependencyEntry> dependencies, List<PluginEntry> plugins) {
        ModuleEntry(String path, String parentPath, String name, String directory,
                    String buildFile, List<DependencyEntry> dependencies) {
            this(path, parentPath, name, directory, buildFile, dependencies, List.of());
        }

        ModuleEntry {
            dependencies = List.copyOf(dependencies);
            plugins = plugins == null ? List.of() : List.copyOf(plugins);
        }
    }

    record PluginEntry(String id, String version, String group, String name, ResolvedArtifact resolved) { }

    record DependencyEntry(String group, String name, String version, String error, ResolvedArtifact resolved) {
        DependencyEntry(String group, String name, String version, String error) {
            this(group, name, version, error, null);
        }
    }
}
