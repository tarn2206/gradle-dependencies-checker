package com.github.tarn2206.ui;

import com.github.tarn2206.AppSettings;
import com.github.tarn2206.actions.ApplyUpdateAction;
import com.github.tarn2206.actions.CollapseAllAction;
import com.github.tarn2206.actions.ExpandAllAction;
import com.github.tarn2206.actions.NavigateToSourceAction;
import com.github.tarn2206.actions.RefreshAction;
import com.github.tarn2206.actions.SettingsAction;
import com.github.tarn2206.actions.ShowOnlyUpgradableAction;
import com.github.tarn2206.actions.SortAction;
import com.github.tarn2206.actions.ViewVulnerabilitiesAction;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.DependencyStateService;
import com.github.tarn2206.tooling.GradleHelper;
import com.github.tarn2206.tooling.MavenUtils;
import com.github.tarn2206.tooling.OsvClient;
import com.github.tarn2206.tooling.ProjectInfo;
import com.github.tarn2206.tooling.VersionCatalog;
import com.github.tarn2206.tooling.Vulnerability;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class DependenciesView extends SimpleToolWindowPanel {
    private static final Logger LOG = Logger.getInstance(DependenciesView.class);
    private static final String POPUP_PLACE = "DependencyUpdatesTreePopup";
    private static final Pattern UNKNOWN_PLUGIN_ID = Pattern.compile("Plugin \\[id: '([^']+)'");

    /**
     * Serializes Gradle Tooling API calls. Even though Task.Backgroundable's queue in parallel
     * on IntelliJ's pool, only one can be inside GradleHelper at a time — prevents multiple
     * concurrent Gradle daemons for a single refresh.
     */
    private static final Semaphore GRADLE_PERMIT = new Semaphore(1);

    /**
     * Bounded pool for HTTP update checks. 4 concurrent Maven metadata fetches at most.
     */
    private static final ExecutorService UPDATE_CHECK_POOL =
            AppExecutorUtil.createBoundedApplicationPoolExecutor("dep-update-check", 4);
    private static final Comparator<Dependency> ALPHABETICAL_COMPARATOR =
            Comparator.comparing((Dependency d) -> d.getGroup() != null ? d.getGroup() : "")
                    .thenComparing(d -> d.getName() != null ? d.getName() : "");
    private static final Comparator<Dependency> BY_SEVERITY_COMPARATOR =
            Comparator.comparingInt(DependenciesView::severityRank)
                    .thenComparing(ALPHABETICAL_COMPARATOR);
    private final transient Project project;
    private final transient DependencyStateService stateService;
    private final AtomicInteger worker = new AtomicInteger();
    /**
     * Coordinates for which we've already submitted a check in this refresh cycle.
     */
    private final Set<String> checkFired = ConcurrentHashMap.newKeySet();
    /**
     * True while a refresh is in flight; a completeWork() that brings the worker count to 0 will trigger sort.
     */
    private final AtomicBoolean pendingFinalize = new AtomicBoolean(false);
    /**
     * Nodes removed from the tree by the "Show only upgradable" filter; kept so we can put them back.
     */
    private final List<HiddenNode> hiddenNodes = new ArrayList<>();
    /**
     * Expansion state captured before the last refresh, keyed by stable node names. Null on first refresh.
     */
    private @Nullable Set<List<String>> capturedExpanded = null;
    /**
     * All paths that existed before the last refresh. Lets us tell "was collapsed" apart from "is new".
     */
    private @Nullable Set<List<String>> capturedExisted = null;
    private Tree tree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private VersionCatalog catalog;
    public DependenciesView(Project project) {
        super(true, true);
        this.project = project;
        this.stateService = project.getService(DependencyStateService.class);
    }

    private static String moduleKey(Dependency d) {
        return d.getGroup() + ":" + d.getName();
    }

    private static void onEdt(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    private static int[] toIntArray(List<Integer> list) {
        var arr = new int[list.size()];
        for (var i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    private static String shortErrorText(Throwable tr) {
        var msg = tr.getMessage();
        if (msg == null || msg.isBlank()) return tr.getClass().getSimpleName();
        var firstLine = msg.split("\\R", 2)[0].trim();
        var cut = firstLine.indexOf(" Searched in");
        if (cut > 0) firstLine = firstLine.substring(0, cut).trim();
        if (firstLine.length() > 200) firstLine = firstLine.substring(0, 197) + "...";
        return firstLine;
    }

    private static List<String> pathToNames(TreePath path) {
        var names = new ArrayList<String>(path.getPathCount());
        for (var obj : path.getPath()) {
            names.add(nodeName((DefaultMutableTreeNode) obj));
        }
        return names;
    }

    private static List<String> pathToNames(DefaultMutableTreeNode node) {
        var names = new ArrayList<String>();
        for (var obj : node.getPath()) {
            names.add(nodeName((DefaultMutableTreeNode) obj));
        }
        return names;
    }

    /**
     * Stable per-node identifier that survives across refreshes (unlike the node reference itself).
     */
    private static String nodeName(DefaultMutableTreeNode node) {
        var obj = node.getUserObject();
        if (obj instanceof Dependency dep) {
            return dep.hasGroup() ? dep.getGroup() + ":" + dep.getName() : dep.getName();
        }
        if (obj instanceof String s) return s;
        return String.valueOf(obj);
    }

    /**
     * Real library dependencies (with group) come first, sorted by comparator. Sub-project name
     * nodes and the "Plugins" string node keep their declared positions (stable sort with 0).
     */
    private static int compareTreeNodes(DefaultMutableTreeNode a, DefaultMutableTreeNode b,
                                        Comparator<Dependency> comparator) {
        var oa = a.getUserObject();
        var ob = b.getUserObject();
        var libA = oa instanceof Dependency da && da.hasGroup() ? da : null;
        var libB = ob instanceof Dependency db && db.hasGroup() ? db : null;
        if (libA != null && libB != null) return comparator.compare(libA, libB);
        if (libA != null) return -1;
        if (libB != null) return 1;
        return 0;
    }

    /**
     * Lower rank sorts earlier. Critical/high vulns first, then updates, then errors, then clean.
     */
    private static int severityRank(Dependency dep) {
        var vulns = dep.getVulnerabilities();
        if (vulns != null && !vulns.isEmpty()) {
            var top = Vulnerability.Severity.UNKNOWN;
            for (var v : vulns) top = Vulnerability.Severity.max(top, v.severity());
            return switch (top) {
                case CRITICAL -> 0;
                case HIGH -> 1;
                case MODERATE -> 2;
                case LOW -> 3;
                case UNKNOWN -> 4;
            };
        }
        if (dep.hasMeaningfulUpdate()) return 5;
        if (dep.getError() != null) return 6;
        return 7;
    }

    public void initToolWindow(ToolWindow toolWindow) {
        toolWindow.setTitleActions(List.of(
                new RefreshAction(this),
                new ShowOnlyUpgradableAction(this),
                new ExpandAllAction(this),
                new CollapseAllAction(this),
                new SortAction(this),
                new SettingsAction(this)));

        var contentFactory = ApplicationManager.getApplication().getService(ContentFactory.class);
        var content = contentFactory.createContent(this, "", false);
        toolWindow.getContentManager().addContent(content);

        rootNode = new DefaultMutableTreeNode();
        treeModel = new DefaultTreeModel(rootNode);
        tree = new Tree(treeModel);
        tree.setCellRenderer(new MyTreeCellRenderer());
        installPopupMenu();
        installDoubleClickNavigation();
        setContent(new JBScrollPane(tree));

        project.getService(com.github.tarn2206.tooling.AutoRefreshHook.class).install(this::update);

        update();
    }

    private void installPopupMenu() {
        var group = new DefaultActionGroup();
        group.add(new NavigateToSourceAction(this));
        group.add(Separator.getInstance());
        group.add(new ApplyUpdateAction(this));
        group.add(new ViewVulnerabilitiesAction(this));
        PopupHandler.installPopupMenu(tree, group, POPUP_PLACE);
    }

    public boolean isIdle() {
        return worker.get() == 0;
    }

    public @Nullable Dependency getSelectedDependency() {
        var path = tree.getSelectionPath();
        if (path == null) return null;
        var node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof Dependency dep && dep.hasGroup()) {
            return dep;
        }
        return null;
    }

    /**
     * Wires a double-click handler that jumps to the dependency's declaration site.
     * Broader match than {@link #getSelectedDependency()} — module nodes navigate too.
     */
    private void installDoubleClickNavigation() {
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                var path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return false;
                var node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!(node.getUserObject() instanceof Dependency dep)) return false;
                return navigateToSource(dep);
            }
        }.installOn(tree);
    }

    /**
     * Open the editor at the declaration site of this dependency. Priority:
     * <ol>
     *   <li>Catalog entry with a known line number → {@code libs.versions.toml} at that line
     *       (works for library entries, version-ref entries, plugin entries, and shared version keys).</li>
     *   <li>Otherwise, {@code build.gradle} / {@code build.gradle.kts} with the coord literal:
     *       find the {@code group:name:version} substring and jump to it.</li>
     *   <li>For module nodes (no group), just open the module's build file.</li>
     * </ol>
     * Returns true if navigation succeeded, false if there was nothing to jump to.
     */
    public boolean navigateToSource(Dependency dep) {
        var catEntry = dep.getCatalogEntry();
        if (catEntry != null && catEntry.tomlFile() != null && catEntry.versionLineNumber() > 0) {
            var vFile = LocalFileSystem.getInstance().findFileByIoFile(catEntry.tomlFile());
            if (vFile != null) {
                var line = Math.max(0, catEntry.versionLineNumber() - 1);
                new OpenFileDescriptor(project, vFile, line, 0).navigate(true);
                return true;
            }
        }

        var buildFile = dep.getModuleBuildFile();
        if (buildFile == null) return false;
        var vFile = LocalFileSystem.getInstance().findFileByIoFile(buildFile);
        if (vFile == null) return false;

        if (dep.hasGroup() && dep.getVersion() != null) {
            var coord = dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion();
            var offset = findCoordOffset(vFile, coord);
            if (offset >= 0) {
                new OpenFileDescriptor(project, vFile, offset).navigate(true);
                return true;
            }
        }

        // Fallback: open the file at the top (module node, or coord not textually findable).
        new OpenFileDescriptor(project, vFile).navigate(true);
        return true;
    }

    private int findCoordOffset(VirtualFile file, String coord) {
        try {
            var doc = FileDocumentManager.getInstance().getDocument(file);
            if (doc != null) return doc.getText().indexOf(coord);
        } catch (Exception ignored) {
            // Fall through to -1
        }
        return -1;
    }

    public @Nullable VersionCatalog getCatalog() {
        return catalog;
    }

    public void update() {
        if (worker.get() > 0) return;
        // If IntelliJ is indexing (post-Gradle-sync phase) or otherwise dumb, defer. Calling
        // Gradle Tooling API before sync has fully wired up credentials/resolution can produce
        // spurious "Username must not be null" or UnknownPluginException errors that resolve
        // themselves once sync completes.
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            com.intellij.openapi.project.DumbService.getInstance(project).smartInvokeLater(this::update);
            return;
        }
        worker.set(1);
        pendingFinalize.set(true);

        // Any nodes hidden by the filter reference orphaned tree structure — drop them before rebuild.
        hiddenNodes.clear();

        // Capture expansion state before we clear the tree — used to restore what the user
        // had expanded/collapsed after the rebuild.
        captureCurrentExpansion();

        checkFired.clear();
        rootNode.removeAllChildren();
        rootNode.setUserObject("loading...");
        treeModel.nodeStructureChanged(rootNode);   // structural: root actually cleared

        stateService.clear();

        new Task.Backgroundable(project, "Load project " + project.getName(), true) {
            private ProjectInfo info;
            private VersionCatalog loadedCatalog;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                info = GradleHelper.getProjectInfo(project);
                loadedCatalog = VersionCatalog.loadForProject(project);
            }

            @Override
            public void onSuccess() {
                catalog = loadedCatalog;
                stateService.setCatalog(loadedCatalog);
                if (info != null) {
                    addProject(rootNode, info, AppSettings.getInstance());
                } else {
                    rootNode.setUserObject("Cannot load project info");
                }
                treeModel.nodeStructureChanged(rootNode);   // structural: populating the root
                completeWork();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                catchError(rootNode, error);
            }
        }.queue();
    }

    private void addProject(DefaultMutableTreeNode node, ProjectInfo info, AppSettings settings) {
        var dependency = new Dependency(info.name());
        dependency.setModuleBuildFile(info.buildFile());
        node.setUserObject(dependency);

        if (info.buildFile().exists()) {
            worker.incrementAndGet();
            dependency.setStatus("loading...");
            var moduleBuildFile = info.buildFile();
            new Task.Backgroundable(project, "Retrieve " + info.name() + " dependencies", true) {
                private List<Dependency> dependencies;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    // Serialize Gradle calls — one daemon-touching operation at a time.
                    try {
                        GRADLE_PERMIT.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    try {
                        indicator.setText("Reading " + info.name() + " dependencies");
                        dependencies = GradleHelper.getDependencies(project, moduleBuildFile.getParentFile());
                    } finally {
                        GRADLE_PERMIT.release();
                    }
                }

                @Override
                public void onSuccess() {
                    dependency.setStatus(null);
                    addDependencies(node, dependencies, settings, moduleBuildFile);
                }

                @Override
                public void onThrowable(@NotNull Throwable error) {
                    dependency.setStatus(null);
                    catchError(node, error);
                }
            }.queue();
        }

        for (var sub : info.children()) {
            var child = new DefaultMutableTreeNode();
            node.add(child);
            addProject(child, sub, settings);
        }

        if (node == rootNode && catalog != null && !catalog.getPlugins().isEmpty()) {
            addPlugins(node, catalog, settings);
        }

        if (node.getChildCount() > 0) {
            maybeExpand(node);
        }
    }

    private void addPlugins(DefaultMutableTreeNode moduleNode, VersionCatalog catalog, AppSettings settings) {
        var pluginsNode = new DefaultMutableTreeNode("Plugins");
        moduleNode.add(pluginsNode);

        var filterOn = settings.isShowOnlyUpgradable();
        var pluginDeps = new ArrayList<Dependency>();
        var insertedIndices = new ArrayList<Integer>();
        var visibleIdx = 0;

        for (var entry : catalog.getPlugins()) {
            var version = catalog.resolveVersion(entry);
            if (version == null) continue;

            var pluginDep = new Dependency(
                    entry.pluginId(),
                    entry.pluginId() + ".gradle.plugin",
                    version);
            pluginDep.setCatalogEntry(entry);
            pluginDeps.add(pluginDep);

            var pluginTreeNode = new DefaultMutableTreeNode(pluginDep);
            if (filterOn && !pluginDep.hasMeaningfulUpdate()) {
                // Don't add to tree yet — kept in hiddenNodes until its check reveals an update.
                hiddenNodes.add(new HiddenNode(pluginsNode, pluginTreeNode));
            } else {
                pluginsNode.add(pluginTreeNode);
                insertedIndices.add(visibleIdx++);
            }
            submitPluginUpdateCheck(pluginDep, pluginTreeNode, settings);
        }

        if (!insertedIndices.isEmpty()) {
            treeModel.nodesWereInserted(pluginsNode, toIntArray(insertedIndices));
        }
        maybeExpand(pluginsNode);

        stateService.upsertAll(pluginDeps);

        if (settings.isCheckVulnerabilities() && !pluginDeps.isEmpty()) {
            checkVulnerabilities(pluginsNode, pluginDeps);
        }
    }

    private void addDependencies(DefaultMutableTreeNode node, List<Dependency> dependencies,
                                 AppSettings settings, File moduleBuildFile) {
        for (var dep : dependencies) {
            if (!dep.hasGroup()) continue;
            dep.setModuleBuildFile(moduleBuildFile);
            if (catalog != null) {
                dep.setCatalogEntry(catalog.findByCoordinate(dep.getGroup(), dep.getName()));
            }
        }

        var filterOn = settings.isShowOnlyUpgradable();
        var insertedIndices = new ArrayList<Integer>();
        var initialChildCount = node.getChildCount();
        var visibleOffset = 0;
        for (var dependency : dependencies) {
            var child = new DefaultMutableTreeNode(dependency);
            if (filterOn && dependency.hasGroup() && !dependency.hasMeaningfulUpdate()) {
                // Don't insert — will appear later if the update check reveals it's upgradable.
                hiddenNodes.add(new HiddenNode(node, child));
            } else {
                node.insert(child, initialChildCount + visibleOffset);
                insertedIndices.add(initialChildCount + visibleOffset);
                visibleOffset++;
            }

            if (dependency.hasGroup() && dependency.getVersion() != null) {
                submitUpdateCheck(dependency, child, settings);
            }
        }
        if (!insertedIndices.isEmpty()) {
            // nodesWereInserted preserves expansion state of every other node; nodeStructureChanged would collapse.
            treeModel.nodesWereInserted(node, toIntArray(insertedIndices));
        }
        completeWork();
        maybeExpand(node);

        stateService.upsertAll(dependencies);

        if (settings.isCheckVulnerabilities()) {
            checkVulnerabilities(node, dependencies);
        }
    }

    private void checkVulnerabilities(DefaultMutableTreeNode moduleNode, List<Dependency> dependencies) {
        worker.incrementAndGet();
        new Task.Backgroundable(project, "Check for known vulnerabilities", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                OsvClient.enrichWithVulnerabilities(dependencies);
            }

            @Override
            public void onSuccess() {
                // Fine-grained repaint of each dep node whose vulns changed. No structure change.
                for (var i = 0; i < moduleNode.getChildCount(); i++) {
                    var child = (DefaultMutableTreeNode) moduleNode.getChildAt(i);
                    if (child.getUserObject() instanceof Dependency dep
                            && dep.getVulnerabilities() != null
                            && !dep.getVulnerabilities().isEmpty()) {
                        treeModel.nodeChanged(child);
                    }
                }
                stateService.notifyChange();
                completeWork();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                LOG.warn("OSV vulnerability check failed", error);
                completeWork();
            }
        }.queue();
    }

    /**
     * Submits an HTTP update check for a library dep. Uses a bounded pool instead of a
     * per-dep Task.Backgroundable — no task-storm, no progress-popup flicker.
     */
    private void submitUpdateCheck(Dependency dependency, DefaultMutableTreeNode node, AppSettings settings) {
        var coord = moduleKey(dependency);

        // First requester actually hits the network; duplicates receive their result via propagateResult.
        if (!checkFired.add(coord)) {
            dependency.setStatus("check for updates...");
            treeModel.nodeChanged(node);
            return;
        }

        dependency.setStatus("check for updates...");
        treeModel.nodeChanged(node);

        worker.incrementAndGet();
        UPDATE_CHECK_POOL.submit(() -> {
            try {
                MavenUtils.checkForUpdate(dependency, settings);
            } catch (Throwable t) {
                LOG.warn("Update check failed for " + dependency, t);
                dependency.setError(shortErrorText(t));
            } finally {
                onEdt(() -> {
                    dependency.setStatus(null);
                    treeModel.nodeChanged(node);
                    propagateResult(dependency);
                    stateService.notifyChange();
                    completeWork();
                });
            }
        });
    }

    /**
     * Same as submitUpdateCheck but uses the plugin-aware repo list (adds Gradle Plugin Portal).
     */
    private void submitPluginUpdateCheck(Dependency pluginDep, DefaultMutableTreeNode node, AppSettings settings) {
        var coord = moduleKey(pluginDep);
        if (!checkFired.add(coord)) {
            pluginDep.setStatus("check for updates...");
            treeModel.nodeChanged(node);
            return;
        }

        pluginDep.setStatus("check for updates...");
        treeModel.nodeChanged(node);

        worker.incrementAndGet();
        UPDATE_CHECK_POOL.submit(() -> {
            try {
                MavenUtils.checkForPluginUpdate(pluginDep, settings);
            } catch (Throwable t) {
                LOG.warn("Plugin update check failed for " + pluginDep, t);
                pluginDep.setError(shortErrorText(t));
            } finally {
                onEdt(() -> {
                    pluginDep.setStatus(null);
                    treeModel.nodeChanged(node);
                    propagateResult(pluginDep);
                    stateService.notifyChange();
                    completeWork();
                });
            }
        });
    }

    /**
     * After a check completes for one dep, propagate the result to any other tree nodes that share
     * the same {@code group:name}. Also propagates to nodes hidden by the "show only upgradable"
     * filter, and unhides any that just became upgradable (or the source itself, if it was hidden).
     */
    private void propagateResult(Dependency source) {
        var enumeration = rootNode.depthFirstEnumeration();
        while (enumeration.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) enumeration.nextElement();
            if (!(node.getUserObject() instanceof Dependency other)) continue;
            if (other == source) continue;
            if (!other.sameModule(source)) continue;
            // Only overwrite if this dep hasn't been checked itself
            if (other.getLatestVersion() != null || other.getError() != null) continue;

            other.setLatestVersion(source.getLatestVersion());
            other.setStatus(null);
            treeModel.nodeChanged(node);
        }

        // Same propagation for deps currently hidden by the filter. No nodeChanged — they're not visible.
        for (var hidden : hiddenNodes) {
            if (!(hidden.child().getUserObject() instanceof Dependency other)) continue;
            if (other == source) continue;
            if (!other.sameModule(source)) continue;
            if (other.getLatestVersion() != null || other.getError() != null) continue;

            other.setLatestVersion(source.getLatestVersion());
            other.setStatus(null);
        }

        // Anything in hiddenNodes that now qualifies (including source itself if it was hidden)
        // gets un-hidden.
        if (AppSettings.getInstance().isShowOnlyUpgradable()) {
            unhideNewlyUpgradable();
        }
    }

    /**
     * Move any hidden dep that now has an update available back into the tree.
     */
    private void unhideNewlyUpgradable() {
        if (hiddenNodes.isEmpty()) return;
        var byParent = new LinkedHashMap<DefaultMutableTreeNode, List<DefaultMutableTreeNode>>();
        var it = hiddenNodes.iterator();
        while (it.hasNext()) {
            var hidden = it.next();
            if (hidden.child().getUserObject() instanceof Dependency dep && dep.hasMeaningfulUpdate()) {
                it.remove();
                byParent.computeIfAbsent(hidden.parent(), k -> new ArrayList<>()).add(hidden.child());
            }
        }
        for (var entry : byParent.entrySet()) {
            var parent = entry.getKey();
            var children = entry.getValue();
            var wasEmpty = parent.getChildCount() == 0;
            var insertedIndices = new int[children.size()];
            for (var i = 0; i < children.size(); i++) {
                insertedIndices[i] = parent.getChildCount();
                parent.add(children.get(i));
            }
            treeModel.nodesWereInserted(parent, insertedIndices);
            // Parent may have been a leaf until now; expand it so the new deps are visible.
            if (wasEmpty) {
                tree.expandPath(new TreePath(parent.getPath()));
            }
        }
    }

    private void catchError(DefaultMutableTreeNode node, Throwable tr) {
        completeWork();

        if (tryMarkFailedPlugin(tr)) {
            LOG.warn("Attributed error to specific catalog plugin", tr);
            return;
        }

        var rootCause = ExceptionUtils.getRootCause(tr);
        if (rootCause == null) rootCause = tr;

        if (node == rootNode && rootNode.getUserObject() instanceof String) {
            rootNode.setUserObject(rootCause);
            treeModel.nodeChanged(rootNode);
        } else {
            var child = new DefaultMutableTreeNode(rootCause);
            node.insert(child, 0);
            treeModel.nodesWereInserted(node, new int[]{0});
        }
        tree.expandPath(new TreePath(node.getPath()));

        LOG.error(tr);
    }

    private boolean tryMarkFailedPlugin(Throwable original) {
        var visited = new HashSet<Throwable>();
        var current = original;
        while (current != null && visited.add(current)) {
            var msg = current.getMessage();
            if (msg != null) {
                var m = UNKNOWN_PLUGIN_ID.matcher(msg);
                if (m.find()) {
                    var pluginId = m.group(1);
                    var pluginNode = findPluginTreeNode(pluginId);
                    if (pluginNode != null && pluginNode.getUserObject() instanceof Dependency dep) {
                        dep.setError(shortErrorText(current));
                        treeModel.nodeChanged(pluginNode);
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private @Nullable DefaultMutableTreeNode findPluginTreeNode(String pluginId) {
        for (var i = 0; i < rootNode.getChildCount(); i++) {
            var child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (!"Plugins".equals(child.getUserObject())) continue;
            for (var j = 0; j < child.getChildCount(); j++) {
                var pluginNode = (DefaultMutableTreeNode) child.getChildAt(j);
                if (pluginNode.getUserObject() instanceof Dependency dep
                        && dep.getCatalogEntry() != null
                        && pluginId.equals(dep.getCatalogEntry().pluginId())) {
                    return pluginNode;
                }
            }
        }
        return null;
    }

    /**
     * Record which nodes exist and which are expanded, so we can restore expansion state
     * after {@link #update()} rebuilds the tree from scratch.
     */
    private void captureCurrentExpansion() {
        if (rootNode.getChildCount() == 0) {
            capturedExpanded = null;
            capturedExisted = null;
            return;
        }
        var expanded = new HashSet<List<String>>();
        var existed = new HashSet<List<String>>();
        collectPathsAndExpansion(rootNode, existed, expanded);
        capturedExpanded = expanded;
        capturedExisted = existed;
    }

    /**
     * Walks the whole tree recording each node's path and, if the node is expanded, adding it to the expanded set.
     */
    private void collectPathsAndExpansion(DefaultMutableTreeNode node,
                                          Set<List<String>> existed,
                                          Set<List<String>> expanded) {
        var pathNames = pathToNames(node);
        existed.add(pathNames);
        if (tree.isExpanded(new TreePath(node.getPath()))) {
            expanded.add(pathNames);
        }
        for (var i = 0; i < node.getChildCount(); i++) {
            collectPathsAndExpansion((DefaultMutableTreeNode) node.getChildAt(i), existed, expanded);
        }
    }

    /**
     * Restore this node's expansion state from what was captured before the refresh.
     * Explicitly collapses when it should be collapsed — the tree/UI can auto-expand newly-inserted
     * subtrees under some conditions, and we don't want that to fight the user's choice.
     */
    private void maybeExpand(DefaultMutableTreeNode node) {
        if (node.getChildCount() == 0) return;
        var pathNames = pathToNames(node);
        var path = new TreePath(node.getPath());
        boolean shouldExpand;
        if (capturedExpanded == null || capturedExisted == null || !capturedExisted.contains(pathNames)) {
            // First-ever refresh, or this node is new — default to expanded.
            shouldExpand = true;
        } else {
            // Node existed before; honor whatever the user had it set to.
            shouldExpand = capturedExpanded.contains(pathNames);
        }
        if (shouldExpand) {
            tree.expandPath(path);
        } else {
            tree.collapsePath(path);
        }
    }

    /**
     * Called instead of {@link AtomicInteger#decrementAndGet()} for every unit of work that
     * completes. If this brings the worker count to zero and a refresh is in flight, apply the
     * user's chosen sort. The pending-finalize flag ensures we sort exactly once per refresh.
     */
    private void completeWork() {
        var v = worker.decrementAndGet();
        if (v == 0 && pendingFinalize.compareAndSet(true, false)) {
            finalizeRefresh();
        }
    }

    /**
     * Post-refresh finalization: restore any nodes hidden by a mid-refresh filter toggle
     * (so sort operates on the full tree with final data), sort, then re-apply the filter.
     */
    private void finalizeRefresh() {
        restoreHiddenInternal();
        applyCurrentSort();
        if (AppSettings.getInstance().isShowOnlyUpgradable()) {
            applyUpgradableFilter();
        }
    }

    /**
     * Expand every row in the tree.
     */
    public void expandAll() {
        int row = 0;
        while (row < tree.getRowCount()) {
            tree.expandRow(row);
            row++;
        }
    }

    /**
     * Collapse every row except the root.
     */
    public void collapseAll() {
        for (var row = tree.getRowCount() - 1; row > 0; row--) {
            tree.collapseRow(row);
        }
    }

    /**
     * Turn the "show only upgradable" filter on or off. Applied to the current tree in place —
     * no Gradle re-read. Toggling off restores previously-hidden nodes from the stash.
     */
    public void setShowOnlyUpgradable(boolean enabled) {
        AppSettings.getInstance().setShowOnlyUpgradable(enabled);
        if (enabled) {
            applyUpgradableFilter();
        } else {
            restoreHiddenInternal();
            applyCurrentSort();
        }
    }

    /**
     * Remove any library dep (with group) that has no meaningful update. Empty modules stay in
     * place so tree structure is still visible. Kept nodes get pushed into {@link #hiddenNodes}
     * so we can restore them when the filter is turned off.
     */
    private void applyUpgradableFilter() {
        var toHide = new ArrayList<DefaultMutableTreeNode>();
        var enumeration = rootNode.depthFirstEnumeration();
        while (enumeration.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) enumeration.nextElement();
            if (node.getUserObject() instanceof Dependency dep
                    && dep.hasGroup()
                    && !dep.hasMeaningfulUpdate()) {
                toHide.add(node);
            }
        }
        for (var node : toHide) {
            var parent = (DefaultMutableTreeNode) node.getParent();
            if (parent == null) continue;
            var index = parent.getIndex(node);
            parent.remove(index);
            treeModel.nodesWereRemoved(parent, new int[]{index}, new Object[]{node});
            hiddenNodes.add(new HiddenNode(parent, node));
        }
    }

    /**
     * Put every stashed hidden node back into its original parent. Order is fixed by a subsequent sort.
     */
    private void restoreHiddenInternal() {
        if (hiddenNodes.isEmpty()) return;
        var byParent = new LinkedHashMap<DefaultMutableTreeNode, List<DefaultMutableTreeNode>>();
        for (var hidden : hiddenNodes) {
            byParent.computeIfAbsent(hidden.parent(), k -> new ArrayList<>()).add(hidden.child());
        }
        hiddenNodes.clear();
        for (var entry : byParent.entrySet()) {
            var parent = entry.getKey();
            var children = entry.getValue();
            var insertedIndices = new int[children.size()];
            for (var i = 0; i < children.size(); i++) {
                insertedIndices[i] = parent.getChildCount();
                parent.add(children.get(i));
            }
            treeModel.nodesWereInserted(parent, insertedIndices);
        }
    }

    /**
     * Re-order tree nodes according to the user's current sort selection. Called at refresh
     * completion (with final data) and whenever the user picks a different sort order.
     * Snapshots and restores expansion state around the reorder — sort's remove+insert can
     * otherwise reset the expansion of affected parent nodes.
     */
    public void applyCurrentSort() {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::applyCurrentSort);
            return;
        }

        // Snapshot current expansion before reordering.
        var snapshot = new HashSet<List<String>>();
        collectExpandedPaths(rootNode, snapshot);

        var order = AppSettings.getInstance().getSortOrder();
        if (order != AppSettings.SortOrder.DECLARATION_ORDER) {
            var comparator = order == AppSettings.SortOrder.BY_SEVERITY
                    ? BY_SEVERITY_COMPARATOR
                    : ALPHABETICAL_COMPARATOR;
            sortSubtree(rootNode, comparator);
        }

        // Re-expand every path that was expanded before sort. expandPath is idempotent for
        // paths already expanded; nodes that weren't in the snapshot are left alone.
        restoreExpansion(rootNode, snapshot);
    }

    private void collectExpandedPaths(DefaultMutableTreeNode node, Set<List<String>> expanded) {
        if (tree.isExpanded(new TreePath(node.getPath()))) {
            expanded.add(pathToNames(node));
        }
        for (var i = 0; i < node.getChildCount(); i++) {
            collectExpandedPaths((DefaultMutableTreeNode) node.getChildAt(i), expanded);
        }
    }

    private void restoreExpansion(DefaultMutableTreeNode node, Set<List<String>> expanded) {
        if (expanded.contains(pathToNames(node))) {
            tree.expandPath(new TreePath(node.getPath()));
        }
        for (var i = 0; i < node.getChildCount(); i++) {
            restoreExpansion((DefaultMutableTreeNode) node.getChildAt(i), expanded);
        }
    }

    private void sortSubtree(DefaultMutableTreeNode node, Comparator<Dependency> comparator) {
        var childCount = node.getChildCount();
        if (childCount > 1) {
            var current = new ArrayList<DefaultMutableTreeNode>(childCount);
            for (var i = 0; i < childCount; i++) {
                current.add((DefaultMutableTreeNode) node.getChildAt(i));
            }
            var sorted = new ArrayList<>(current);
            sorted.sort((a, b) -> compareTreeNodes(a, b, comparator));

            if (!current.equals(sorted)) {
                var indices = new int[childCount];
                for (var i = 0; i < childCount; i++) indices[i] = i;
                var removed = current.toArray(new DefaultMutableTreeNode[0]);

                node.removeAllChildren();
                treeModel.nodesWereRemoved(node, indices, removed);
                for (var child : sorted) {
                    node.add(child);
                }
                treeModel.nodesWereInserted(node, indices);
            }
        }
        // Recurse for nested project nodes and the Plugins node.
        for (var i = 0; i < node.getChildCount(); i++) {
            sortSubtree((DefaultMutableTreeNode) node.getChildAt(i), comparator);
        }
    }

    private record HiddenNode(DefaultMutableTreeNode parent, DefaultMutableTreeNode child) {
    }
}