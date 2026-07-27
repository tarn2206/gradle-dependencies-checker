package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.DependencyStateService;
import com.github.tarn2206.tooling.Vulnerability;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

import java.util.ArrayList;
import java.util.List;

public class VulnerableDependencyKotlinInspection extends LocalInspectionTool {

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
                if (value == null) return;

                var coord = GradleDslHelpers.parseCoordinate(value);
                if (coord == null) return;

                var dep = state.byCoordinate(coord.group(), coord.name());
                if (dep == null) return;
                if (!coord.version().equals(dep.getVersion())) return;

                var vulns = dep.getVulnerabilities();
                if (vulns == null || vulns.isEmpty()) return;

                var range = KotlinDslHelpers.versionRangeInTemplate(tpl, coord.version());
                if (range == null) return;

                var topSeverity = highestSeverity(vulns);

                var fixes = new ArrayList<LocalQuickFix>();
                if (dep.hasMeaningfulUpdate()) {
                    fixes.add(new ApplyInlineKotlinUpdateQuickFix(coord.version(), dep.getLatestVersion()));
                }
                fixes.add(new OpenVulnerabilityReportQuickFix(coord.group(), coord.name()));

                var description = vulns.size()
                        + (vulns.size() == 1 ? " known vulnerability" : " known vulnerabilities")
                        + " (" + topSeverity.name() + ")";

                holder.registerProblem(
                        tpl,
                        description,
                        highlightTypeFor(topSeverity),
                        range,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }
        };
    }

    private static Vulnerability.Severity highestSeverity(List<Vulnerability> vulns) {
        var top = Vulnerability.Severity.UNKNOWN;
        for (var v : vulns) {
            top = Vulnerability.Severity.max(top, v.severity());
        }
        return top;
    }

    private static ProblemHighlightType highlightTypeFor(Vulnerability.Severity s) {
        return switch (s) {
            case CRITICAL, HIGH -> ProblemHighlightType.GENERIC_ERROR;
            case MODERATE -> ProblemHighlightType.WARNING;
            case LOW, UNKNOWN -> ProblemHighlightType.WEAK_WARNING;
        };
    }
}
