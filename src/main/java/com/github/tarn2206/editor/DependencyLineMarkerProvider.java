package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.CatalogEntry;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.DependencyStateService;
import com.github.tarn2206.tooling.DependencyUpdater;
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
import org.toml.lang.psi.TomlKey;
import org.toml.lang.psi.TomlKeySegment;
import org.toml.lang.psi.TomlKeyValue;

import javax.swing.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gutter icons on TOML catalog entries. Marker semantics:
 * <ul>
 *   <li>Library/plugin entry with inline version and update available → download icon</li>
 *   <li>Library/plugin entry with known vulnerabilities → warning/error icon</li>
 *   <li>{@code [versions]} entry whose key is used by an updatable dep → download icon</li>
 *   <li>Library/plugin with {@code version.ref} — no update marker (handled on the [versions] entry)
 *       but vulnerability marker still applies since CVEs are per-artifact</li>
 *   <li>BOM-managed entry (no version in catalog) — no update marker; vuln marker if vulns exist</li>
 * </ul>
 */
public class DependencyLineMarkerProvider extends LineMarkerProviderDescriptor {

    @Override
    public @Nullable String getName() {
        return "Gradle dependency updates & vulnerabilities";
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof LeafPsiElement)) return null;

        var file = element.getContainingFile();
        if (!CatalogPsiHelpers.isCatalogFile(file)) return null;

        var segment = PsiTreeUtil.getParentOfType(element, TomlKeySegment.class);
        if (segment == null) return null;

        var key = PsiTreeUtil.getParentOfType(segment, TomlKey.class);
        if (key == null) return null;

        // Only mark on the FIRST segment of the key (not sub-parts like ".ref").
        var segments = key.getSegments();
        if (segments.isEmpty() || segments.get(0) != segment) return null;

        var keyValue = PsiTreeUtil.getParentOfType(key, TomlKeyValue.class);
        if (keyValue == null) return null;

        var tableName = CatalogPsiHelpers.containingTableName(keyValue);
        if (tableName == null) return null;

        var entryKey = CatalogPsiHelpers.entryKey(keyValue);
        if (entryKey == null) return null;

        var state = element.getProject().getService(DependencyStateService.class);

        return switch (tableName) {
            case CatalogPsiHelpers.LIBRARIES ->
                    buildForDirect(element, state.byCatalogKey(entryKey), CatalogEntry.Kind.LIBRARY);
            case CatalogPsiHelpers.PLUGINS ->
                    buildForDirect(element, state.byPluginKey(entryKey), CatalogEntry.Kind.PLUGIN);
            case CatalogPsiHelpers.VERSIONS ->
                    buildForVersionKey(element, entryKey, state);
            default -> null;
        };
    }

    private LineMarkerInfo<?> buildForDirect(PsiElement anchor, Dependency dep, CatalogEntry.Kind kind) {
        if (dep == null) return null;

        // Show the update marker whenever the entry has any editable version — either its own
        // inline literal OR a version.ref pointing at a [versions] entry. The click routes through
        // DependencyUpdater which edits the correct spot (and prompts on shared refs). BOM-managed
        // entries with no version at all still get no update marker.
        var catEntry = dep.getCatalogEntry();
        var hasUpdate = dep.hasMeaningfulUpdate()
                && catEntry != null
                && catEntry.hasEditableVersion();

        var vulns = dep.getVulnerabilities();
        var hasVulns = vulns != null && !vulns.isEmpty();

        if (!hasUpdate && !hasVulns) return null;

        return buildMarker(anchor, dep, kind, hasUpdate, vulns);
    }

    private LineMarkerInfo<?> buildForVersionKey(PsiElement anchor, String versionKey, DependencyStateService state) {
        var dep = state.anyUpdatableForVersionKey(versionKey);
        if (dep == null) return null;
        var catEntry = dep.getCatalogEntry();
        if (catEntry == null) return null;
        // Version key markers show update only — CVEs stay on the individual library entries.
        return buildMarker(anchor, dep, catEntry.kind(), true, null);
    }

    private LineMarkerInfo<?> buildMarker(PsiElement anchor, Dependency dep, CatalogEntry.Kind kind,
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
                (mouseEvent, elt) -> handleClick(elt, dep, kind, hasUpdate, vulns != null && !vulns.isEmpty()),
                GutterIconRenderer.Alignment.LEFT,
                () -> "Dependency status"
        );
    }

    private void handleClick(PsiElement element, Dependency dep, CatalogEntry.Kind kind,
                             boolean hasUpdate, boolean hasVulns) {
        var project = element.getProject();
        if (hasUpdate) {
            var state = project.getService(DependencyStateService.class);
            DependencyUpdater.apply(project, dep, state.getCatalog());
            dep.setLatestVersion(null);
            state.notifyChange();
        } else if (hasVulns) {
            var coord = URLEncoder.encode(dep.getGroup() + ":" + dep.getName(), StandardCharsets.UTF_8);
            BrowserUtil.browse("https://osv.dev/list?q=" + coord + "&ecosystem=Maven");
        }
    }

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
}