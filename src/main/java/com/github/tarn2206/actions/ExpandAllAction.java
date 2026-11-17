package com.github.tarn2206.actions;

import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class ExpandAllAction extends AnAction {
    private final DependenciesView view;

    public ExpandAllAction(DependenciesView view) {
        super("Expand All", "Expand every node in the tree", AllIcons.Actions.Expandall);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        view.expandAll();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}