package com.github.tarn2206.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import com.github.tarn2206.AppSettings;
import com.github.tarn2206.actions.RefreshAction;
import com.github.tarn2206.actions.SettingsAction;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.GradleHelper;
import com.github.tarn2206.tooling.MavenUtils;
import com.github.tarn2206.tooling.ProjectInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;

public class DependenciesView extends SimpleToolWindowPanel
{
    private static final Logger LOG = Logger.getInstance(DependenciesView.class);
    private final transient Project project;
    private Tree tree;
    private DefaultMutableTreeNode rootNode;
    private final AtomicInteger worker = new AtomicInteger();
    private final Set<Dependency> updateSet = new HashSet<>();
    private final Queue<Dependency> updateList = new ConcurrentLinkedQueue<>();

    public DependenciesView(Project project)
    {
        super(true, true);
        this.project = project;
    }

    public void initToolWindow(ToolWindow toolWindow)
    {
        toolWindow.setTitleActions(List.of(new RefreshAction(this), new SettingsAction()));

        var contentFactory = ApplicationManager.getApplication().getService(ContentFactory.class);
        var content = contentFactory.createContent(this, "", false);
        toolWindow.getContentManager().addContent(content);

        rootNode = new DefaultMutableTreeNode();
        tree = new Tree(rootNode);
        tree.setCellRenderer(new MyTreeCellRenderer());
        setContent(new JBScrollPane(tree));

        update();
    }

    public boolean isIdle()
    {
        return worker.get() == 0;
    }

    public void update()
    {
        if (worker.get() > 0) return;
        worker.set(1);

        updateSet.clear();
        updateList.clear();
        rootNode.removeAllChildren();
        rootNode.setUserObject("loading...");
        tree.updateUI();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Load project " + project.getName(), true)
        {
            @Override
            public void run(@NotNull ProgressIndicator indicator)
            {
                try
                {
                    var info = GradleHelper.getProjectInfo(project);
                    if (info != null)
                    {
                        addProject(rootNode, info, AppSettings.getInstance());
                        tree.updateUI();
                        worker.decrementAndGet();
                    }
                }
                catch (Exception e)
                {
                    catchError(rootNode, e);
                }
            }
        });
    }

    private void addProject(DefaultMutableTreeNode node, ProjectInfo info, AppSettings settings)
    {
        var dependency = new Dependency(info.name());
        node.setUserObject(dependency);

        if (info.buildFile().exists())
        {
            worker.incrementAndGet();
            dependency.setStatus("loading...");
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Retrieve " + info.name() + " dependencies", true)
            {
                @Override
                public void run(@NotNull ProgressIndicator indicator)
                {
                    try
                    {
                        var dependencies = GradleHelper.getDependencies(project, info.buildFile().getParentFile());
                        dependency.setStatus(null);
                        addDependencies(node, dependencies, settings);
                    }
                    catch (Exception e)
                    {
                        dependency.setStatus(null);
                        catchError(node, e);
                    }
                }
            });
        }

        for (var sub : info.children())
        {
            var child = new DefaultMutableTreeNode();
            node.add(child);
            addProject(child, sub, settings);
        }

        if (node.getChildCount() > 0)
        {
            tree.expandPath(new TreePath(node.getPath()));
        }
    }

    private void addDependencies(DefaultMutableTreeNode node, List<Dependency> dependencies, AppSettings settings)
    {
        var n = 0;
        for (var dependency : dependencies)
        {
            var child = new DefaultMutableTreeNode(dependency);
            node.insert(child, n++);

            if (dependency.hasGroup() && dependency.getVersion() != null)
            {
                checkForUpdate(dependency, child, settings);
            }
        }
        worker.decrementAndGet();
        tree.expandPath(new TreePath(node.getPath()));
    }

    private void checkForUpdate(Dependency dependency, DefaultMutableTreeNode node, AppSettings settings)
    {
        dependency.setStatus("check for updates...");
        updateList.add(dependency);
        if (updateSet.stream().anyMatch(e -> e.sameModule(dependency)))
        {
            updateList.stream()
                      .filter(e -> e.sameModule(dependency) && !e.equals(dependency) && e.getStatus() == null)
                      .findAny()
                      .ifPresent(e ->
                      {
                          dependency.setLatestVersion(e.getLatestVersion());
                          dependency.setStatus(null);
                      });
            return;
        }

        updateSet.add(dependency);
        worker.incrementAndGet();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Check for update", true)
        {
            @Override
            public void run(@NotNull ProgressIndicator indicator)
            {
                try
                {
                    MavenUtils.checkForUpdate(dependency, settings);
                    dependency.setStatus(null);
                    updateList.stream()
                              .filter(e -> e.sameModule(dependency) && !e.equals(dependency))
                              .forEach(e ->
                                       {
                                           e.setLatestVersion(dependency.getLatestVersion());
                                           e.setStatus(null);
                                       });
                    tree.updateUI();
                    worker.decrementAndGet();
                }
                catch (Exception e)
                {
                    dependency.setStatus(null);
                    catchError(node, e);
                }
            }
        });
    }

    private void catchError(DefaultMutableTreeNode node, Throwable tr)
    {
        worker.decrementAndGet();
        var rootCause = ExceptionUtils.getRootCause(tr);
        if (rootCause == null) rootCause = tr;

        if (node.equals(rootNode))
        {
            rootNode.setUserObject(rootCause);
            tree.updateUI();
        }
        else
        {
            var child = new DefaultMutableTreeNode(rootCause);
            node.insert(child, 0);
        }
        tree.expandPath(new TreePath(node.getPath()));

        LOG.error(tr);
    }
}
