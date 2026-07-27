package com.github.tarn2206.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;

import java.util.regex.Pattern;

/**
 * Shared utilities for Groovy DSL (build.gradle) integration.
 */
@UtilityClass
public final class GradleDslHelpers {
    private static final Pattern COORD_PART = Pattern.compile("[\\w.\\-+]+");

    /**
     * True for {@code build.gradle}, {@code settings.gradle}, and any other .gradle file. Excludes .kts.
     */
    public static boolean isGradleFile(@Nullable PsiFile file) {
        if (file == null) return false;
        var name = file.getName();
        return name.endsWith(".gradle") && !name.endsWith(".gradle.kts");
    }

    /**
     * Parses a {@code group:name:version} string. Returns null for anything that doesn't look like a
     * Maven coordinate — including strings with spaces, wrong part counts, or empty parts.
     */
    public static @Nullable Coordinate parseCoordinate(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        var parts = s.split(":");
        if (parts.length != 3) return null;
        for (var p : parts) {
            if (p.isBlank() || !COORD_PART.matcher(p).matches()) return null;
        }
        return new Coordinate(parts[0], parts[1], parts[2]);
    }

    /**
     * Range covering just the version substring within a Groovy string literal, relative to the literal's start.
     * <p>
     * The literal's text includes quotes: {@code 'group:name:1.2.3'} — length 20, version at index 12.
     * {@code lastIndexOf} correctly finds the version since it appears last and only once for well-formed coords.
     */
    public static @Nullable TextRange versionRangeInLiteral(GrLiteral literal, Coordinate coord) {
        var text = literal.getText();
        var idx = text.lastIndexOf(coord.version());
        if (idx < 0) return null;
        return new TextRange(idx, idx + coord.version().length());
    }

    /**
     * Replaces just the version substring inside a Groovy string literal. Preserves quote style
     * automatically because we only touch the version bytes. Single write-command, single undo.
     */
    public static boolean replaceVersionInLiteral(Project project, GrLiteral literal,
                                                  String oldVersion, String newVersion) {
        var range = versionRangeInLiteral(literal, new Coordinate("", "", oldVersion));
        if (range == null) return false;

        var literalStart = literal.getTextRange().getStartOffset();
        var start = literalStart + range.getStartOffset();
        var end = literalStart + range.getEndOffset();

        var file = literal.getContainingFile();
        var vFile = file.getVirtualFile();
        if (vFile == null) return false;
        Document doc = FileDocumentManager.getInstance().getDocument(vFile);
        if (doc == null) return false;

        WriteCommandAction.runWriteCommandAction(project, "Apply Dependency Update", null, () -> {
            doc.replaceString(start, end, newVersion);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
        return true;
    }

    public record Coordinate(String group, String name, String version) {
    }
}