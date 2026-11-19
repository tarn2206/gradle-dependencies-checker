package com.github.tarn2206.tooling;

import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskState;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GradleSyncStateTest {
    @Test
    public void blocksQueuedRunningAndCancelingSyncsButReleasesTerminalStates() {
        var id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID,
                                             ExternalSystemTaskType.RESOLVE_PROJECT, "project");
        for (var state : new ExternalSystemTaskState[] {
                ExternalSystemTaskState.NOT_STARTED, ExternalSystemTaskState.IN_PROGRESS,
                ExternalSystemTaskState.CANCELING }) {
            assertTrue(state.name(), GradleSyncState.isActiveSync(id, state, "project"));
        }
        for (var state : new ExternalSystemTaskState[] {
                ExternalSystemTaskState.FINISHED, ExternalSystemTaskState.FAILED,
                ExternalSystemTaskState.CANCELED }) {
            assertFalse(state.name(), GradleSyncState.isActiveSync(id, state, "project"));
        }
    }

    @Test
    public void ignoresOtherProjectsSystemsAndOrdinaryGradleBuilds() {
        var sync = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID,
                                               ExternalSystemTaskType.RESOLVE_PROJECT, "other");
        var build = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID,
                                                ExternalSystemTaskType.EXECUTE_TASK, "project");
        var otherSystem = ExternalSystemTaskId.create(new ProjectSystemId("OTHER"),
                                                      ExternalSystemTaskType.RESOLVE_PROJECT, "project");
        for (var id : new ExternalSystemTaskId[] { sync, build, otherSystem }) {
            assertFalse(GradleSyncState.isActiveSync(id, ExternalSystemTaskState.IN_PROGRESS, "project"));
        }
    }
}
