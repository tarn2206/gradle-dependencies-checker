package com.github.tarn2206.actions;

import com.github.tarn2206.AppSettings;
import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

public class ShowOnlyUpgradableAction extends ToggleAction {
    private final DependenciesView view;

    public ShowOnlyUpgradableAction(DependenciesView view) {
        super("Show Only Upgradable", "Hide dependencies without an available update",
                AllIcons.General.Filter);
        this.view = view;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return AppSettings.getInstance().isShowOnlyUpgradable();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        view.setShowOnlyUpgradable(state);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}