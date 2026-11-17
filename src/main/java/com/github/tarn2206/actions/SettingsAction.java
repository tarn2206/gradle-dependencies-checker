package com.github.tarn2206.actions;

import com.github.tarn2206.ui.DependenciesView;
import com.github.tarn2206.ui.SettingsDialog;
import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class SettingsAction extends AnAction {
    private final DependenciesView view;

    public SettingsAction(DependenciesView view) {
        super("Settings", null, Actions.Properties);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var dialog = new SettingsDialog();
        if (dialog.showAndGet()) {
            dialog.saveSettings();
            view.update();
        }
    }
}