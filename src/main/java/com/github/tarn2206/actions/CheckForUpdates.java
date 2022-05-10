package com.github.tarn2206.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class CheckForUpdates extends AnAction
{
    private static final String TOOL_WINDOW_ID = "com.github.tarn2206.dependency.updates";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e)
    {
        var project = e.getProject();
        if (project == null)
        {
            return;
        }

        var toolWindowManager = ToolWindowManager.getInstance(project);
        var toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null)
        {
            toolWindow.show(null);
        }
    }
}
