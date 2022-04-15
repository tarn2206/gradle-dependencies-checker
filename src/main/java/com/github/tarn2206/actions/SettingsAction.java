package com.github.tarn2206.actions;

import com.github.tarn2206.ui.SettingsDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class SettingsAction extends AnAction
{
    public SettingsAction()
    {
        super("Settings", null, AllIcons.Actions.Properties);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e)
    {
        var dialog = new SettingsDialog();
        if (dialog.showAndGet())
        {
            dialog.save();
        }
    }
}
