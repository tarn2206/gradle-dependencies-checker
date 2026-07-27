package com.github.tarn2206.tooling;

import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
public class Dependency {
    private final String group;
    private final String name;
    private final String version;
    private String latestVersion;
    private String status;
    private String error;
    private CatalogEntry catalogEntry;
    /** Build file of the module that declared this dependency. Used by ApplyUpdateAction. */
    private File moduleBuildFile;
    /** Known vulnerabilities. Null = not yet checked. Empty = clean. */
    private List<Vulnerability> vulnerabilities;

    public Dependency(String name) {
        this(null, name, null);
    }

    public Dependency(String group, String name, String version) {
        this.group = group;
        this.name = name;
        this.version = version != null && version.contains(" ") ? version.substring(0, version.indexOf(' ')) : version;
    }

    public boolean hasGroup() {
        return group != null && !group.equals("project ");
    }

    public boolean sameModule(Dependency o) {
        return o != null && Objects.equals(group, o.group) && Objects.equals(name, o.name);
    }

    /**
     * Returns true when an update is actually available — i.e. the latest known version differs from
     * the current one. Guards editor decorations and tree rendering against a stale-state edge case
     * where {@code latestVersion} equals {@code version} (which shouldn't happen but can if a check
     * ran against a since-updated file before the state was refreshed).
     */
    public boolean hasMeaningfulUpdate() {
        return latestVersion != null && !latestVersion.equals(version);
    }

    @Override
    public String toString() {
        var s = new StringBuilder();
        if (hasGroup()) {
            s.append(group).append(":");
        }
        s.append(name);
        if (version != null) {
            s.append(":").append(version);
        }
        return s.toString();
    }
}
