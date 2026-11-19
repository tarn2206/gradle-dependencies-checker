package com.github.tarn2206.actions;

import com.github.tarn2206.ui.SettingsDialog;
import com.intellij.icons.AllIcons.General;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class SettingsAction extends AnAction {
    public SettingsAction() {
        super("Settings", "Configure repositories and dependency update preferences", General.GearPlain);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var dialog = new SettingsDialog();
        if (dialog.showAndGet()) {
            dialog.saveSettings();
        }
    }
}
