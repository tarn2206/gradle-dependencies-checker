package com.github.tarn2206.ui;

import java.io.File;
import java.util.List;
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
    private DefaultMutableTreeNode root;
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

        root = new DefaultMutableTreeNode();
        tree = new Tree(root);
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
        worker = 0;
        root.removeAllChildren();
        String basePath = project.getBasePath();
        File app = new File(basePath, "app/build.gradle");
        if (app.exists())
        {
            basePath = app.getParent();
        }
        listDependencies(root, project.getName(), basePath);
    }

    private void listDependencies(DefaultMutableTreeNode node, String projectName, String projectDir)
    {
        MyObject<String> myObject = new MyObject<>(projectName);
        node.setUserObject(myObject);
        node.add(new DefaultMutableTreeNode("loading..."));
        tree.expandPath(new TreePath(node.getPath()));
        tree.updateUI();

        worker++;
        Single.fromCallable(() -> GradleHelper.listDependencies(projectDir))
              .subscribeOn(Schedulers.io())
              .observeOn(SwingSchedulers.edt())
              .subscribe(dependencies -> displayDependencies(node, dependencies), t -> onError(node, t));
    }

    private void displayDependencies(DefaultMutableTreeNode parent, List<Dependency> dependencies)
    {
        parent.removeAllChildren();
        for (Dependency dependency : dependencies)
        {
            if ("project ".equals(dependency.group))
            {
                if (isProjectInTree(dependency.toString()))
                {
                    parent.add(new DefaultMutableTreeNode(new MyObject<>(dependency.toString())));
                }
                else
                {
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode();
                    root.add(child);
                    listDependencies(child, dependency.toString(), project.getBasePath() + "/" + dependency.name);
                }
            }
            else
            {
                MyObject<Dependency> myObject = new MyObject<>(dependency);
                myObject.status = "check for updates...";
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(myObject);
                parent.add(child);
                worker++;
                Single.fromCallable(() -> MavenUtils.checkForUpdate(dependency))
                      .subscribeOn(Schedulers.io())
                      .observeOn(SwingSchedulers.edt())
                      .subscribe(result -> {
                          myObject.status = null;
                          tree.updateUI();
                          worker--;
                      }, t -> onError(child, t));
            }
        }
        worker--;
        tree.updateUI();
    }

    private boolean isProjectInTree(String project)
    {
        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++)
        {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode)root.getChildAt(i);
            MyObject<?> myObject = (MyObject<?>)child.getUserObject();
            if (myObject.data instanceof String && project.equals(myObject.data))
            {
                return true;
            }
        }
        return false;
    }

    private void onError(DefaultMutableTreeNode node, Throwable t)
    {
        t.printStackTrace();

        node.removeAllChildren();
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(new MyObject<>(t));
        node.add(child);
        tree.updateUI();

        Notify.error(project, t.getMessage());
        worker--;
    }
}
