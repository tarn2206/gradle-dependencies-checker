package com.github.tarn2206;

import com.github.tarn2206.ui.DependenciesView;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

public class DependenciesToolWindowFactory implements ToolWindowFactory
{
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow)
    {
        new DependenciesView(project, toolWindow);
    }
}
