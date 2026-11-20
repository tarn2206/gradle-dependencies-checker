package com.github.tarn2206.tooling;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Dependency {
    private final String group;
    private final String name;
    private final String version;
    private String pluginId;
    private boolean buildscriptPlugin;
    private String latestVersion;
    private List<ResolvedArtifact> resolvedArtifacts = List.of();
    private List<AvailableUpdate> availableUpdates = List.of();
    private String status;
    private String error;

    public Dependency(String name) {
        this(null, name, null);
    }

    public Dependency(String group, String name, String version) {
        this.group = group;
        this.name = name;
        this.version = version != null && version.contains(" ") ? version.substring(0, version.indexOf(' ')) : version;
    }

    public static Dependency plugin(String id, String version) {
        var dependency = new Dependency(id, id + ".gradle.plugin", version);
        dependency.pluginId = id;
        return dependency;
    }

    public static Dependency classpathPlugin(String group, String name, String version) {
        var dependency = new Dependency(group, name, version);
        dependency.buildscriptPlugin = true;
        return dependency;
    }

    public boolean hasGroup() {
        return group != null && !group.equals("project ");
    }

    public boolean sameModule(Dependency o) {
        return o != null && Objects.equals(group, o.group) && Objects.equals(name, o.name);
    }

    public List<ResolvedArtifact> updateTargets() {
        return resolvedArtifacts.isEmpty() ? List.of(new ResolvedArtifact(group, name, version)) : resolvedArtifacts;
    }

    public boolean sameUpdateCheck(Dependency other) {
        return sameModule(other) && Objects.equals(version, other.version)
               && buildscriptPlugin == other.buildscriptPlugin
               && Objects.equals(pluginId, other.pluginId)
               && new HashSet<>(updateTargets()).equals(new HashSet<>(other.updateTargets()));
    }

    public record AvailableUpdate(ResolvedArtifact current, String latest) { }

    public String availableUpdatesText() {
        return availableUpdates.stream().map(update -> {
            var target = update.current();
            var sameCoordinate = Objects.equals(group, target.group()) && Objects.equals(name, target.name());
            return (sameCoordinate ? "" : target.group() + ":" + target.name() + ":") + update.latest();
        }).distinct().collect(Collectors.joining(", "));
    }

    public String coordinateText() {
        var sb = new StringBuilder();
        if (hasGroup() && pluginId == null) {
            sb.append(group).append(":");
        }
        sb.append(pluginId != null ? pluginId : name);
        return sb.toString();
    }

    public List<String> resolvedVersion() {
        var selected = resolvedArtifacts.stream().map(artifact ->
                                                              Objects.equals(group, artifact.group()) && Objects.equals(name, artifact.name())
                                                              ? artifact.version() : artifact.toString()).distinct().toList();
        if (!selected.isEmpty() && !(selected.size() == 1 && Objects.equals(version, selected.getFirst()))) {
            return selected;
        }
        return List.of();
    }

    @Override
    public String toString() {
        var resolvedVersion = resolvedVersion();
        return coordinateText() + (version == null ? "" : ":" + version)
               + (resolvedVersion.isEmpty() ? "" : " [" + String.join(", ", resolvedVersion) + "]");
    }
}
