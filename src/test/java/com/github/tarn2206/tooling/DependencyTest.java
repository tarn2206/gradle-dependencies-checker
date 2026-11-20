package com.github.tarn2206.tooling;

import java.util.List;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyTest {
    @Test
    public void displaysResolutionOnlyWhenDifferent() {
        var dependency = new Dependency("com.fasterxml.jackson.core", "jackson-core", "2.17.0");
        assertEquals("com.fasterxml.jackson.core:jackson-core:2.17.0", dependency.toString());
        dependency.setResolvedArtifacts(List.of(new ResolvedArtifact(dependency.getGroup(), dependency.getName(), "2.17.0")));
        assertEquals("com.fasterxml.jackson.core:jackson-core:2.17.0", dependency.toString());
        dependency.setResolvedArtifacts(List.of(new ResolvedArtifact(dependency.getGroup(), dependency.getName(), "2.17.2")));
        assertEquals("com.fasterxml.jackson.core:jackson-core:2.17.0 [2.17.2]", dependency.toString());
    }

    @Test
    public void displaysAllResolvedVersionsAndSubstitutedCoordinates() {
        var dependency = new Dependency("example", "library", "1.+");
        dependency.setResolvedArtifacts(List.of(new ResolvedArtifact("example", "library", "1.5"),
                                                new ResolvedArtifact("example", "library", "1.8")));
        assertEquals("example:library:1.+ [1.5, 1.8]", dependency.toString());
        dependency.setResolvedArtifacts(List.of(new ResolvedArtifact("replacement", "new-library", "2")));
        assertEquals("example:library:1.+ [replacement:new-library:2]", dependency.toString());
    }

    @Test
    public void updateChecksMustNotShareResultsAcrossDifferentResolutions() {
        var first = new Dependency("example", "library", "1");
        var second = new Dependency("example", "library", "1");
        assertTrue(first.sameUpdateCheck(second));
        first.setResolvedArtifacts(List.of(new ResolvedArtifact("example", "library", "2")));
        assertFalse(first.sameUpdateCheck(second));
        second.setResolvedArtifacts(first.getResolvedArtifacts());
        assertTrue(first.sameUpdateCheck(second));
    }
}
