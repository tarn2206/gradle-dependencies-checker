package com.github.tarn2206.tooling;

import java.util.function.Supplier;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.util.GradleConstants;

@Service(Service.Level.PROJECT)
public final class GradleDependencyCache implements Disposable {
    public static final Topic<Runnable> SYNC_COMPLETED = Topic.create("Dependency updates Gradle sync", Runnable.class);
    private final Supplier<BuildSnapshot> loader;
    // Serializes loads without blocking invalidate() on the separate state lock (this).
    private final Object loadLock = new Object();
    // Guarded by this.
    private long generation;
    private BuildSnapshot snapshot;

    public GradleDependencyCache(Project project) {
        this(() -> GradleHelper.loadBuild(project));
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(
                new ExternalSystemTaskNotificationListener() {
                    @Override
                    public void onSuccess(@NotNull String projectPath, @NotNull ExternalSystemTaskId id) {
                        if (id.getType() == ExternalSystemTaskType.RESOLVE_PROJECT
                            && GradleConstants.SYSTEM_ID.equals(id.getProjectSystemId())
                            && project.equals(id.findProject()) && !project.isDisposed()) {
                            invalidate();
                            project.getMessageBus().syncPublisher(SYNC_COMPLETED).run();
                        }
                    }
                }, this);
    }

    // Allows cache behavior to be tested without starting IntelliJ or Gradle.
    GradleDependencyCache(Supplier<BuildSnapshot> loader) {
        this.loader = loader;
    }

    public static GradleDependencyCache getInstance(Project project) {
        return project.getService(GradleDependencyCache.class);
    }

    public BuildSnapshot get() {
        synchronized (loadLock) {
            while (true) {
                long loadGeneration;
                synchronized (this) {
                    if (snapshot != null) return snapshot;
                    loadGeneration = generation;
                }
                var loaded = loader.get(); // Failures are deliberately not cached.
                synchronized (this) {
                    if (generation == loadGeneration) {
                        snapshot = loaded;
                        return loaded;
                    }
                }
            }
        }
    }

    public synchronized void invalidate() {
        generation++;
        snapshot = null;
    }

    @Override
    public void dispose() {
        invalidate();
    }
}
