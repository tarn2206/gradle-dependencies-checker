package com.github.tarn2206.actions;

import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class CollapseAllAction extends AnAction {
    private final DependenciesView view;

    public CollapseAllAction(DependenciesView view) {
        super("Collapse All", "Collapse every node in the tree", AllIcons.Actions.Collapseall);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        view.collapseAll();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}