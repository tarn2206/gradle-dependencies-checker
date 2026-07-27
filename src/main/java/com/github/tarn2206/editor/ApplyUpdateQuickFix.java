package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.CatalogEntry;
import com.github.tarn2206.tooling.DependencyStateService;
import com.github.tarn2206.tooling.DependencyUpdater;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix that applies the currently-known update to a catalog entry.
 * <p>
 * Persistable: stores only strings + an enum. Live {@link com.github.tarn2206.tooling.Dependency}
 * is looked up from {@link DependencyStateService} at apply time — if state has been cleared
 * (e.g. tool window refresh in progress) we bail out with a clear message.
 */
public class ApplyUpdateQuickFix implements LocalQuickFix {
    private static final String NOTIFICATION_GROUP = "Dependency Updates";

    private final String catalogKey;
    private final CatalogEntry.Kind kind;
    private final String newVersion;

    public ApplyUpdateQuickFix(String catalogKey, CatalogEntry.Kind kind, String newVersion) {
        this.catalogKey = catalogKey;
        this.kind = kind;
        this.newVersion = newVersion;
    }

    private static void notify(Project project, String content, NotificationType type) {
        Notifications.Bus.notify(new Notification(NOTIFICATION_GROUP, "Dependency Update", content, type), project);
    }

    @Override
    public @NotNull String getName() {
        return "Update to " + newVersion;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Update Gradle catalog entry";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        var state = project.getService(DependencyStateService.class);
        var dep = kind == CatalogEntry.Kind.PLUGIN
                ? state.byPluginKey(catalogKey)
                : state.byCatalogKey(catalogKey);
        if (dep == null || dep.getLatestVersion() == null) {
            Messages.showErrorDialog(project,
                    "Update no longer available for '" + catalogKey + "'. Try refreshing the Dependency Updates tool window.",
                    "Apply Dependency Update");
            return;
        }

        var result = DependencyUpdater.apply(project, dep, state.getCatalog());

        switch (result.status()) {
            case UPDATED -> {
                // Prevent the same inspection from re-firing until the next refresh confirms the new state.
                dep.setLatestVersion(null);
                state.notifyChange();
                notify(project, result.message(), NotificationType.INFORMATION);
            }
            case NOT_FOUND -> {
                if (result.file() != null) {
                    new OpenFileDescriptor(project, result.file(),
                            Math.max(result.line() - 1, 0), 0).navigate(true);
                }
                notify(project, result.message(), NotificationType.WARNING);
            }
            case FAILED -> Messages.showErrorDialog(project, result.message(), "Apply Dependency Update");
            case CANCELLED -> {
            }
        }
    }
}