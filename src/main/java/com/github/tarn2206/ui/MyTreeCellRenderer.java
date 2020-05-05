package com.github.tarn2206.ui;

import java.awt.Color;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import com.github.tarn2206.tooling.Dependency;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;

public class MyTreeCellRenderer extends ColoredTreeCellRenderer
{
    public static final SimpleTextAttributes ORANGE_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0xEDA200), new Color(0xF0A732)));
    public static final SimpleTextAttributes CYAN_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x389FD6), new Color(0x3592C4)));
    public static final SimpleTextAttributes GREEN_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x59A869), new Color(0x499C54)));
    public static final SimpleTextAttributes RED_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0xDB5860), new Color(0xC75450)));
    public static final SimpleTextAttributes GRAY_ATTRIBUTES = new SimpleTextAttributes(STYLE_PLAIN, new JBColor(new Color(0x6E6E6E), new Color(0xAFB1B3)));

    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row,
                                      boolean hasFocus)
    {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
        Object obj = node.getUserObject();
        if (obj instanceof UserObject)
        {
            UserObject userObject = (UserObject)obj;
            if (userObject.data instanceof Dependency)
            {
                setIcon(AllIcons.Nodes.PpLib);
                Dependency dependency = (Dependency)userObject.data;
                if (dependency.hasLatestVersion())
                {
                    append(dependency.group + ':' + dependency.name + ':');
                    append(dependency.version, ORANGE_ATTRIBUTES);
                    append(" -> ");
                    append(dependency.latestVersion, CYAN_ATTRIBUTES);
                    return;
                }
            }
            else setIcon(node.getLevel() < 2 ? AllIcons.Nodes.PpJdk : AllIcons.Nodes.Module);

            append(userObject.data.toString());
            appendText(userObject.status, ORANGE_ATTRIBUTES);
            appendText(userObject.error, RED_ATTRIBUTES);
        }
        else if (obj != null) append(obj.toString(), GRAY_ATTRIBUTES);
    }

    private void appendText(String s, SimpleTextAttributes attr)
    {
        if (s == null || s.length() == 0) return;
        append(" - ");
        append(s, attr);
    }
}
