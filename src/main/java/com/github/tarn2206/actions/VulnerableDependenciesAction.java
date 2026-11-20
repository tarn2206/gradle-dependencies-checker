package com.github.tarn2206.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionWrapper;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps toolbar presentation separate from the IDE's shared action.
 */
public final class VulnerableDependenciesAction extends AnActionWrapper {
    public VulnerableDependenciesAction(@NotNull AnAction delegate) {
        super(delegate);
        getTemplatePresentation().setIcon(AllIcons.Toolwindows.ToolWindowProblems);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        super.update(event);
        event.getPresentation().setIcon(AllIcons.Toolwindows.ToolWindowProblems);
    }
}
