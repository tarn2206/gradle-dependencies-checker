package com.github.tarn2206.ui;

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
        listDependencies(root, project.getName(), project.getBasePath());
    }

    private void listDependencies(DefaultMutableTreeNode node, String projectName, String projectDir)
    {
        UserObject userObject = new UserObject(projectName);
        node.setUserObject(userObject);
        node.add(new DefaultMutableTreeNode("loading..."));
        tree.expandPath(new TreePath(node.getPath()));
        tree.updateUI();

        worker++;
        Single.fromCallable(() -> GradleHelper.listDependencies(projectDir))
              .subscribeOn(Schedulers.io())
              .observeOn(SwingSchedulers.edt())
              .subscribe(dependencies -> displayDependencies(node, dependencies), t -> onError(userObject, t));
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
                    parent.add(new DefaultMutableTreeNode(new UserObject(dependency.toString())));
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
                UserObject userObject = new UserObject(dependency);
                userObject.status = "Check for Updates...";
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(userObject);
                parent.add(child);
                worker++;
                Single.fromCallable(() -> MavenUtils.checkForUpdate(dependency))
                      .subscribeOn(Schedulers.io())
                      .observeOn(SwingSchedulers.edt())
                      .subscribe(result -> {
                          userObject.status = null;
                          tree.updateUI();
                          worker--;
                      }, t -> onError(userObject, t));
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
            UserObject userObject = (UserObject)child.getUserObject();
            if (userObject.data instanceof String)
            {
                String s = (String)userObject.data;
                if (s.equals(project)) return true;
            }
        }
        return false;
    }

    private void onError(UserObject userObject, Throwable t)
    {
        userObject.status = null;
        userObject.error = t.toString();
        tree.updateUI();
        Notify.error(project, userObject.error);
        worker--;
    }
}
