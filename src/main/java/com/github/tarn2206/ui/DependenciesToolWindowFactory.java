package com.github.tarn2206.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.settings.GradleSettings;

public class DependenciesToolWindowFactory implements ToolWindowFactory {
    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return !GradleSettings.getInstance(project).getLinkedProjectsSettings().isEmpty();
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var view = new DependenciesView(project, toolWindow);
        var content = ContentFactory.getInstance().createContent(view, null, false);
        toolWindow.getContentManager().addContent(content);
        view.init();
    }
}
