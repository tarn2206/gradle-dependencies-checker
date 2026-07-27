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
import org.toml.lang.psi.TomlKeyValue;

public class OutdatedDependencyTomlInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!CatalogPsiHelpers.isCatalogFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        var state = holder.getProject().getService(DependencyStateService.class);

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
                            handleDirect(keyValue, entryKey, state.byCatalogKey(entryKey), CatalogEntry.Kind.LIBRARY);
                    case CatalogPsiHelpers.PLUGINS ->
                            handleDirect(keyValue, entryKey, state.byPluginKey(entryKey), CatalogEntry.Kind.PLUGIN);
                    case CatalogPsiHelpers.VERSIONS -> handleVersionKey(keyValue, entryKey, state);
                    default -> { /* other tables not our concern */ }
                }
            }

            /**
             * Library/plugin entry that has an editable version — either inline or via version.ref.
             * BOM-managed entries with no version at all are skipped: there's nothing here to fix.
             */
            private void handleDirect(TomlKeyValue keyValue, String entryKey,
                                      Dependency dep, CatalogEntry.Kind kind) {
                if (dep == null || !dep.hasMeaningfulUpdate()) return;

                var catEntry = dep.getCatalogEntry();
                if (catEntry == null || !catEntry.hasEditableVersion()) return;

                var anchor = CatalogPsiHelpers.highlightAnchor(keyValue);
                if (anchor == null) return;

                holder.registerProblem(
                        anchor,
                        "Newer version available: " + dep.getVersion() + " → " + dep.getLatestVersion(),
                        ProblemHighlightType.WEAK_WARNING,
                        new ApplyUpdateQuickFix(entryKey, kind, dep.getLatestVersion())
                );
            }

            /**
             * [versions] entry — highlight if any library or plugin using this key has an update.
             * The quick-fix uses one of those deps to route through DependencyUpdater, which
             * correctly edits the version literal here on this line.
             */
            private void handleVersionKey(TomlKeyValue keyValue, String versionKey, DependencyStateService state) {
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