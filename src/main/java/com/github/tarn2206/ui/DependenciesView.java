package com.github.tarn2206.ui;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.GradleHelper;
import com.github.tarn2206.tooling.MavenUtils;
import com.github.tarn2206.tooling.ProjectInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import hu.akarnokd.rxjava3.swing.SwingSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;

public class DependenciesView extends SimpleToolWindowPanel
{
    private final String projectPath;
    private Tree tree;
    private DefaultMutableTreeNode rootNode;
    private final AtomicInteger worker = new AtomicInteger();
    private transient CompositeDisposable disposables;
    private final Set<Dependency> updateSet = new HashSet<>();
    private final Queue<Dependency> updateList = new ConcurrentLinkedQueue<>();

    public DependenciesView(@NotNull Project project, @NotNull ToolWindow toolWindow)
    {
        super(true, true);

        setupToolWindow(toolWindow);

        projectPath = project.getBasePath();
        StartupManager.getInstance(project).runWhenProjectIsInitialized(this::run);
    }

    private void setupToolWindow(ToolWindow toolWindow)
    {
        var refresh = new RefreshAction(this);
        toolWindow.setTitleActions(Collections.singletonList(refresh));

        var content = ContentFactory.SERVICE.getInstance().createContent(this, "", false);
        toolWindow.getContentManager().addContent(content);
        rootNode = new DefaultMutableTreeNode();
        tree = new Tree(rootNode);
        tree.setCellRenderer(new MyTreeCellRenderer());
        setContent(new JBScrollPane(tree));
    }

    public boolean isIdle()
    {
        if (worker.get() == 0 && disposables != null)
        {
            disposables.dispose();
        }
        return worker.get() == 0;
    }

    public void run()
    {
        if (worker.get() > 0) return;
        worker.set(1);

        updateSet.clear();
        updateList.clear();
        rootNode.removeAllChildren();
        rootNode.setUserObject("loading...");
        tree.updateUI();
        if (disposables != null) disposables.dispose();
        disposables = new CompositeDisposable();
        var disposable = fromCallable(() -> GradleHelper.getProjectInfo(projectPath))
                .subscribe(p ->
                {
                    addProject(rootNode, p);
                    tree.updateUI();
                    worker.decrementAndGet();
                }, tr -> onError(rootNode, tr));
        disposables.add(disposable);
    }

    private void addProject(DefaultMutableTreeNode node, ProjectInfo p)
    {
        var dependency = new Dependency(p.name);
        node.setUserObject(dependency);

        if (p.buildFile.exists())
        {
            worker.incrementAndGet();
            dependency.status = "loading...";
            var disposable = fromCallable(() -> GradleHelper.getDependencies(projectPath, p.buildFile.getParentFile()))
                    .subscribe(dependencies ->
                               {
                                   dependency.status = null;
                                   addDependencies(node, dependencies);
                               }, tr ->
                               {
                                   dependency.status = null;
                                   onError(node, tr);
                               });
            disposables.add(disposable);
        }

        for (var sub : p.children)
        {
            var child = new DefaultMutableTreeNode();
            node.add(child);
            addProject(child, sub);
        }

        if (node.getChildCount() > 0)
        {
            tree.expandPath(new TreePath(node.getPath()));
        }
    }

    private void addDependencies(DefaultMutableTreeNode node, List<Dependency> dependencies)
    {
        int n = 0;
        for (var dependency : dependencies)
        {
            var child = new DefaultMutableTreeNode(dependency);
            node.insert(child, n++);

            if (dependency.hasGroup() && dependency.version != null)
            {
                checkForUpdate(dependency, child);
            }
        }
        worker.decrementAndGet();
        tree.expandPath(new TreePath(node.getPath()));
    }

    private void checkForUpdate(Dependency dependency, DefaultMutableTreeNode node)
    {
        dependency.status = "check for updates...";
        updateList.add(dependency);
        if (updateSet.stream().anyMatch(e -> e.sameModule(dependency)))
        {
            updateList.stream()
                      .filter(e -> e.sameModule(dependency) && !e.equals(dependency) && e.status == null)
                      .findAny()
                      .ifPresent(e ->
                      {
                          dependency.latestVersion = e.latestVersion;
                          dependency.status = null;
                      });
            return;
        }

        updateSet.add(dependency);
        worker.incrementAndGet();
        var disposable = fromCallable(() -> MavenUtils.checkForUpdate(dependency))
                .subscribe(result ->
                {
                    dependency.status = null;
                    updateList.stream()
                              .filter(e -> e.sameModule(dependency) && !e.equals(dependency))
                              .forEach(e ->
                              {
                                  e.latestVersion = dependency.latestVersion;
                                  e.status = null;
                              });
                    tree.updateUI();
                    worker.decrementAndGet();
                }, tr ->
                {
                    dependency.status = null;
                    onError(node, tr);
                });
        disposables.add(disposable);
    }

    private void onError(DefaultMutableTreeNode node, Throwable tr)
    {
        worker.decrementAndGet();
        tr.printStackTrace();
        var rootCause = ExceptionUtils.getRootCause(tr);
        if (rootCause == null) rootCause = tr;

        var child = new DefaultMutableTreeNode(rootCause);
        node.insert(child, 0);
    }

    private static <T> Single<? extends T> fromCallable(Callable<? extends T> callable)
    {
        return Single.fromCallable(callable)
                     .subscribeOn(Schedulers.io())
                     .observeOn(SwingSchedulers.edt());
    }
}
