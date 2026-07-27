package com.github.tarn2206.ui;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;

public class SettingsDialog extends DialogWrapper {
    private final AppSettings settings = AppSettings.getInstance();
    private JBCheckBox ignoreUnstable;
    private JBTextField unstablePatterns;
    private JBCheckBox checkVulnerabilities;
    private JBCheckBox autoRefresh;
    private RepositoryTable table;

    public SettingsDialog() {
        super(true);
        setResizable(false);
        setTitle("Dependency Updates");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        var layout = new GridLayoutManager(7, 1);
        layout.setMargin(JBUI.insets(5));
        var panel = new JPanel(layout);

        addRepositoryTable(panel);
        panel.add(createUnstableCheckBox(), new GridConstraints(3, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        panel.add(createUnstableTextField(), new GridConstraints(4, 0, 1, 1, 0, 3, 3, 0, null, null, null));
        panel.add(createVulnerabilityCheckBox(), new GridConstraints(5, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        panel.add(createAutoRefreshCheckBox(), new GridConstraints(6, 0, 1, 1, 8, 0, 3, 0, null, null, null));

        loadSettings();

        return panel;
    }

    private void addRepositoryTable(JPanel panel) {
        var label = new JLabel("Maven Repository");
        panel.add(label, new GridConstraints(0, 0, 1, 1, 8, 0, 0, 0, null, null, null));

        table = new RepositoryTable();
        table.setPreferredSize(new Dimension(810, 240));
        panel.add(table, new GridConstraints(1, 0, 1, 1, 0, 3, 3, 2, null, new Dimension(780, 240), null));
        addHint(panel, 2, 0, "To authenticate the private maven repository, include the credentials in the URL, e.g., https://username:password@your-repo.com");
    }

    private JPanel createUnstableCheckBox() {
        ignoreUnstable = new JBCheckBox("Ignore unstable version");
        ignoreUnstable.addActionListener(e -> unstablePatterns.setEnabled(ignoreUnstable.isSelected()));

        var layout = new GridLayoutManager(1, 1);
        layout.setMargin(JBUI.insetsTop(10));
        var panel = new JPanel(layout);
        panel.add(ignoreUnstable, new GridConstraints(0, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        return panel;
    }

    private JPanel createUnstableTextField() {
        var layout = new GridLayoutManager(2, 2);
        layout.setVGap(0);
        var panel = new JPanel(layout);
        unstablePatterns = new JBTextField();
        panel.add(unstablePatterns, new GridConstraints(0, 1, 1, 1, 0, 3, 3, 3, null, null, null));
        addHint(panel, 1, 1, "Comma separated list of unstable version patterns");
        return panel;
    }

    private JPanel createVulnerabilityCheckBox() {
        checkVulnerabilities = new JBCheckBox("Check for vulnerabilities (queries osv.dev)");
        var layout = new GridLayoutManager(2, 1);
        layout.setMargin(JBUI.insetsTop(10));
        var panel = new JPanel(layout);
        panel.add(checkVulnerabilities, new GridConstraints(0, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        addHint(panel, 1, 0, "Sends dependency coordinates to osv.dev to look up known CVEs. Results cached for 6 hours.");
        return panel;
    }

    private JPanel createAutoRefreshCheckBox() {
        autoRefresh = new JBCheckBox("Auto-refresh on Gradle sync or catalog file changes");
        var layout = new GridLayoutManager(2, 1);
        layout.setMargin(JBUI.insetsTop(10));
        var panel = new JPanel(layout);
        panel.add(autoRefresh, new GridConstraints(0, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        addHint(panel, 1, 0, "Debounced by 2 seconds. Requires the tool window to have been opened at least once.");
        return panel;
    }

    private void addHint(JPanel panel, int row, int column, String text) {
        var hint = new JLabel(text);
        hint.setForeground(UIUtil.getContextHelpForeground());
        if (SystemInfo.isMac) {
            var font = hint.getFont();
            var size = font.getSize2D();
            font = new FontUIResource(font.deriveFont(size - JBUIScale.scale(1)));
            hint.setFont(font);
        }
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        panel.add(hint, new GridConstraints(row, column, 1, 1, 0, 3, 3, 3, null, null, null));
    }

    public void loadSettings() {
        table.setRepos(settings.getRepos());

        ignoreUnstable.setSelected(settings.isIgnoreUnstable());
        unstablePatterns.setEnabled(settings.isIgnoreUnstable());
        unstablePatterns.setText(settings.getUnstablePatterns());

        checkVulnerabilities.setSelected(settings.isCheckVulnerabilities());
        autoRefresh.setSelected(settings.isAutoRefresh());
    }

    public void saveSettings() {
        settings.setRepos(table.getRepos());
        settings.setIgnoreUnstable(ignoreUnstable.isSelected());
        settings.setUnstablePatterns(unstablePatterns.getText());
        settings.setCheckVulnerabilities(checkVulnerabilities.isSelected());
        settings.setAutoRefresh(autoRefresh.isSelected());
        // Invalidate MavenUtils cache — repo list or unstable rules may have changed.
        com.github.tarn2206.tooling.MavenUtils.clearCache();
    }
}