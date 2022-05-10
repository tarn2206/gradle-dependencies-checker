package com.github.tarn2206.ui;

import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;

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
        table.setPreferredSize(new Dimension(720, 200));
        panel.add(table, new GridConstraints(1, 0, 1, 1, 0, 3, 3, 2, null, new Dimension(700, 200), null));

        ignoreUnstable = new JBCheckBox("Ignore unstable version");
        ignoreUnstable.addActionListener(e -> unstablePatterns.setEnabled(ignoreUnstable.isSelected()));
        panel.add(wrap(ignoreUnstable), new GridConstraints(2, 0, 1, 1, 8, 0, 3, 0, null, null, null));

        var panel1 = new JPanel(new GridLayoutManager(1, 2));
        panel.add(panel1, new GridConstraints(3, 0, 1, 1, 0, 3, 3, 0, null, null, null));

        unstablePatterns = new JBTextField();
        var panel2 = UI.PanelFactory.panel(unstablePatterns)
                                    .withComment("   Comma separated list of unstable version patterns")
                                    .createPanel();
        panel1.add(panel2, new GridConstraints(0, 1, 1, 1, 0, 3, 3, 3, null, null, null));

        load();

        return panel;
    }

    private static JPanel wrap(JComponent component)
    {
        var layout = new GridLayoutManager(1, 1);
        layout.setMargin(JBUI.insets(10, 0, 0, 0));
        var panel = new JPanel(layout);
        panel.add(component, new GridConstraints(0, 0, 1, 1, 8, 0, 3, 0, null, null, null));
        return panel;
    }

    public void load()
    {
        table.setRepos(settings.repos);

        ignoreUnstable.setSelected(settings.ignoreUnstable);
        unstablePatterns.setEnabled(settings.ignoreUnstable);
        unstablePatterns.setText(settings.unstablePatterns);
    }

    public void save()
    {
        settings.repos = table.getRepos();
        settings.ignoreUnstable = ignoreUnstable.isSelected();
        settings.unstablePatterns = unstablePatterns.getText();
    }
}
