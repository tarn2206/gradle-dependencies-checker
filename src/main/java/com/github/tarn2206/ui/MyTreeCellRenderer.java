package com.github.tarn2206.ui;

import com.github.tarn2206.tooling.CatalogEntry;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.Vulnerability;
import com.intellij.icons.AllIcons.Ide;
import com.intellij.icons.AllIcons.Nodes;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.util.List;

import static com.intellij.ui.SimpleTextAttributes.ERROR_ATTRIBUTES;
import static com.intellij.ui.SimpleTextAttributes.STYLE_BOLD;
import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;
import static com.intellij.ui.SimpleTextAttributes.STYLE_WAVED;

public class MyTreeCellRenderer extends ColoredTreeCellRenderer {
    public static final SimpleTextAttributes ORANGE_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0xf29a32), new Color(0xf0a732)));
    public static final SimpleTextAttributes CYAN_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x40b6e0), new Color(0x40b6e0)));
    public static final SimpleTextAttributes GREEN_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x59A869), new Color(0x499C54)));
    public static final SimpleTextAttributes WAVE_ATTRIBUTES = new SimpleTextAttributes(STYLE_WAVED, null, new JBColor(new Color(0xff0e14), new Color(0xe85259)));
    public static final SimpleTextAttributes GRAY_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, UIUtil.getInactiveTextColor());
    public static final SimpleTextAttributes CRITICAL_ATTRIBUTES = new SimpleTextAttributes(STYLE_BOLD, new JBColor(new Color(0xff0e14), new Color(0xe85259)));

    private static final int MAX_ERROR_LEN = 200;

    private static String coordDisplay(Dependency dep) {
        var entry = dep.getCatalogEntry();
        if (entry != null && entry.kind() == CatalogEntry.Kind.PLUGIN && entry.pluginId() != null) {
            return dep.getVersion() == null ? entry.pluginId() : entry.pluginId() + ":" + dep.getVersion();
        }
        return dep.toString();
    }

    /**
     * Truncate & first-line-ify a Throwable for single-line tree rendering.
     */
    private static String shortenThrowable(Throwable tr) {
        var msg = tr.getMessage();
        if (msg == null || msg.isBlank()) return tr.getClass().getSimpleName();
        return shortenError(msg);
    }

    private static String shortenError(String msg) {
        var firstLine = msg.split("\\R", 2)[0].trim();
        var cut = firstLine.indexOf(" Searched in");
        if (cut > 0) firstLine = firstLine.substring(0, cut).trim();
        return firstLine.length() > MAX_ERROR_LEN ? firstLine.substring(0, MAX_ERROR_LEN - 3) + "..." : firstLine;
    }

    private static Vulnerability.Severity highestSeverity(List<Vulnerability> vulns) {
        if (vulns == null || vulns.isEmpty()) return Vulnerability.Severity.UNKNOWN;
        var top = Vulnerability.Severity.UNKNOWN;
        for (var v : vulns) {
            top = Vulnerability.Severity.max(top, v.severity());
        }
        return top;
    }

    private static SimpleTextAttributes attributesFor(Vulnerability.Severity severity) {
        return switch (severity) {
            case CRITICAL -> CRITICAL_ATTRIBUTES;
            case HIGH, MODERATE -> ORANGE_ATTRIBUTES;
            case LOW, UNKNOWN -> GRAY_ATTRIBUTES;
        };
    }

    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        var node = (DefaultMutableTreeNode) value;
        var obj = node.getUserObject();
        if (obj instanceof String str) {
            setIcon(getNodeIcon(node));
            append(str);
        } else if (obj instanceof Dependency dependency) {
            renderDependency(node, dependency);
        } else if (obj instanceof Throwable tr) {
            setIcon(Ide.FatalError);
            append(shortenThrowable(tr), ERROR_ATTRIBUTES);
        } else if (obj != null) {
            append(obj.toString());
        }
    }

    private Icon getNodeIcon(DefaultMutableTreeNode node) {
        return node.getLevel() == 0 ? Nodes.PpJdk : Nodes.Module;
    }

    private void renderDependency(DefaultMutableTreeNode node, Dependency dependency) {
        var isPlugin = dependency.getCatalogEntry() != null
                && dependency.getCatalogEntry().kind() == CatalogEntry.Kind.PLUGIN;

        if (isPlugin) {
            setIcon(Nodes.Plugin);
        } else {
            setIcon(dependency.hasGroup() ? Nodes.PpLib : getNodeIcon(node));
        }

        var vulns = dependency.getVulnerabilities();
        var highestSeverity = highestSeverity(vulns);

        if (vulns != null && !vulns.isEmpty()) {
            append("\u26A0 ", attributesFor(highestSeverity));
        }

        var coord = coordDisplay(dependency);
        if (dependency.hasMeaningfulUpdate()) {
            append(coord, ORANGE_ATTRIBUTES);
            append(" -> ");
            append(dependency.getLatestVersion(), CYAN_ATTRIBUTES);
        } else if (dependency.getError() != null) {
            append(coord, WAVE_ATTRIBUTES);
        } else {
            append(coord);
        }

        if (dependency.getCatalogEntry() != null) {
            append(" (" + dependency.getCatalogEntry().displayName() + ")", GRAY_ATTRIBUTES);
        }

        if (vulns != null && !vulns.isEmpty()) {
            var count = vulns.size();
            var label = ", " + count + (count == 1 ? " CVE" : " CVEs") + " (" + highestSeverity.name() + ")";
            append(label, attributesFor(highestSeverity));
        }

        if (dependency.getStatus() != null) {
            append(", " + dependency.getStatus(), GRAY_ATTRIBUTES);
        } else if (dependency.getError() != null) {
            append(" - ");
            append(shortenError(dependency.getError()), ERROR_ATTRIBUTES);
        }
    }
}
