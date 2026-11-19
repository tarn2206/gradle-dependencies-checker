package com.github.tarn2206.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import com.github.tarn2206.AppSettings;
import com.github.tarn2206.actions.RefreshAction;
import com.github.tarn2206.actions.SettingsAction;
import com.github.tarn2206.actions.SyncGradleAction;
import com.github.tarn2206.tooling.BuildSnapshot;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.GradleDependencyCache;
import com.github.tarn2206.tooling.GradleSyncState;
import com.github.tarn2206.tooling.MavenUtils;
import com.github.tarn2206.tooling.ProjectInfo;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;

public class DependenciesView extends SimpleToolWindowPanel {
    private static final Logger LOG = Logger.getInstance(DependenciesView.class);
    private final Project project;
    private final ToolWindow toolWindow;
    private final Alarm syncRefreshAlarm;
    private Tree tree;
    private boolean pendingUpdate;
    private DefaultMutableTreeNode rootNode;
    private final AtomicInteger worker = new AtomicInteger();
    // Only accessed on the EDT; background tasks receive individual dependencies.
    private final List<Dependency> updateList = new ArrayList<>();

    public DependenciesView(Project project, ToolWindow toolWindow) {
        super(true, true);
        this.project = project;
        this.toolWindow = toolWindow;
        syncRefreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, toolWindow.getDisposable());
    }

    public void init() {
        rootNode = new DefaultMutableTreeNode();
        tree = new Tree(rootNode);
        tree.setCellRenderer(new MyTreeCellRenderer());

        var treeExpander = new DefaultTreeExpander(tree);
        var commonActions = CommonActionsManager.getInstance();
        var actions = new DefaultActionGroup(new RefreshAction(this), new SyncGradleAction());
        actions.addSeparator();
        actions.add(commonActions.createExpandAllAction(treeExpander, tree));
        actions.add(commonActions.createCollapseAllAction(treeExpander, tree));
        actions.addSeparator();
        actions.add(new SettingsAction());
        var actionToolbar = ActionManager.getInstance().createActionToolbar("Dependency Updates Toolbar", actions, true);
        actionToolbar.setTargetComponent(this);
        setToolbar(actionToolbar.getComponent());
        setContent(ScrollPaneFactory.createScrollPane(tree));

        GradleDependencyCache.getInstance(project);
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(
                GradleDependencyCache.SYNC_COMPLETED,
                () -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (isDisposed()) return;
                    // Multiple linked builds can finish syncing in a burst. Their snapshots
                    // are invalidated immediately; defer only the expensive UI reload.
                    syncRefreshAlarm.cancelAllRequests();
                    syncRefreshAlarm.addRequest(this::loadCached, 500);
                }));
        loadCached();
    }

    private boolean isDisposed() {
        return project.isDisposed() || toolWindow.isDisposed();
    }

    private boolean isIdle() {
        return worker.get() == 0;
    }

    public boolean canCheckForUpdates() {
        return !isDisposed() && isIdle() && !GradleSyncState.isSyncing(project);
    }

    public void update() {
        if (isDisposed()) return;
        syncRefreshAlarm.cancelAllRequests();
        GradleDependencyCache.getInstance(project).invalidate();
        loadCached();
    }

    private void loadCached() {
        if (isDisposed()) return;
        if (!isIdle()) {
            pendingUpdate = true;
            return;
        }
        pendingUpdate = false;
        worker.set(1);

        updateList.clear();
        rootNode.removeAllChildren();
        rootNode.setUserObject("loading...");
        tree.updateUI();

        new Task.Backgroundable(project, "Load project " + project.getName(), true) {
            private BuildSnapshot snapshot;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                snapshot = GradleDependencyCache.getInstance(project).get();
            }

            @Override
            public void onSuccess() {
                if (isDisposed()) return;
                if (snapshot != null) {
                    addProject(rootNode, snapshot.projectInfo(), AppSettings.getInstance(), snapshot);
                } else {
                    rootNode.setUserObject("Cannot load project info");
                }
                tree.updateUI();

            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                catchError(rootNode, error);
            }

            @Override
            public void onFinished() {
                finishWorker();
            }
        }.queue();
    }

    private void addProject(DefaultMutableTreeNode node, ProjectInfo info, AppSettings settings, BuildSnapshot snapshot) {
        node.setUserObject(new Dependency(info.name()));
        for (var dependency : snapshot.dependencies(info)) {
            var child = new DefaultMutableTreeNode(dependency);
            node.add(child);
            if (dependency.hasGroup() && dependency.getVersion() != null) {
                checkForUpdate(dependency, child, settings);
            }
        }
        tree.expandPath(new TreePath(node.getPath()));
        tree.updateUI();

        for (var sub : info.children()) {
            var child = new DefaultMutableTreeNode();
            node.add(child);
            addProject(child, sub, settings, snapshot);
        }

        if (node.getChildCount() > 0) {
            tree.expandPath(new TreePath(node.getPath()));
        }
    }

    private void checkForUpdate(Dependency dependency, DefaultMutableTreeNode node, AppSettings settings) {
        dependency.setStatus("check for updates...");
        tree.updateUI();
        var alreadyChecking = updateList.stream().anyMatch(e -> e.sameModule(dependency));
        updateList.add(dependency);
        if (alreadyChecking) {
            updateList.stream()
                      .filter(e -> e != dependency && e.sameModule(dependency) && e.getStatus() == null)
                      .findAny()
                      .ifPresent(e -> {
                          dependency.setLatestVersion(e.getLatestVersion());
                          dependency.setStatus(null);
                          tree.updateUI();
                      });
            return;
        }

        worker.incrementAndGet();
        new Task.Backgroundable(project, "Check dependency updates", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                MavenUtils.checkForUpdate(dependency, settings);
            }

            @Override
            public void onSuccess() {
                if (isDisposed()) return;
                dependency.setStatus(null);
                updateList.stream()
                          .filter(e -> e != dependency && e.sameModule(dependency))
                          .forEach(e -> {
                              e.setLatestVersion(dependency.getLatestVersion());
                              e.setStatus(null);
                          });
                tree.updateUI();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                dependency.setStatus(null);
                catchError(node, error);
            }

            @Override
            public void onFinished() {
                finishWorker();
            }
        }.queue();
    }

    private void finishWorker() {
        if (worker.decrementAndGet() == 0 && pendingUpdate && !isDisposed()) loadCached();
    }

    private void catchError(DefaultMutableTreeNode node, Throwable tr) {
        if (isDisposed()) return;
        var rootCause = ExceptionUtils.getRootCause(tr);
        if (rootCause == null) rootCause = tr;

        if (node == rootNode) {
            rootNode.setUserObject(rootCause);
        } else {
            var child = new DefaultMutableTreeNode(rootCause);
            node.insert(child, 0);
        }
        tree.expandPath(new TreePath(node.getPath()));
        tree.updateUI();

        LOG.error(tr);
    }
}
