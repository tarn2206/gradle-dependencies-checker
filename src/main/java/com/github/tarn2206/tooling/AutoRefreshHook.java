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
    private volatile @Nullable Runnable refresh;

    public AutoRefreshHook(Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    /** Called by the tool window on first open. Idempotent: subsequent calls just update the refresh target. */
    public void install(Runnable refresh) {
        this.refresh = refresh;
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
        alarm.cancelAllRequests();
        alarm.addRequest(() -> {
            var target = refresh;
            if (target == null || project.isDisposed()) return;
            LOG.info("Auto-refresh: " + reason);
            target.run();
        }, DEBOUNCE_MS);
    }

    @Override
    public void dispose() {}
}