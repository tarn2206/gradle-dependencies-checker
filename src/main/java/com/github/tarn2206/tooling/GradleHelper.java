package com.github.tarn2206.tooling;

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Pair;
import org.apache.commons.lang3.StringUtils;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.LongRunningOperation;
import org.gradle.tooling.model.GradleProject;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.GradleManager;
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

public class GradleHelper {
    private GradleHelper() {
    }

    public static ProjectInfo getProjectInfo(Project project) {
        if (project.getBasePath() == null) return null;
        var settings = getGradleSettings(project);
        var connector = createConnector(new File(project.getBasePath()), settings);
        try (var connection = connector.connect()) {
            var builder = connection.model(GradleProject.class);
            applyExecutionSettings(builder, project, settings);
            return getProjectInfo(builder.get());
        }
    }

    private static ProjectInfo getProjectInfo(GradleProject gradleProject) {
        var children = gradleProject.getChildren().stream().map(GradleHelper::getProjectInfo).toList();
        return new ProjectInfo(gradleProject.getName(), gradleProject.getBuildScript().getSourceFile(), children);
    }

    public static List<Dependency> getDependencies(Project project, File projectDirectory) {
        var settings = getGradleSettings(project);
        var connector = createConnector(projectDirectory, settings);
        try (var connection = connector.connect(); var out = new ByteArrayOutputStream()) {
            var buildLauncher = connection.newBuild();
            applyExecutionSettings(buildLauncher, project, settings);
            buildLauncher.forTasks("dependencies").setStandardOutput(out).run();
            return parseDependencies(out.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static @Nullable GradleExecutionSettings getGradleSettings(Project project) {
        var manager = (GradleManager) ExternalSystemApiUtil.getManager(GradleConstants.SYSTEM_ID);
        if (manager == null) return null;
        try {
            return manager.getExecutionSettingsProvider()
                    .fun(new Pair<>(project, project.getBasePath()));
        } catch (Exception e) {
            return null;
        }
    }

    private static GradleConnector createConnector(File projectDirectory,
                                                   @Nullable GradleExecutionSettings settings) {
        var connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory);
        if (settings != null) {
            if (settings.getGradleHome() != null) {
                connector.useInstallation(new File(settings.getGradleHome()));
            }
            // Gradle User Home — where ~/.gradle/gradle.properties (credentials, GPR tokens) lives.
            // Matching what IntelliJ normally uses ensures the same properties and daemon are picked up.
            var serviceDir = settings.getServiceDirectory();
            if (StringUtils.isNotBlank(serviceDir)) {
                connector.useGradleUserHomeDir(new File(serviceDir));
            }
        }
        return connector;
    }

    /**
     * Mirrors IntelliJ's Gradle sync configuration on our Tooling API operations. Without this,
     * private repos requiring auth (e.g. GitHub Packages) fail to resolve for us even when they
     * work for "Reload All Gradle Projects" — because credentials are typically passed through
     * env vars, JVM args, or gradle.properties in the configured Gradle User Home.
     */
    private static void applyExecutionSettings(LongRunningOperation op, Project project,
                                               @Nullable GradleExecutionSettings settings) {
        // Java home: prefer Gradle-specific JDK from IntelliJ settings, fallback to project SDK.
        String javaHome = null;
        if (settings != null && StringUtils.isNotBlank(settings.getJavaHome())) {
            javaHome = settings.getJavaHome();
        } else {
            var sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null && sdk.getHomePath() != null) {
                javaHome = sdk.getHomePath();
            }
        }
        if (javaHome != null) {
            op.setJavaHome(new File(javaHome));
        }

        if (settings == null) return;

        // Environment variables — often where GITHUB_TOKEN etc. get plumbed. If IntelliJ is
        // configured to pass parent env, merge with current JVM env so nothing is dropped.
        var env = settings.getEnv();
        if (env != null && !env.isEmpty()) {
            if (settings.isPassParentEnvs()) {
                var combined = new HashMap<String, String>(System.getenv());
                combined.putAll(env);
                op.setEnvironmentVariables(combined);
            } else {
                op.setEnvironmentVariables(env);
            }
        }
    }

    private static List<Dependency> parseDependencies(String source) {
        var list = new ArrayList<Dependency>();
        try (var scanner = new Scanner(source)) {
            var inBlock = false;
            while (scanner.hasNext()) {
                var line = scanner.nextLine();
                inBlock = checkIsInBlock(line, inBlock);

                if (inBlock && (line.startsWith("+--- ") || line.startsWith("\\--- "))) // only first level
                {
                    var dependency = parseDependency(line.substring(5));
                    if (dependency != null && list.stream().noneMatch(e -> e.sameModule(dependency))) {
                        list.add(dependency);
                    }
                }
            }
        }
        return list;
    }

    private static boolean checkIsInBlock(String line, boolean inBlock) {
        if (StringUtils.containsAnyIgnoreCase(line,
                "compileClasspath - ",
                "runtimeClasspath - ",
                "implementation - ")) return true;
        if (line.isEmpty()) return false;
        return inBlock;
    }

    private static Dependency parseDependency(String s) {
        if (s.startsWith("project ")) return null;

        var clean = s.replaceAll("\\{strictly (.*)}", "$1");
        var a = clean.split(":");
        if (a.length < 2) return null;

        if (a.length > 2) {
            return new Dependency(a[0], a[1], a[2]);
        }

        var i = a[1].indexOf(" -> ");
        if (i != -1) {
            var name = a[1].substring(0, i);
            var version = a[1].substring(i + 4);
            return new Dependency(a[0], name, version);
        }

        i = a[1].indexOf(" ");
        if (i == -1) {
            return new Dependency(a[0], a[1], null);
        }

        var name = a[1].substring(0, i);
        var d = new Dependency(a[0], name, null);
        d.setError(a[1].substring(i + 1));
        return "(n)".equals(d.getError()) ? null : d;
    }
}