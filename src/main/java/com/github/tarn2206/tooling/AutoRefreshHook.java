package com.github.tarn2206.tooling;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project-level hook that triggers a debounced refresh of the Dependency Updates view
 * whenever Gradle finishes a sync or a version catalog file changes on disk.
 * <p>
 * Only registers listeners once per project (idempotent {@link #install(Runnable)}).
 * The setting {@code AppSettings.autoRefresh} is checked at trigger time so toggling
 * the setting takes effect without re-registration.
 */
@Service(Service.Level.PROJECT)
public final class AutoRefreshHook implements Disposable {
    private static final Logger LOG = Logger.getInstance(AutoRefreshHook.class);
    private static final int DEBOUNCE_MS = 2_000;

    private final Project project;
    private final Alarm alarm;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    /**
     * Set by an editor-driven apply (badge click, quick-fix) when the {@code autoRefreshAfterApply}
     * setting is off. Cleared by the next VFS-triggered {@link #schedule(String)} call. The one-
     * shot semantics are important — we only want to skip the VFS event caused by our own apply,
     * not the next external edit the user makes.
     */
    private final AtomicBoolean suppressNextVfs = new AtomicBoolean(false);
    private volatile @Nullable Runnable refresh;
    /**
     * Lightweight cleanup that runs after every editor-driven apply — regardless of the
     * {@code autoRefreshAfterApply} setting. Currently used by the tool window to re-hide rows
     * that stopped being upgradable when their siblings' {@code latestVersion} got cleared during
     * the apply. Independent of the full refresh path so we can keep the tree consistent even
     * when the Gradle re-sync is suppressed.
     */
    private volatile @Nullable Runnable postEditorApply;

    public AutoRefreshHook(Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    /**
     * Called by an editor-driven apply before it modifies the catalog. Honors the
     * {@code autoRefreshAfterApply} setting — if that setting is on (default), this is a no-op and
     * the imminent VFS event will still schedule a refresh. If it's off, the next VFS-triggered
     * schedule is suppressed, so the user can click multiple badges back-to-back without waiting
     * for each one to Gradle-sync before proceeding.
     */
    public void suppressNextIfConfigured() {
        if (AppSettings.getInstance().isAutoRefreshAfterApply()) return;
        suppressNextVfs.set(true);
    }

    /**
     * Called after an editor-driven apply completes. Runs the tool window's post-apply hook
     * (currently: rebalance the "show only upgradable" filter so newly-non-upgradable rows
     * disappear from the visible tree). Fires regardless of the auto-refresh setting — it's a
     * pure in-memory tree walk, not a Gradle round-trip.
     */
    public void onEditorApplyCompleted() {
        var cb = postEditorApply;
        if (cb == null) return;
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(cb);
    }

    /**
     * Called by the tool window on first open. Idempotent: subsequent calls just update the refresh
     * target and the post-apply hook.
     *
     * @param refresh         full tool-window refresh (Gradle re-read). Runs on VFS/Gradle-sync triggers.
     * @param postEditorApply lightweight cleanup after editor-driven applies. See
     *                        {@link #onEditorApplyCompleted()}.
     */
    public void install(Runnable refresh, Runnable postEditorApply) {
        this.refresh = refresh;
        this.postEditorApply = postEditorApply;
        if (!installed.compareAndSet(false, true)) return;

        var connection = project.getMessageBus().connect(this);

        connection.subscribe(ProjectDataImportListener.TOPIC, new ProjectDataImportListener() {
            @Override
            public void onImportFinished(@Nullable String projectPath) {
                schedule("Gradle sync completed");
            }

            @Override
            public void onImportFailed(@Nullable String projectPath) {
                // Gradle failed to configure — no point re-running dependencies.
            }
        });

        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (var event : events) {
                    var file = event.getFile();
                    if (file != null && file.getName().endsWith(".versions.toml")) {
                        schedule("Catalog file changed");
                        return;
                    }
                }
            }
        });

        LOG.info("Auto-refresh hooks installed for " + project.getName());
    }

    private void schedule(String reason) {
        if (!AppSettings.getInstance().isAutoRefresh()) return;
        // Only VFS-triggered schedules can come from our own apply; Gradle-sync schedules always run.
        if ("Catalog file changed".equals(reason) && suppressNextVfs.compareAndSet(true, false)) {
            LOG.info("Auto-refresh suppressed: apply from editor, autoRefreshAfterApply=false");
            return;
        }
        alarm.cancelAllRequests();
        alarm.addRequest(() -> {
            var target = refresh;
            if (target == null || project.isDisposed()) return;
            LOG.info("Auto-refresh: " + reason);
            target.run();
        }, DEBOUNCE_MS);
    }

    @Override
    public void dispose() {
    }
}