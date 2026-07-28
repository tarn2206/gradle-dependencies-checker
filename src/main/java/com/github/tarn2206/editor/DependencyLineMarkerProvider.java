package com.github.tarn2206.editor;

import com.github.tarn2206.tooling.CatalogEntry;
import com.github.tarn2206.tooling.Dependency;
import com.github.tarn2206.tooling.DependencyStateService;
import com.github.tarn2206.tooling.DependencyUpdater;
import com.github.tarn2206.tooling.VersionCatalog;
import com.github.tarn2206.tooling.Vulnerability;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
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
 *   <li>Library/plugin entry with editable version and update available → download icon,
 *       click bumps this entry's version (or its version.ref target).</li>
 *   <li>Library/plugin entry with known vulnerabilities → warning/error icon.</li>
 *   <li>{@code [versions]} entry whose key is used by an updatable dep → download icon.</li>
 *   <li>BOM-managed library entry (no version in the TOML) whose managing BOM has an update →
 *       download icon, click routes through the BOM (shows the shared-version-key dialog if the
 *       BOM's key is shared, otherwise bumps silently).</li>
 *   <li>BOM-managed entry when the plugin can't uniquely identify a managing BOM →
 *       no update marker (vuln marker still applies).</li>
 * </ul>
 */
public class DependencyLineMarkerProvider extends LineMarkerProviderDescriptor {

    private static final Logger LOG = Logger.getInstance(DependencyLineMarkerProvider.class);

    private static Vulnerability.Severity highestSeverity(List<Vulnerability> vulns) {
        var top = Vulnerability.Severity.UNKNOWN;
        for (var v : vulns) {
            top = Vulnerability.Severity.max(top, v.severity());
        }
        return top;
    }

    @Override
    public @Nullable String getName() {
        return "Gradle dependency updates & vulnerabilities";
    }

    private static String buildUpdateTooltip(Dependency sourceDep,
                                             @Nullable Dependency updateDep,
                                             @Nullable CatalogEntry updateVia) {
        if (updateDep == null) return "";
        if (updateDep == sourceDep) {
            return "Update available: " + sourceDep.getVersion() + " → " + updateDep.getLatestVersion();
        }
        return "<html>Update available for <b>" + sourceDep.getGroup() + ":" + sourceDep.getName()
                + "</b><br>Managed by BOM <code>" + updateVia.key() + "</code>."
                + "<br><br><i>Click to bump the BOM to " + updateDep.getLatestVersion() + ".</i></html>";
    }

    private static String buildVulnTooltip(Dependency sourceDep, List<Vulnerability> vulns,
                                           boolean hasUpdate,
                                           @Nullable Dependency updateDep,
                                           @Nullable CatalogEntry updateVia) {
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
        if (hasUpdate && updateDep != null) {
            if (updateDep == sourceDep) {
                sb.append("update to ").append(updateDep.getLatestVersion());
            } else {
                sb.append("bump BOM ").append(updateVia.key())
                        .append(" to ").append(updateDep.getLatestVersion());
            }
        } else {
            sb.append("open OSV.dev");
        }
        sb.append("</i></html>");
        return sb.toString();
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

        var project = element.getProject();
        var state = project.getService(DependencyStateService.class);
        var catalog = state.getCatalog();

        return switch (tableName) {
            case CatalogPsiHelpers.LIBRARIES -> buildForLibraryLine(element, entryKey, catalog, state);
            case CatalogPsiHelpers.PLUGINS -> buildForPluginLine(element, entryKey, catalog, state);
            case CatalogPsiHelpers.VERSIONS -> buildForVersionKey(element, entryKey, state);
            default -> null;
        };
    }

    /**
     * A library entry — could be inline-versioned, version.ref, or BOM-managed. The specific entry
     * on THIS line is looked up by key; a single Dependency may be shared across multiple aliases,
     * so {@code dep.getCatalogEntry()} isn't reliable for this-line semantics.
     */
    private @Nullable LineMarkerInfo<?> buildForLibraryLine(PsiElement anchor, String entryKey,
                                                            @Nullable VersionCatalog catalog,
                                                            DependencyStateService state) {
        var dep = state.byCatalogKey(entryKey);
        if (dep == null) return null;

        var lineEntry = catalog == null ? null : catalog.findEntryByKey(entryKey);
        return buildForCatalogEntry(anchor, dep, lineEntry, catalog, state);
    }

    private @Nullable LineMarkerInfo<?> buildForPluginLine(PsiElement anchor, String entryKey,
                                                           @Nullable VersionCatalog catalog,
                                                           DependencyStateService state) {
        var dep = state.byPluginKey(entryKey);
        if (dep == null) return null;

        var lineEntry = catalog == null ? null : catalog.findEntryByKey(entryKey);
        return buildForCatalogEntry(anchor, dep, lineEntry, catalog, state);
    }

    private @Nullable LineMarkerInfo<?> buildForCatalogEntry(PsiElement anchor, Dependency sourceDep,
                                                             @Nullable CatalogEntry lineEntry,
                                                             @Nullable VersionCatalog catalog,
                                                             DependencyStateService state) {
        // Determine what a click should update, if anything.
        Dependency updateDep = null;
        CatalogEntry updateVia = null;

        if (lineEntry != null && lineEntry.hasEditableVersion()) {
            // Regular case: this line owns its version — update it directly.
            if (sourceDep.hasMeaningfulUpdate()) {
                updateDep = sourceDep;
                updateVia = lineEntry;
            }
        } else if (lineEntry != null && catalog != null) {
            // BOM-managed: no version literal on this line. Route through the managing BOM if we
            // can pin one down. If not, we leave update handling off — vuln badge still stands.
            var bomEntry = catalog.findManagingBom(sourceDep.getGroup(), sourceDep.getName());
            if (bomEntry != null) {
                var bomDep = state.byCatalogKey(bomEntry.key());
                if (bomDep != null && bomDep.hasMeaningfulUpdate()) {
                    updateDep = bomDep;
                    updateVia = bomEntry;
                } else {
                    LOG.warn("BOM-managed " + sourceDep.getGroup() + ":" + sourceDep.getName()
                            + " → BOM '" + bomEntry.key() + "' resolved, but "
                            + (bomDep == null ? "no Dependency in state for that BOM key"
                            : "BOM has no meaningful update (latestVersion="
                            + bomDep.getLatestVersion() + ")"));
                }
            } else {
                LOG.warn("BOM-managed " + sourceDep.getGroup() + ":" + sourceDep.getName()
                        + " → findManagingBom returned null. See earlier log lines from VersionCatalog for the reason.");
            }
        }

        var vulns = sourceDep.getVulnerabilities();
        var hasVulns = vulns != null && !vulns.isEmpty();
        if (updateDep == null && !hasVulns) return null;

        return buildMarker(anchor, sourceDep, updateDep, updateVia, hasVulns ? vulns : null);
    }

    /**
     * A {@code [versions]} entry — highlight if any library or plugin using this key has an update.
     * Click routes through one of those deps to bump the version literal on this line.
     */
    private @Nullable LineMarkerInfo<?> buildForVersionKey(PsiElement anchor, String versionKey,
                                                           DependencyStateService state) {
        var dep = state.anyUpdatableForVersionKey(versionKey);
        if (dep == null) return null;
        // Update via the dep's primary entry (which uses this version.ref by construction of
        // anyUpdatableForVersionKey). CVE markers stay on the individual library entries.
        return buildMarker(anchor, dep, dep, dep.getCatalogEntry(), null);
    }

    private LineMarkerInfo<?> buildMarker(PsiElement anchor,
                                          Dependency sourceDep,
                                          @Nullable Dependency updateDep,
                                          @Nullable CatalogEntry updateVia,
                                          @Nullable List<Vulnerability> vulns) {
        var hasUpdate = updateDep != null;
        var hasVulns = vulns != null && !vulns.isEmpty();

        Icon icon;
        String tooltip;
        if (hasVulns) {
            var top = highestSeverity(vulns);
            icon = (top == Vulnerability.Severity.CRITICAL || top == Vulnerability.Severity.HIGH)
                    ? AllIcons.General.Error
                    : AllIcons.General.Warning;
            tooltip = buildVulnTooltip(sourceDep, vulns, hasUpdate, updateDep, updateVia);
        } else {
            icon = AllIcons.Actions.Download;
            tooltip = buildUpdateTooltip(sourceDep, updateDep, updateVia);
        }

        // Capture what the click needs — avoids re-resolving via state at click time.
        var clickUpdateDep = updateDep;
        var clickUpdateVia = updateVia;
        return new LineMarkerInfo<>(
                anchor,
                anchor.getTextRange(),
                icon,
                elt -> tooltip,
                (mouseEvent, elt) -> handleClick(elt, sourceDep, clickUpdateDep, clickUpdateVia, hasVulns),
                GutterIconRenderer.Alignment.LEFT,
                () -> "Dependency status"
        );
    }

    private void handleClick(PsiElement element, Dependency sourceDep,
                             @Nullable Dependency updateDep, @Nullable CatalogEntry updateVia,
                             boolean hasVulns) {
        var project = element.getProject();
        if (updateDep != null) {
            var state = project.getService(DependencyStateService.class);
            DependencyUpdater.apply(project, updateDep, updateVia, state.getCatalog());
            // Clear both — the routed dep (in case DependencyUpdater cleared it too, no harm) and
            // the source dep so the badge on THIS line goes quiet immediately. The next refresh
            // re-checks both.
            updateDep.setLatestVersion(null);
            if (updateDep != sourceDep) sourceDep.setLatestVersion(null);
            state.notifyChange();
        } else if (hasVulns) {
            var coord = URLEncoder.encode(sourceDep.getGroup() + ":" + sourceDep.getName(),
                    StandardCharsets.UTF_8);
            BrowserUtil.browse("https://osv.dev/list?q=" + coord + "&ecosystem=Maven");
        }
    }
}