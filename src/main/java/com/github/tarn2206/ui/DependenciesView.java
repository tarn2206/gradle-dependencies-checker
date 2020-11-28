package com.github.tarn2206.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.GradleHelper;
import com.github.tarn2206.tooling.MavenUtils;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import hu.akarnokd.rxjava3.swing.SwingSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;

public class DependenciesView extends SimpleToolWindowPanel
{
    private final Project project;
    private Tree tree;
    private DefaultMutableTreeNode rootNode;
    private int worker = 0;

    public DependenciesView(Project project)
    {
        super(true, true);

        this.project = project;
        DefaultActionGroup group = new DefaultActionGroup("ACTION_GROUP", false);
        group.addAction(new RefreshAction(this));

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("ACTION_TOOLBAR", group, true);
        setToolbar(toolbar.getComponent());
    }

    public void initToolWindow(@NotNull ToolWindow toolWindow)
    {
        Content content = ContentFactory.SERVICE.getInstance().createContent(this, "", false);
        toolWindow.getContentManager().addContent(content);

        rootNode = new DefaultMutableTreeNode();
        tree = new Tree(rootNode);
        tree.setCellRenderer(new MyTreeCellRenderer());
        setContent(new JBScrollPane(tree));

        StartupManager.getInstance(project).runWhenProjectIsInitialized(this::run);
    }

    public boolean isIdle()
    {
        return worker == 0;
    }

    public void run()
    {
        rootNode.removeAllChildren();
        rootNode.setUserObject(new MyObject<>("loading..."));
        worker = 1;
        fromCallable(() -> GradleHelper.listProjects(project.getBasePath()))
                .subscribe(this::addProjects, tr -> onError(rootNode, tr));
    }

    private void addProjects(List<String> projects)
    {
        if (projects.size() > 0)
        {
            List<Item> list = new ArrayList<>();
            rootNode.setUserObject(new MyObject<>(projects.get(0)));
            File buildFile = new File(project.getBasePath(), "build.gradle");
            if (buildFile.exists())
            {
                list.add(new Item(rootNode, project.getBasePath()));
            }
            for (int i = 1; i < projects.size(); i++)
            {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new MyObject<>(projects.get(i)));
                rootNode.add(node);
                if (i == 1 && !buildFile.exists())
                {
                    expandNode(rootNode);
                }
                list.add(new Item(node, project.getBasePath() + File.separatorChar + projects.get(i)));
            }
            listDependencies(list);
        }
        worker--;
    }

    private void listDependencies(List<Item> list)
    {
        if (list.isEmpty()) return;

        Item item = list.remove(0);
        item.node.insert(new DefaultMutableTreeNode("loading..."), 0);
        expandNode(item.node);

        worker++;
        fromCallable(() -> GradleHelper.listDependencies(item.path))
                .subscribe(dependencies -> {
                    displayDependencies(item.node, dependencies);
                    listDependencies(list);
                }, tr -> {
                    onError(item.node, tr);
                    listDependencies(list);
                });
    }

    private void displayDependencies(DefaultMutableTreeNode parent, List<Dependency> dependencies)
    {
        parent.remove(0);
        int n = 0;
        for (Dependency dependency : dependencies)
        {
            if (!"project ".equals(dependency.group))
            {
                MyObject<Dependency> myObject = new MyObject<>(dependency);
                myObject.status = "check for updates...";
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(myObject);
                parent.insert(child, n++);
                worker++;
                fromCallable(() -> MavenUtils.checkForUpdate(dependency))
                        .subscribe(result -> {
                            myObject.status = null;
                            tree.updateUI();
                            worker--;
                        }, tr -> {
                            myObject.status = null;
                            onError(child, tr);
                        });
            }
            else
            {
                if (parent != rootNode)
                {
                    parent.insert(new DefaultMutableTreeNode(new MyObject<>(dependency.toString())), n++);
                }
                if (!exists(rootNode, dependency.toString()))
                {
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(new MyObject<>("* " + dependency.toString()));
                    rootNode.add(child);
                    //listDependencies(child, project.getBasePath() + File.separatorChar + dependency.name);
                }
            }
        }
        worker--;
        tree.updateUI();
    }

    private boolean exists(DefaultMutableTreeNode node, String text)
    {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++)
        {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode)node.getChildAt(i);
            MyObject<?> myObject = (MyObject<?>)child.getUserObject();
            if (myObject.data instanceof String && text.equals(myObject.data))
            {
                return true;
            }
        }
        return false;
    }

    private void onError(DefaultMutableTreeNode node, Throwable tr)
    {
        tr.printStackTrace();

        node.removeAllChildren();
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(new MyObject<>(tr));
        node.add(child);
        expandNode(node);
        worker--;
    }

    private void expandNode(DefaultMutableTreeNode node)
    {
        tree.expandPath(new TreePath(node.getPath()));
        tree.updateUI();
    }

    private static <T> Single<? extends T> fromCallable(Callable<? extends T> callable)
    {
        return Single.fromCallable(callable)
                     .subscribeOn(Schedulers.io())
                     .observeOn(SwingSchedulers.edt());
    }
}
