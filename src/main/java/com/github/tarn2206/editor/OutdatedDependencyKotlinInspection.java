package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.DependencyStateService;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

public class OutdatedDependencyKotlinInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!KotlinDslHelpers.isKotlinDslFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        var state = holder.getProject().getService(DependencyStateService.class);

        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof KtStringTemplateExpression tpl)) return;

                var value = KotlinDslHelpers.plainStringValue(tpl);
                if (value == null) return; // interpolation / escapes — skip

                var coord = GradleDslHelpers.parseCoordinate(value);
                if (coord == null) return;

                var dep = state.byCoordinate(coord.group(), coord.name());
                if (dep == null || !dep.hasMeaningfulUpdate()) return;

                // Guard against stale state: only highlight if the version we know matches what's typed.
                if (!coord.version().equals(dep.getVersion())) return;

                var range = KotlinDslHelpers.versionRangeInTemplate(tpl, coord.version());
                if (range == null) return;

                holder.registerProblem(
                        tpl,
                        "Newer version available: " + coord.version() + " → " + dep.getLatestVersion(),
                        ProblemHighlightType.WEAK_WARNING,
                        range,
                        new ApplyInlineKotlinUpdateQuickFix(coord.version(), dep.getLatestVersion())
                );
            }
        };
    }
}
