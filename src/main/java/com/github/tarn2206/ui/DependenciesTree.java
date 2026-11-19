package com.github.tarn2206.ui;

import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import com.github.tarn2206.tooling.BuildSnapshot;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.ProjectInfo;
import com.intellij.ui.treeStructure.Tree;

/**
 * Owns tree structure and rendering. All methods run on the EDT.
 */
final class DependenciesTree extends Tree {
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);

    DependenciesTree() {
        setModel(model);
        setCellRenderer(new MyTreeCellRenderer());
    }

    void showLoading() {
        root.removeAllChildren();
        root.setUserObject("loading...");
        model.reload();
    }

    void refreshRow(DefaultMutableTreeNode node) {
        model.nodeChanged(node);
    }

    void refreshRows(Predicate<Dependency> matches) {
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode)nodes.nextElement();
            if (node.getUserObject() instanceof Dependency dependency && matches.test(dependency)) {
                model.nodeChanged(node);
            }
        }
    }

    void showSnapshot(BuildSnapshot snapshot, BiConsumer<Dependency, DefaultMutableTreeNode> checkUpdate) {
        root.removeAllChildren();
        if (snapshot == null) {
            root.setUserObject("Cannot load project info");
            model.reload();
            return;
        }
        var plugins = snapshot.plugins();
        if (!plugins.isEmpty()) {
            var pluginsNode = new DefaultMutableTreeNode("Plugins");
            root.add(pluginsNode);
            plugins.forEach(plugin -> addDependency(pluginsNode, plugin));
        }
        addProject(root, snapshot.projectInfo(), snapshot);
        model.reload();
        for (int row = 0; row < getRowCount(); row++) expandRow(row);

        // Build the entire tree before scheduling checks so rendering never sees a partial model.
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode)nodes.nextElement();
            if (node.getUserObject() instanceof Dependency dependency
                && dependency.hasGroup() && dependency.getVersion() != null) {
                checkUpdate.accept(dependency, node);
            }
        }
    }

    private void addProject(DefaultMutableTreeNode node, ProjectInfo info, BuildSnapshot snapshot) {
        node.setUserObject(new Dependency(info.name()));
        snapshot.dependencies(info).forEach(dependency -> addDependency(node, dependency));
        for (var childInfo : info.children()) {
            var child = new DefaultMutableTreeNode();
            node.add(child);
            addProject(child, childInfo, snapshot);
        }
    }

    private void addDependency(DefaultMutableTreeNode parent, Dependency dependency) {
        parent.add(new DefaultMutableTreeNode(dependency));
    }

    void showError(DefaultMutableTreeNode node, Throwable error) {
        if (node == null) {
            root.setUserObject(error);
            model.nodeChanged(root);
        } else {
            model.insertNodeInto(new DefaultMutableTreeNode(error), node, 0);
            model.nodeChanged(node);
            expandPath(new TreePath(node.getPath()));
        }
    }
}
