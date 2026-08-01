package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.CatalogEntry;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.DependencyStateService;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.toml.lang.psi.TomlKeyValue;

public class OutdatedDependencyTomlInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!CatalogPsiHelpers.isCatalogFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        var state = holder.getProject().getService(DependencyStateService.class);
        var catalog = state.getCatalog();

        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof TomlKeyValue keyValue)) return;

                var tableName = CatalogPsiHelpers.containingTableName(keyValue);
                if (tableName == null) return;

                var entryKey = CatalogPsiHelpers.entryKey(keyValue);
                if (entryKey == null) return;

                switch (tableName) {
                    case CatalogPsiHelpers.LIBRARIES ->
                            handleLibraryOrPlugin(keyValue, entryKey, state.byCatalogKey(entryKey),
                                    CatalogEntry.Kind.LIBRARY);
                    case CatalogPsiHelpers.PLUGINS ->
                            handleLibraryOrPlugin(keyValue, entryKey, state.byPluginKey(entryKey),
                                    CatalogEntry.Kind.PLUGIN);
                    case CatalogPsiHelpers.VERSIONS -> handleVersionKey(keyValue, entryKey);
                    default -> { /* other tables not our concern */ }
                }
            }

            /**
             * Direct case (line has an editable version): squiggle + quick-fix that bumps the
             * version on this line. BOM-managed case: if a managing BOM can be identified, offer a
             * "via BOM" quick-fix that routes to the BOM's entry. If no BOM can be pinned down,
             * skip — nothing safe to click.
             */
            private void handleLibraryOrPlugin(TomlKeyValue keyValue, String entryKey,
                                               @Nullable Dependency dep, CatalogEntry.Kind kind) {
                if (dep == null) return;

                var lineEntry = catalog == null ? null : catalog.findEntryByKey(entryKey);
                if (lineEntry == null) return;

                if (lineEntry.hasEditableVersion()) {
                    if (!dep.hasMeaningfulUpdate()) return;
                    registerDirect(keyValue, entryKey, kind, dep);
                } else {
                    registerViaBom(keyValue, dep);
                }
            }

            private void registerDirect(TomlKeyValue keyValue, String entryKey,
                                        CatalogEntry.Kind kind, Dependency dep) {
                var anchor = CatalogPsiHelpers.highlightAnchor(keyValue);
                if (anchor == null) return;
                holder.registerProblem(
                        anchor,
                        "Newer version available: " + dep.getVersion() + " → " + dep.getLatestVersion(),
                        ProblemHighlightType.WEAK_WARNING,
                        new ApplyUpdateQuickFix(entryKey, kind, dep.getLatestVersion())
                );
            }

            private void registerViaBom(TomlKeyValue keyValue, Dependency dep) {
                if (catalog == null) return;
                var bomEntry = catalog.findManagingBom(dep.getGroup(), dep.getName());
                if (bomEntry == null) return;
                var bomDep = state.byCatalogKey(bomEntry.key());
                if (bomDep == null || !bomDep.hasMeaningfulUpdate()) return;

                var anchor = CatalogPsiHelpers.highlightAnchor(keyValue);
                if (anchor == null) return;

                // The quick-fix targets the BOM's catalog key. Applying it invokes DependencyUpdater
                // against the BOM's dep, which fires the shared-version-key dialog when the BOM's
                // version.ref is shared (typical).
                holder.registerProblem(
                        anchor,
                        "Managed by BOM '" + bomEntry.key() + "', which has a newer version: "
                                + bomDep.getVersion() + " → " + bomDep.getLatestVersion(),
                        ProblemHighlightType.WEAK_WARNING,
                        new ApplyUpdateQuickFix(bomEntry.key(), CatalogEntry.Kind.LIBRARY, bomDep.getLatestVersion())
                );
            }

            /**
             * [versions] entry — highlight if any library or plugin using this key has an update.
             * The quick-fix uses one of those deps to route through DependencyUpdater, which
             * correctly edits the version literal here on this line.
             */
            private void handleVersionKey(TomlKeyValue keyValue, String versionKey) {
                var dep = state.anyUpdatableForVersionKey(versionKey);
                if (dep == null) return;

                var catEntry = dep.getCatalogEntry();
                if (catEntry == null) return;

                var anchor = CatalogPsiHelpers.highlightAnchor(keyValue);
                if (anchor == null) return;

                holder.registerProblem(
                        anchor,
                        "Newer version available: " + dep.getVersion() + " → " + dep.getLatestVersion(),
                        ProblemHighlightType.WEAK_WARNING,
                        new ApplyUpdateQuickFix(catEntry.key(), catEntry.kind(), dep.getLatestVersion())
                );
            }
        };
    }
}