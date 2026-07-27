package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.DependencyStateService;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;

public class OutdatedDependencyGroovyInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!GradleDslHelpers.isGradleFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        var state = holder.getProject().getService(DependencyStateService.class);

        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof GrLiteral literal)) return;

                // GrLiteral.getValue() returns null / non-String for interpolated GStrings — natural filter.
                if (!(literal.getValue() instanceof String s)) return;
                var coord = GradleDslHelpers.parseCoordinate(s);
                if (coord == null) return;

                var dep = state.byCoordinate(coord.group(), coord.name());
                if (dep == null || !dep.hasMeaningfulUpdate()) return;

                // Guard against stale state: only highlight if the version we know matches what's typed.
                if (!coord.version().equals(dep.getVersion())) return;

                var range = GradleDslHelpers.versionRangeInLiteral(literal, coord);
                if (range == null) return;

                holder.registerProblem(
                        literal,
                        "Newer version available: " + coord.version() + " → " + dep.getLatestVersion(),
                        ProblemHighlightType.WEAK_WARNING,
                        range,
                        new ApplyInlineUpdateQuickFix(coord.version(), dep.getLatestVersion())
                );
            }
        };
    }
}
