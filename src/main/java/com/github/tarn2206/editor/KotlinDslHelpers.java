package com.github.tarn2206.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

/**
 * Kotlin PSI utilities for build.gradle.kts. Mirrors {@link GradleDslHelpers} for Groovy —
 * coordinate parsing is shared (see {@code GradleDslHelpers.parseCoordinate}).
 */
public final class KotlinDslHelpers {

    private KotlinDslHelpers() {}

    /** Only fire on Kotlin DSL Gradle scripts (build.gradle.kts, settings.gradle.kts). */
    public static boolean isKotlinDslFile(@Nullable PsiFile file) {
        return file != null && file.getName().endsWith(".gradle.kts");
    }

    /**
     * Concatenates a Kotlin string template's static text. Returns null if any entry has
     * interpolation ({@code $var}, {@code ${expr}}) or escape sequences — those can't be
     * resolved at PSI time, so we don't try to highlight them.
     */
    public static @Nullable String plainStringValue(KtStringTemplateExpression tpl) {
        var sb = new StringBuilder();
        for (var entry : tpl.getEntries()) {
            if (!(entry instanceof KtLiteralStringTemplateEntry)) return null;
            sb.append(entry.getText());
        }
        return sb.toString();
    }

    /**
     * Range covering the version substring within the template's text (which includes the outer
     * quotes). {@code lastIndexOf} skips the opening quote and finds the version at the end.
     */
    public static @Nullable TextRange versionRangeInTemplate(KtStringTemplateExpression tpl, String version) {
        var text = tpl.getText();
        var idx = text.lastIndexOf(version);
        if (idx < 0) return null;
        return new TextRange(idx, idx + version.length());
    }

    /** Replaces just the version substring inside the Kotlin string template. Single undo. */
    public static boolean replaceVersionInTemplate(Project project, KtStringTemplateExpression tpl,
                                                   String oldVersion, String newVersion) {
        var text = tpl.getText();
        var idx = text.lastIndexOf(oldVersion);
        if (idx < 0) return false;

        var tplStart = tpl.getTextRange().getStartOffset();
        var start = tplStart + idx;
        var end = start + oldVersion.length();

        var file = tpl.getContainingFile();
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
}