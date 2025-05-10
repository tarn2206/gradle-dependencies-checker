package com.github.tarn2206.actions;

import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class RefreshAction extends AnAction
{
    private final DependenciesView view;

    public RefreshAction(DependenciesView view)
    {
        super("Check for Updates...", null, Actions.Refresh);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e)
    {
        view.update();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread()
    {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent e)
    {
        e.getPresentation().setEnabled(view.isIdle());
    }
}
