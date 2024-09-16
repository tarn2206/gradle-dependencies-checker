package com.github.tarn2206.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
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
import com.intellij.openapi.project.Project;
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

public class DependenciesView extends SimpleToolWindowPanel
{
    private static final Logger LOG = Logger.getInstance(DependenciesView.class);
    private final transient Project project;
    private Tree tree;
    private DefaultMutableTreeNode rootNode;
    private final AtomicInteger worker = new AtomicInteger();
    private transient CompositeDisposable disposables;
    private final Set<Dependency> updateSet = new HashSet<>();
    private final Queue<Dependency> updateList = new ConcurrentLinkedQueue<>();

    public DependenciesView(Project project, ToolWindow toolWindow)
    {
        super(true, true);

        setupToolWindow(toolWindow);

        this.project = project;
        run();
    }

    private void setupToolWindow(ToolWindow toolWindow)
    {
        toolWindow.setTitleActions(List.of(new RefreshAction(this), new SettingsAction()));

        var contentFactory = ApplicationManager.getApplication().getService(ContentFactory.class);
        var content = contentFactory.createContent(this, "", false);
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
        var disposable = fromCallable(() -> GradleHelper.getProjectInfo(project))
                .subscribe(info ->
                {
                    addProject(rootNode, info, AppSettings.getInstance());
                    tree.updateUI();
                    worker.decrementAndGet();
                }, tr -> onError(rootNode, tr));
        disposables.add(disposable);
    }

    private void addProject(DefaultMutableTreeNode node, ProjectInfo info, AppSettings settings)
    {
        var dependency = new Dependency(info.name());
        node.setUserObject(dependency);

        if (info.buildFile().exists())
        {
            worker.incrementAndGet();
            dependency.setStatus("loading...");
            var disposable = fromCallable(() ->
                GradleHelper.getDependencies(project, info.buildFile().getParentFile()))
                            .subscribe(dependencies ->
                            {
                                dependency.setStatus(null);
                                addDependencies(node, dependencies, settings);
                            }, tr ->
                            {
                                dependency.setStatus(null);
                                onError(node, tr);
                            });
            disposables.add(disposable);
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
        var disposable = fromCallable(() -> MavenUtils.checkForUpdate(dependency, settings))
                .subscribe(result ->
                {
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
                }, tr ->
                {
                    dependency.setStatus(null);
                    onError(node, tr);
                });
        disposables.add(disposable);
    }

    private void onError(DefaultMutableTreeNode node, Throwable tr)
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

    private static <T> Single<? extends T> fromCallable(Callable<? extends T> callable)
    {
        return Single.fromCallable(callable)
                     .subscribeOn(Schedulers.io())
                     .observeOn(SwingSchedulers.edt());
    }
}
