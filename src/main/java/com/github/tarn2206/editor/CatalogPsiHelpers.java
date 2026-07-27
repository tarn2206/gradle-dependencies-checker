package com.github.tarn2206.editor;

import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.toml.lang.psi.TomlKey;
import org.toml.lang.psi.TomlKeyValue;
import org.toml.lang.psi.TomlTable;

/**
 * Utilities for interpreting a Gradle version catalog TOML PSI tree.
 * Kept lightweight — no PSI traversal beyond what inspections need per element.
 */
public final class CatalogPsiHelpers {
    public static final String LIBRARIES = "libraries";
    public static final String PLUGINS = "plugins";
    public static final String VERSIONS = "versions";

    private CatalogPsiHelpers() {}

    /** Any file ending in ".versions.toml" is treated as a catalog. Non-catalog files get no highlights via state lookup misses. */
    public static boolean isCatalogFile(@Nullable PsiFile file) {
        return file != null && file.getName().endsWith(".versions.toml");
    }

    /** Returns the containing table name (e.g., "libraries"), or null if the entry isn't in a named table. */
    public static @Nullable String containingTableName(TomlKeyValue keyValue) {
        var table = PsiTreeUtil.getParentOfType(keyValue, TomlTable.class);
        if (table == null) return null;
        var header = table.getHeader();
        var key = header.getKey();
        return key == null ? null : key.getText();
    }

    /** For a key like "guava-testlib", returns "guava-testlib". For dotted keys, joins segments. */
    public static @Nullable String entryKey(TomlKeyValue keyValue) {
        var key = keyValue.getKey();
        return key == null ? null : key.getText();
    }

    /** The most useful highlight anchor: the key element within a TomlKeyValue. */
    public static @Nullable TomlKey highlightAnchor(TomlKeyValue keyValue) {
        return keyValue.getKey();
    }
}