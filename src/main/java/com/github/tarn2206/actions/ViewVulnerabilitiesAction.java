package com.github.tarn2206.actions;

import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons.General;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class ViewVulnerabilitiesAction extends AnAction {
    private final DependenciesView view;

    public ViewVulnerabilitiesAction(DependenciesView view) {
        super("Open Vulnerability Report", "View known vulnerabilities on OSV.dev", General.Warning);
        this.view = view;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        var dep = view.getSelectedDependency();
        var enabled = dep != null
                && dep.getVulnerabilities() != null
                && !dep.getVulnerabilities().isEmpty();
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var dep = view.getSelectedDependency();
        if (dep == null) return;
        var coord = dep.getGroup() + ":" + dep.getName();
        var encoded = URLEncoder.encode(coord, StandardCharsets.UTF_8);
        BrowserUtil.browse("https://osv.dev/list?q=" + encoded + "&ecosystem=Maven");
    }
}