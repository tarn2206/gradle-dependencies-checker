package com.github.tarn2206.actions;

import com.github.tarn2206.tooling.GradleSyncState;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import icons.GradleIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

/**
 * Starts an IDE sync; the cache's sync listener handles invalidation and UI refresh.
 */
public final class SyncGradleAction extends AnAction {
    public SyncGradleAction() {
        super("Sync Gradle Projects", "Reload all linked Gradle projects and refresh dependencies after sync", GradleIcons.Gradle);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (!canSync(project)) return;
        ExternalSystemUtil.refreshProjects(new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID));
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(canSync(event.getProject()));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static boolean canSync(@Nullable Project project) {
        return project != null && !project.isDisposed()
               && !GradleSyncState.isSyncing(project)
               && !GradleSettings.getInstance(project).getLinkedProjectsSettings().isEmpty();
    }
}
