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
            if (result.stream().noneMatch(existing -> existing.sameModule(dependency))) result.add(dependency);
        }
        return result;
    }

    record ModuleEntry(String path, String parentPath, String name, String directory,
                       String buildFile, List<DependencyEntry> dependencies) {
        ModuleEntry {
            dependencies = List.copyOf(dependencies);
        }
    }

    record DependencyEntry(String group, String name, String version, String error) {
    }
}
