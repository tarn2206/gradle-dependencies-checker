package com.github.tarn2206.actions;

import com.github.tarn2206.tooling.DependencyUpdater;
import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons.Actions;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class ApplyUpdateAction extends AnAction {
    private static final String NOTIFICATION_GROUP = "Dependency Updates";
    private final DependenciesView view;

    public ApplyUpdateAction(DependenciesView view) {
        super("Apply Update", "Update to the latest version", Actions.Download);
        this.view = view;
    }

    private static void notify(Project project, String content, NotificationType type) {
        var notification = new Notification(NOTIFICATION_GROUP, "Dependency Update", content, type);
        Notifications.Bus.notify(notification, project);
    }

    /**
     * Ensures the tool window is visible before we show the notification/refresh, so users see the result.
     * Currently unused, kept for potential future integration when triggering from other places.
     */
    @SuppressWarnings("unused")
    private static void showToolWindow(Project project) {
        var mgr = ToolWindowManager.getInstance(project);
        var tw = mgr.getToolWindow("Dependency Updates");
        if (tw != null) tw.show(null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var dep = view.getSelectedDependency();
        var enabled = dep != null
                && dep.getLatestVersion() != null
                && dep.getVersion() != null
                && view.isIdle();
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        var dep = view.getSelectedDependency();
        if (project == null || dep == null || dep.getLatestVersion() == null) return;

        var result = DependencyUpdater.apply(project, dep, view.getCatalog());
        handleResult(project, result);
    }

    private void handleResult(Project project, DependencyUpdater.Result result) {
        switch (result.status()) {
            case UPDATED -> {
                notify(project, result.message(), NotificationType.INFORMATION);
                view.update();
            }
            case NOT_FOUND -> {
                if (result.file() != null) {
                    // Open the file with caret at the best-guess line so the user can edit manually.
                    var descriptor = new OpenFileDescriptor(project, result.file(),
                            Math.max(result.line() - 1, 0), 0);
                    descriptor.navigate(true);
                }
                notify(project, result.message(), NotificationType.WARNING);
            }
            case FAILED -> Messages.showErrorDialog(project, result.message(), "Apply Dependency Update");
            case CANCELLED -> {
                // no-op
            }
        }
    }
}