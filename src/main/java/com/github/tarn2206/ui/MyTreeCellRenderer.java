package com.github.tarn2206.ui;

import java.awt.Color;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import com.github.tarn2206.tooling.Dependency;
import com.intellij.icons.AllIcons.Ide;
import com.intellij.icons.AllIcons.Nodes;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import static com.intellij.ui.SimpleTextAttributes.ERROR_ATTRIBUTES;
import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;
import static com.intellij.ui.SimpleTextAttributes.STYLE_WAVED;

public class MyTreeCellRenderer extends ColoredTreeCellRenderer
{
    public static final SimpleTextAttributes ORANGE_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0xf29a32), new Color(0xf0a732)));
    public static final SimpleTextAttributes CYAN_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x40b6e0), new Color(0x40b6e0)));
    public static final SimpleTextAttributes GREEN_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x59A869), new Color(0x499C54)));
    public static final SimpleTextAttributes WAVE_ATTRIBUTES = new SimpleTextAttributes(STYLE_WAVED, null, new JBColor(new Color(0xff0e14), new Color(0xe85259)));
    public static final SimpleTextAttributes GRAY_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, UIUtil.getInactiveTextColor());

    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus)
    {
        var node = (DefaultMutableTreeNode)value;
        var obj = node.getUserObject();
        if (obj instanceof String str)
        {
            setIcon(getNodeIcon(node));
            append(str);
        }
        else if (obj instanceof Dependency dependency)
        {
            renderDependency(node, dependency);
        }
        else if (obj instanceof Throwable)
        {
            setIcon(Ide.FatalError);
            append(obj.toString(), ERROR_ATTRIBUTES);
        }
        else if (obj != null)
        {
            append(obj.toString());
        }
    }

    private Icon getNodeIcon(DefaultMutableTreeNode node)
    {
        return node.getLevel() == 0 ? Nodes.PpJdk : Nodes.Module;
    }

    private void renderDependency(DefaultMutableTreeNode node, Dependency dependency)
    {
        setIcon(dependency.hasGroup() ? Nodes.PpLib : getNodeIcon(node));
        if (dependency.getLatestVersion() != null)
        {
            append(dependency.toString(), ORANGE_ATTRIBUTES);
            append(" -> ");
            append(dependency.getLatestVersion(), CYAN_ATTRIBUTES);
        }
        else if (dependency.getError() != null)
        {
            append(dependency.toString(), WAVE_ATTRIBUTES);
        }
        else
        {
            append(dependency.toString());
        }

        if (dependency.getStatus() != null)
        {
            append(", " + dependency.getStatus(), GRAY_ATTRIBUTES);
        }
        else if (dependency.getError() != null)
        {
            append(" - ");
            append(dependency.getError(), ERROR_ATTRIBUTES);
        }
    }
}
