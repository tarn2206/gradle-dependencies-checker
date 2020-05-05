package com.github.tarn2206;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class DependenciesUpdatesAction extends AnAction
{
    private static final String TOOL_WINDOW_ID = "Dependencies";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e)
    {
        Project project = e.getProject();
        if (project == null)
        {
            return;
        }

        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null)
        {
            toolWindowManager.registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.RIGHT, true);
        }
    }
}
