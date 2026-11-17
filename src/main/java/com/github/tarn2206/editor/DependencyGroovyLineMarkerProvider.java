package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.DependencyStateService;
import com.github.tarn2206.tooling.Vulnerability;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;

import javax.swing.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DependencyGroovyLineMarkerProvider extends LineMarkerProviderDescriptor {

    private static Vulnerability.Severity highestSeverity(List<Vulnerability> vulns) {
        var top = Vulnerability.Severity.UNKNOWN;
        for (var v : vulns) {
            top = Vulnerability.Severity.max(top, v.severity());
        }
        return top;
    }

    private static String buildVulnTooltip(Dependency dep, List<Vulnerability> vulns, boolean hasUpdate) {
        var top = highestSeverity(vulns);
        var sb = new StringBuilder("<html>");
        sb.append("<b>").append(vulns.size())
                .append(vulns.size() == 1 ? " known vulnerability" : " known vulnerabilities")
                .append("</b> (").append(top.name()).append(")<br>");
        var maxShown = Math.min(vulns.size(), 5);
        for (var i = 0; i < maxShown; i++) {
            var v = vulns.get(i);
            sb.append("• ").append(v.id()).append(" — ").append(v.severity().name());
            if (v.fixedVersion() != null) sb.append(" (fixed in ").append(v.fixedVersion()).append(")");
            sb.append("<br>");
        }
        if (vulns.size() > maxShown) sb.append("… and ").append(vulns.size() - maxShown).append(" more<br>");
        sb.append("<br><i>Click to ");
        sb.append(hasUpdate ? "update to " + dep.getLatestVersion() : "open OSV.dev");
        sb.append("</i></html>");
        return sb.toString();
    }

    @Override
    public @Nullable String getName() {
        return "Gradle dependency updates & vulnerabilities";
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof LeafPsiElement)) return null;

        var file = element.getContainingFile();
        if (!GradleDslHelpers.isGradleFile(file)) return null;

        var literal = PsiTreeUtil.getParentOfType(element, GrLiteral.class);
        if (literal == null) return null;

        // Only attach the marker on the first leaf of the literal — avoids duplicates.
        var firstLeaf = PsiTreeUtil.getDeepestFirst(literal);
        if (firstLeaf != element) return null;

        if (!(literal.getValue() instanceof String s)) return null;
        var coord = GradleDslHelpers.parseCoordinate(s);
        if (coord == null) return null;

        var state = element.getProject().getService(DependencyStateService.class);
        var dep = state.byCoordinate(coord.group(), coord.name());
        if (dep == null) return null;
        if (!coord.version().equals(dep.getVersion())) return null;

        var hasUpdate = dep.hasMeaningfulUpdate();
        var vulns = dep.getVulnerabilities();
        var hasVulns = vulns != null && !vulns.isEmpty();
        if (!hasUpdate && !hasVulns) return null;

        return buildMarker(element, dep, hasUpdate, vulns);
    }

    private LineMarkerInfo<?> buildMarker(PsiElement anchor, Dependency dep,
                                          boolean hasUpdate, @Nullable List<Vulnerability> vulns) {
        Icon icon;
        String tooltip;

        if (vulns != null && !vulns.isEmpty()) {
            var top = highestSeverity(vulns);
            icon = (top == Vulnerability.Severity.CRITICAL || top == Vulnerability.Severity.HIGH)
                    ? AllIcons.General.Error
                    : AllIcons.General.Warning;
            tooltip = buildVulnTooltip(dep, vulns, hasUpdate);
        } else {
            icon = AllIcons.Actions.Download;
            tooltip = "Update available: " + dep.getVersion() + " → " + dep.getLatestVersion();
        }

        return new LineMarkerInfo<>(
                anchor,
                anchor.getTextRange(),
                icon,
                elt -> tooltip,
                (mouseEvent, elt) -> handleClick(elt, dep, hasUpdate),
                GutterIconRenderer.Alignment.LEFT,
                () -> "Dependency status"
        );
    }

    private void handleClick(PsiElement element, Dependency dep, boolean hasUpdate) {
        var project = element.getProject();
        var literal = PsiTreeUtil.getParentOfType(element, GrLiteral.class);

        if (hasUpdate && literal != null && dep.getLatestVersion() != null) {
            if (GradleDslHelpers.replaceVersionInLiteral(project, literal, dep.getVersion(), dep.getLatestVersion())) {
                var state = project.getService(DependencyStateService.class);
                dep.setLatestVersion(null);
                dep.setVulnerabilities(null);
                state.notifyChange();
            }
        } else {
            var coord = URLEncoder.encode(dep.getGroup() + ":" + dep.getName(), StandardCharsets.UTF_8);
            BrowserUtil.browse("https://osv.dev/list?q=" + coord + "&ecosystem=Maven");
        }
    }
}
