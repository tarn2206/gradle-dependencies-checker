package com.github.tarn2206.tooling;

import org.jspecify.annotations.NonNull;

/**
 * Actual Maven component selected by Gradle, independent of its requested selector.
 */
public record ResolvedArtifact(String group, String name, String version) {
    public boolean isValid() {
        return group != null && !group.isBlank() && name != null && !name.isBlank()
               && version != null && !version.isBlank();
    }

    @Override
    public @NonNull String toString() {
        return group + ":" + name + ":" + version;
    }
}
