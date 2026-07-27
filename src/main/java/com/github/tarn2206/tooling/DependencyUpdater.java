package com.github.tarn2206.tooling;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class DependencyUpdater {
    private static final Logger LOG = Logger.getInstance(DependencyUpdater.class);

    private DependencyUpdater() {}

    public enum Status { UPDATED, NOT_FOUND, CANCELLED, FAILED }

    public record Result(Status status, String message, @Nullable VirtualFile file, int line) {
        public static Result updated(String msg) { return new Result(Status.UPDATED, msg, null, 0); }
        public static Result notFound(String msg, VirtualFile file, int line) { return new Result(Status.NOT_FOUND, msg, file, line); }
        public static Result cancelled() { return new Result(Status.CANCELLED, "Cancelled", null, 0); }
        public static Result failed(String msg) { return new Result(Status.FAILED, msg, null, 0); }
    }

    public static Result apply(Project project, Dependency dep, @Nullable VersionCatalog catalog) {
        var newVersion = dep.getLatestVersion();
        if (newVersion == null) return Result.failed("No update available");
        if (dep.getVersion() == null) return Result.failed("Current version unknown");

        Result result;
        var entry = dep.getCatalogEntry();
        if (entry != null && entry.hasEditableVersion()) {
            result = applyCatalog(project, dep, entry, catalog, newVersion);
        } else {
            result = applyBuildFile(project, dep, newVersion);
        }

        if (result.status() == Status.UPDATED) {
            // Invalidate cached data for the pre-update version. Editor decorations go quiet
            // immediately; the next refresh (~2s via auto-refresh) will re-check against the
            // new version and repopulate with accurate state.
            dep.setLatestVersion(null);
            dep.setVulnerabilities(null);
        }

        return result;
    }

    private static Result applyCatalog(Project project, Dependency dep, CatalogEntry entry,
                                       @Nullable VersionCatalog catalog, String newVersion) {
        var kindLabel = entry.kind() == CatalogEntry.Kind.PLUGIN ? "plugin" : "library";
        String oldVersion;
        String editLabel;

        if (entry.versionRef() != null) {
            if (catalog == null) return Result.failed("Catalog not loaded");
            oldVersion = catalog.versionFor(entry.versionRef());
            editLabel = "version key '" + entry.versionRef() + "'";

            // Shared version key confirmation — list BOTH libraries and plugins that would move.
            var affectedLibs = catalog.librariesUsingVersionKey(entry.versionRef());
            var affectedPlugins = catalog.pluginsUsingVersionKey(entry.versionRef());
            var total = affectedLibs.size() + affectedPlugins.size();
            if (total > 1) {
                var lines = new ArrayList<String>();
                affectedLibs.forEach(k -> lines.add("library: " + k));
                affectedPlugins.forEach(k -> lines.add("plugin:  " + k));
                var msg = "Version key '" + entry.versionRef() + "' is used by "
                        + total + " entries:\n\n  "
                        + String.join("\n  ", lines)
                        + "\n\nUpdate all of them to " + newVersion + "?";
                var choice = Messages.showYesNoDialog(project, msg, "Apply Dependency Update", Messages.getQuestionIcon());
                if (choice != Messages.YES) return Result.cancelled();
            }
        } else {
            oldVersion = entry.inlineVersion();
            editLabel = kindLabel + " '" + entry.key() + "'";
        }

        if (oldVersion == null || oldVersion.isBlank()) {
            return Result.failed("Couldn't determine current version in catalog for " + entry.key());
        }

        return editVersionInFile(project, entry.tomlFile(), entry.versionLineNumber(),
                oldVersion, newVersion,
                "Updated " + editLabel + " to " + newVersion);
    }

    private static Result applyBuildFile(Project project, Dependency dep, String newVersion) {
        var buildFile = dep.getModuleBuildFile();
        if (buildFile == null) {
            return Result.failed("Module build file unknown for " + dep.getGroup() + ":" + dep.getName());
        }

        var vFile = LocalFileSystem.getInstance().findFileByIoFile(buildFile);
        if (vFile == null) return Result.failed("Build file not accessible: " + buildFile);

        var doc = FileDocumentManager.getInstance().getDocument(vFile);
        if (doc == null) return Result.failed("Cannot open document for " + buildFile);

        var oldCoord = dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion();
        var newCoord = dep.getGroup() + ":" + dep.getName() + ":" + newVersion;

        var text = doc.getText();
        var idx = text.indexOf(oldCoord);
        if (idx < 0) {
            return Result.notFound(
                    "Couldn't find literal '" + oldCoord + "' in " + buildFile.getName()
                            + ". Version may be defined via a variable or gradle.properties.",
                    vFile, findClosestLine(doc, dep.getGroup() + ":" + dep.getName()));
        }

        WriteCommandAction.runWriteCommandAction(project, "Apply Dependency Update", null, () -> {
            doc.replaceString(idx, idx + oldCoord.length(), newCoord);
            FileDocumentManager.getInstance().saveDocument(doc);
        });

        return Result.updated("Updated " + dep.getGroup() + ":" + dep.getName() + " to " + newVersion);
    }

    private static Result editVersionInFile(Project project, File file, int lineNumber,
                                            String oldVersion, String newVersion, String successMessage) {
        var vFile = LocalFileSystem.getInstance().findFileByIoFile(file);
        if (vFile == null) return Result.failed("File not accessible: " + file);
        var doc = FileDocumentManager.getInstance().getDocument(vFile);
        if (doc == null) return Result.failed("Cannot open document for " + file);

        var replaced = new AtomicBoolean(false);
        WriteCommandAction.runWriteCommandAction(project, "Apply Dependency Update", null, () -> {
            if (tryReplaceOnLine(doc, lineNumber, oldVersion, newVersion)
                    || tryReplaceQuoted(doc, oldVersion, newVersion)) {
                FileDocumentManager.getInstance().saveDocument(doc);
                replaced.set(true);
            }
        });

        return replaced.get()
                ? Result.updated(successMessage)
                : Result.notFound("Couldn't locate version '" + oldVersion + "' in " + file.getName(),
                                  vFile, Math.max(lineNumber, 1));
    }

    private static boolean tryReplaceOnLine(Document doc, int line1based, String oldVersion, String newVersion) {
        if (line1based < 1 || line1based > doc.getLineCount()) return false;
        var line = line1based - 1;
        var start = doc.getLineStartOffset(line);
        var end = doc.getLineEndOffset(line);
        var lineText = doc.getCharsSequence().subSequence(start, end).toString();
        var idx = lineText.indexOf(oldVersion);
        if (idx < 0) return false;
        doc.replaceString(start + idx, start + idx + oldVersion.length(), newVersion);
        return true;
    }

    private static boolean tryReplaceQuoted(Document doc, String oldVersion, String newVersion) {
        var quoted = Pattern.compile("([\"'])" + Pattern.quote(oldVersion) + "\\1");
        var m = quoted.matcher(doc.getText());
        if (!m.find()) return false;
        var idx = m.start() + 1;
        doc.replaceString(idx, idx + oldVersion.length(), newVersion);
        return true;
    }

    private static int findClosestLine(Document doc, String needle) {
        var idx = doc.getText().indexOf(needle);
        return idx < 0 ? 1 : doc.getLineNumber(idx) + 1;
    }
}