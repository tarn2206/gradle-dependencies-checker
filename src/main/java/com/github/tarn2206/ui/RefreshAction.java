package com.github.tarn2206.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

public class RefreshAction extends AnAction
{
    private final DependenciesView view;

    public RefreshAction(DependenciesView view)
    {
        super("Refresh", "Refresh all projects", AllIcons.Actions.Refresh);
        this.view = view;
    }

    @Override
    public void actionPerformed(AnActionEvent e)
    {
        view.run();
    }

    @Override
    public void update(AnActionEvent e)
    {
        e.getPresentation().setEnabled(view.isIdle());
    }
}
