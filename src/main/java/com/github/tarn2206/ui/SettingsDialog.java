package com.github.tarn2206.ui;

import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.FontUIResource;

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

public class SettingsDialog extends DialogWrapper
{
    private final AppSettings settings = AppSettings.getInstance();
    private JBCheckBox ignoreUnstable;
    private JBTextField unstablePatterns;
    private RepositoryTable table;

    public SettingsDialog()
    {
        super(true);
        setResizable(false);
        setTitle("Dependency Updates");
        init();
    }

    @Override
    protected JComponent createCenterPanel()
    {
        var layout = new GridLayoutManager(4, 1);
        layout.setMargin(JBUI.insets(5));
        var panel = new JPanel(layout);

        var label = new JLabel("Maven Repository");
        panel.add(label, new GridConstraints(0, 0, 1, 1, 8, 0, 0, 0, null, null, null));

        table = new RepositoryTable();
        table.setPreferredSize(new Dimension(800, 240));
        panel.add(table, new GridConstraints(1, 0, 1, 1, 0, 3, 3, 2, null, new Dimension(780, 240), null));

        panel.add(createCheckboxPanel(), new GridConstraints(2, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        panel.add(createUnstablePanel(), new GridConstraints(3, 0, 1, 1, 0, 3, 3, 0, null, null, null));

        loadSettings();

        return panel;
    }

    private JPanel createCheckboxPanel()
    {
        ignoreUnstable = new JBCheckBox("Ignore unstable version");
        ignoreUnstable.addActionListener(e -> unstablePatterns.setEnabled(ignoreUnstable.isSelected()));

        var layout = new GridLayoutManager(1, 1);
        layout.setMargin(JBUI.insetsTop(10));
        var panel = new JPanel(layout);
        panel.add(ignoreUnstable, new GridConstraints(0, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        return panel;
    }

    private JPanel createUnstablePanel()
    {
        var layout = new GridLayoutManager(2, 2);
        layout.setVGap(0);
        var panel = new JPanel(layout);
        unstablePatterns = new JBTextField();
        panel.add(unstablePatterns, new GridConstraints(0, 1, 1, 1, 0, 3, 3, 3, null, null, null));

        var hint = new JLabel("Comma separated list of unstable version patterns");
        hint.setForeground(UIUtil.getContextHelpForeground());
        if (SystemInfo.isMac)
        {
            var font = hint.getFont();
            var size = font.getSize2D();
            font = new FontUIResource(font.deriveFont(size - JBUIScale.scale(2))); // Allow to reset the font by UI
            hint.setFont(font);
        }
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        panel.add(hint, new GridConstraints(1, 1, 1, 1, 0, 3, 3, 3, null, null, null));
        return panel;
    }

    public void loadSettings()
    {
        table.setRepos(settings.repos);

        ignoreUnstable.setSelected(settings.ignoreUnstable);
        unstablePatterns.setEnabled(settings.ignoreUnstable);
        unstablePatterns.setText(settings.unstablePatterns);
    }

    public void saveSettings()
    {
        settings.repos = table.getRepos();
        settings.ignoreUnstable = ignoreUnstable.isSelected();
        settings.unstablePatterns = unstablePatterns.getText();
    }
}
