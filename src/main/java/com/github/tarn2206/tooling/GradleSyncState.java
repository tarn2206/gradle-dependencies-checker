package com.github.tarn2206.tooling;

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskState;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.gradle.util.GradleConstants;

/**
 * Reads IDE-owned tasks, including syncs started before our tool window was opened.
 */
public final class GradleSyncState {
    private GradleSyncState() { }

    public static boolean isSyncing(Project project) {
        var projectId = ExternalSystemTaskId.getProjectId(project);
        return ExternalSystemProcessingManager.getInstance()
                                              .findTasksOfState(GradleConstants.SYSTEM_ID, ExternalSystemTaskState.values())
                                              .stream()
                                              .anyMatch(task -> isActiveSync(task.getId(), task.getState(), projectId));
    }

    static boolean isActiveSync(ExternalSystemTaskId id, ExternalSystemTaskState state, String projectId) {
        return GradleConstants.SYSTEM_ID.equals(id.getProjectSystemId())
               && id.getType() == ExternalSystemTaskType.RESOLVE_PROJECT
               && projectId.equals(id.getIdeProjectId())
               && !state.isStopped();
    }
}
