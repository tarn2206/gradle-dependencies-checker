package com.github.tarn2206.actions;

import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class NavigateToSourceAction extends AnAction {
    private final DependenciesView view;

    public NavigateToSourceAction(DependenciesView view) {
        super("Jump to Source", "Open where this dependency is declared",
                AllIcons.Actions.EditSource);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var dep = view.getSelectedDependency();
        if (dep != null) {
            view.navigateToSource(dep);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(view.getSelectedDependency() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}