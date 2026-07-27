package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.DependencyStateService;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;

public class ApplyInlineUpdateQuickFix implements LocalQuickFix {
    private static final String NOTIFICATION_GROUP = "Dependency Updates";

    private final String oldVersion;
    private final String newVersion;

    public ApplyInlineUpdateQuickFix(String oldVersion, String newVersion) {
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
    }

    @Override
    public @NotNull String getName() {
        return "Update to " + newVersion;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Update Gradle dependency";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        var element = descriptor.getPsiElement();
        var literal = element instanceof GrLiteral gr ? gr : PsiTreeUtil.getParentOfType(element, GrLiteral.class);
        if (literal == null) return;

        if (GradleDslHelpers.replaceVersionInLiteral(project, literal, oldVersion, newVersion)) {
            invalidateStaleState(project, literal);
            Notifications.Bus.notify(
                    new Notification(NOTIFICATION_GROUP, "Dependency Update",
                            "Updated to " + newVersion, NotificationType.INFORMATION),
                    project);
        }
    }

    /** Clear latestVersion + vulnerabilities on the state's dep so editor decorations stop firing until refresh. */
    private static void invalidateStaleState(Project project, GrLiteral literal) {
        if (!(literal.getValue() instanceof String s)) return;
        var coord = GradleDslHelpers.parseCoordinate(s);
        if (coord == null) return;
        var state = project.getService(DependencyStateService.class);
        var dep = state.byCoordinate(coord.group(), coord.name());
        if (dep != null) {
            dep.setLatestVersion(null);
            dep.setVulnerabilities(null);
        }
        state.notifyChange();
    }
}