package com.github.tarn2206.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.util.GradleConstants;

/**
 * "Reload All Gradle Projects" — same effect as the button in the Gradle tool window, exposed on
 * the Dependency Updates toolbar so the user can pull IDE state back in sync after applying a
 * catalog change without leaving this tool window. Auto-refresh of dependency data (2 s after the
 * TOML change) still runs on top; this button is for the IDE-side reimport.
 */
public class SyncGradleAction extends AnAction {

    public SyncGradleAction() {
        super("Reload Gradle Projects", "Reimport Gradle projects for this workspace",
                icons.GradleIcons.Gradle);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) return;
        ExternalSystemUtil.refreshProjects(new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}