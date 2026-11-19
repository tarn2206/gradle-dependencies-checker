package com.github.tarn2206.tooling;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import com.google.gson.Gson;
import com.intellij.openapi.util.SystemInfoRt;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BuildSnapshotTest {
    @Test
    public void preservesTreeAndIsolatesUiChanges() {
        var directory = new File("build/snapshot-fixture").getAbsoluteFile();
        var entry = new BuildSnapshot.DependencyEntry("example", "library", "1", "resolution error");
        var snapshot = new BuildSnapshot(new BuildSnapshot.ModuleEntry[] {
                module(directory, ":child:nested", ":child", List.of(entry)),
                module(directory, ":", null, List.of(entry, entry)),
                module(directory, ":child", ":", List.of())
        });
        var root = snapshot.projectInfo();
        check(root.children().size() == 1, "root subtree");
        var child = root.children().getFirst();
        check(child.children().size() == 1, "nested subtree");
        check(snapshot.dependencies(child).isEmpty(), "empty module retained");
        var first = snapshot.dependencies(root);
        check(first.size() == 1, "coordinates deduplicated");
        first.getFirst().setLatestVersion("2");
        first.getFirst().setStatus("checking");
        first.getFirst().setError("UI error");
        first.clear();
        var second = snapshot.dependencies(root);
        check(second.size() == 1 && second.getFirst().getLatestVersion() == null
              && second.getFirst().getStatus() == null
              && "resolution error".equals(second.getFirst().getError()), "cached snapshot is isolated from UI mutation");
        check(snapshot.dependencies(child.children().getFirst()).size() == 1, "nested dependencies retained");
        System.out.println("PASS: subtree reconstruction, empty modules, deduplication and immutable cached data");
    }

    @Test
    public void copiesInputsAndKeepsFirstModuleDependency() {
        var directory = new File("build/snapshot-fixture").getAbsoluteFile();
        var entries = new ArrayList<>(List.of(
                new BuildSnapshot.DependencyEntry("example", "library", "1", "first"),
                new BuildSnapshot.DependencyEntry("example", "library", "2", "second")));
        var modules = new BuildSnapshot.ModuleEntry[] { module(directory, ":", null, entries) };
        var snapshot = new BuildSnapshot(modules);
        entries.clear();
        modules[0] = null;
        var dependencies = snapshot.dependencies(snapshot.projectInfo());
        assertEquals(1, dependencies.size());
        assertEquals("1", dependencies.getFirst().getVersion());
        assertEquals("first", dependencies.getFirst().getError());
    }

    @Test
    public void matchesBuildFilesUsingPlatformCaseSensitivity() {
        var directory = new File("build/snapshot-fixture").getAbsoluteFile();
        var snapshot = new BuildSnapshot(new BuildSnapshot.ModuleEntry[] {
                module(directory, ":", null, List.of())
        });
        var root = snapshot.projectInfo();
        var alternateCase = new ProjectInfo(root.name(),
                                            new File(root.buildFile().getParentFile(), "BUILD.GRADLE"), List.of());
        if (SystemInfoRt.isFileSystemCaseSensitive) {
            assertThrows(NoSuchElementException.class, () -> snapshot.dependencies(alternateCase));
        } else {
            assertTrue(snapshot.dependencies(alternateCase).isEmpty());
        }
    }

    @Test
    public void deserializesImmutableModuleEntries() {
        var json = """
                   [{"path":":","name":"root","buildFile":"build.gradle","dependencies":[]}]
                   """;
        var modules = new Gson().fromJson(json, BuildSnapshot.ModuleEntry[].class);
        var snapshot = new BuildSnapshot(modules);
        assertTrue(snapshot.dependencies(snapshot.projectInfo()).isEmpty());
        var dependencies = modules[0].dependencies();
        var entry = new BuildSnapshot.DependencyEntry("example", "library", "1", null);
        assertThrows(UnsupportedOperationException.class, () -> dependencies.add(entry));
    }

    private static BuildSnapshot.ModuleEntry module(File base, String path, String parent,
                                                    List<BuildSnapshot.DependencyEntry> dependencies) {
        var directory = new File(base, path.replace(':', '/')).getAbsoluteFile();
        return new BuildSnapshot.ModuleEntry(path, parent, path, directory.getPath(),
                                             new File(directory, "build.gradle").getPath(), dependencies);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
