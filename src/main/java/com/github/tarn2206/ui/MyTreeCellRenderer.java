package com.github.tarn2206.ui;

import java.awt.Color;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import com.github.tarn2206.tooling.Dependency;
import com.intellij.icons.AllIcons.Ide;
import com.intellij.icons.AllIcons.Nodes;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.codehaus.plexus.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import static com.intellij.ui.SimpleTextAttributes.ERROR_ATTRIBUTES;
import static com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES;
import static com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES;
import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;
import static com.intellij.ui.SimpleTextAttributes.STYLE_WAVED;

public class MyTreeCellRenderer extends ColoredTreeCellRenderer {
    private static final SimpleTextAttributes HAS_NEW_VERSION_ATTRIBUTES = new SimpleTextAttributes(STYLE_WAVED, null, new JBColor(new Color(0xE66F04), new Color(0xF2C55C)));
    private static final SimpleTextAttributes HAS_ERROR_ATTRIBUTES = new SimpleTextAttributes(STYLE_WAVED, null, new JBColor(new Color(0xF50000), new Color(0xF65354)));
    private static final SimpleTextAttributes NEW_VERSION_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x1193E3), new Color(0x50CBF4)));
    private static final SimpleTextAttributes OUTDATED_VERSION_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0xE66F04), new Color(0xF2C55C)));

    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        var node = (DefaultMutableTreeNode)value;
        setToolTipText(null);
        var obj = node.getUserObject();
        if (obj instanceof String str) {
            setIcon(getNodeIcon(node));
            append(str);
        } else if (obj instanceof Dependency dependency) {
            renderDependency(node, dependency);
        } else if (obj instanceof Throwable) {
            setIcon(Ide.FatalError);
            append(obj.toString(), ERROR_ATTRIBUTES);
        } else if (obj != null) {
            append(obj.toString());
        }
    }

    private Icon getNodeIcon(DefaultMutableTreeNode node) {
        return node.getLevel() == 0 ? Nodes.PpJdk : Nodes.Module;
    }

    private void renderDependency(DefaultMutableTreeNode node, Dependency dependency) {
        setIcon(dependency.hasGroup() ? Nodes.PpLib : getNodeIcon(node));

        var attributes = REGULAR_ATTRIBUTES;
        if (StringUtils.isNotBlank(dependency.getError())) {
            attributes = HAS_ERROR_ATTRIBUTES;
            setToolTipText("<html>" + StringUtil.escapeXmlEntities(dependency.getError())
                                                .replace("\r\n", "\n")
                                                .replace("\n", "<br>")
                           + "</html>");
        } else if (dependency.getLatestVersion() != null)
            attributes = HAS_NEW_VERSION_ATTRIBUTES;
        append(dependency.coordinateText(), attributes);

        var resolved = dependency.resolvedVersion();
        var versionColor = dependency.getLatestVersion() == null ? REGULAR_ATTRIBUTES : OUTDATED_VERSION_ATTRIBUTES;
        if (dependency.getVersion() != null) {
            append(":");
            append(dependency.getVersion(), resolved.isEmpty() ? versionColor : GRAYED_ATTRIBUTES);
        }
        if (!resolved.isEmpty()) {
            append(" [");
            append(String.join(", ", resolved), versionColor);
            append("]");
        }
        if (dependency.getLatestVersion() != null) {
            append(" → ");
            append(dependency.availableUpdatesText(), NEW_VERSION_ATTRIBUTES);
        }

        if (dependency.getStatus() != null) {
            append(", " + dependency.getStatus(), GRAYED_ATTRIBUTES);
        }
    }
}
