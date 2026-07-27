package com.github.tarn2206.tooling;

import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * A single entry in a Gradle version catalog — either a {@code [libraries]} or {@code [plugins]} entry.
 * <p>
 * For {@link Kind#LIBRARY}, {@code pluginId} is null and the coordinate is carried on the
 * associated {@link Dependency}. For {@link Kind#PLUGIN}, {@code pluginId} holds the Gradle
 * plugin ID (e.g. {@code "org.springframework.boot"}).
 * <p>
 * Exactly one of {@code versionRef} and {@code inlineVersion} may be non-null. When both are null,
 * the entry has no version declared (BOM-managed library, or a Gradle built-in plugin).
 */
public record CatalogEntry(
        Kind kind,
        String key,
        @Nullable String pluginId,
        @Nullable String versionRef,
        @Nullable String inlineVersion,
        File tomlFile,
        int versionLineNumber
) {
    public enum Kind { LIBRARY, PLUGIN }

    /** Gradle-style accessor as it appears in build.gradle. */
    public String displayName() {
        var dotted = key.replace('-', '.');
        return kind == Kind.PLUGIN ? "libs.plugins." + dotted : "libs." + dotted;
    }

    public boolean hasEditableVersion() {
        return versionRef != null || inlineVersion != null;
    }
}