package com.github.tarn2206.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class RefreshAction extends AnAction
{
    private final DependenciesView view;

    public RefreshAction(DependenciesView view)
    {
        super("Check for Updates...", null, AllIcons.Actions.Refresh);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e)
    {
        view.run();
    }

    @Override
    public void update(AnActionEvent e)
    {
        e.getPresentation().setEnabled(view.isIdle());
    }
}
