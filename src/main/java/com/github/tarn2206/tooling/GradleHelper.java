package com.github.tarn2206.tooling;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

import com.google.gson.Gson;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import org.apache.commons.lang3.StringUtils;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.jetbrains.plugins.gradle.GradleManager;
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

final class GradleHelper {
    private GradleHelper() { }

    static BuildSnapshot loadBuild(Project project) {
        var basePath = project.getBasePath();
        if (basePath == null) throw new IllegalArgumentException("Project has no base directory");
        Path script = null;
        Path output = null;
        try {
            var settings = getExecutionSettings(project, basePath);
            script = Files.createTempFile("dependency-checker-", ".gradle");
            output = Files.createTempFile("dependency-checker-", ".json");
            try (var resource = GradleHelper.class.getResourceAsStream("/gradle/direct-dependencies.gradle")) {
                if (resource == null) throw new IOException("Missing direct-dependencies Gradle script");
                Files.write(script, resource.readAllBytes());
            }
            runBuild(basePath, settings, script, output);
            var modules = new Gson().fromJson(Files.readString(output), BuildSnapshot.ModuleEntry[].class);
            if (modules == null || modules.length == 0) throw new IOException("Gradle returned no project data");
            return new BuildSnapshot(modules);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read Gradle dependencies", e);
        } finally {
            deleteTemporaryFiles(script, output);
        }
    }

    private static void runBuild(String projectDirectory, GradleExecutionSettings settings, Path script, Path output)
            throws IOException {
        var directory = new File(projectDirectory).getCanonicalFile();
        var connector = GradleConnector.newConnector().forProjectDirectory(directory);
        if (settings != null) configureConnector(connector, settings);
        try (var connection = connector.connect()) {
            var build = connection.newBuild();
            if (settings != null) configureBuildLauncher(build, settings);
            var task = "dependencyChecker" + UUID.randomUUID().toString().replace("-", "");
            build.forTasks(":" + task)
                 .withArguments("--init-script", script.toString(), "--no-configuration-cache",
                                "-PdependencyCheckerTask=" + task,
                                "-PdependencyCheckerProject=" + directory.getPath(),
                                "-PdependencyCheckerOutput=" + output)
                 .run();
        }
    }

    private static GradleExecutionSettings getExecutionSettings(Project project, String projectDirectory) {
        var manager = (GradleManager)ExternalSystemApiUtil.getManager(GradleConstants.SYSTEM_ID);
        if (manager == null) return null;

        return manager.getExecutionSettingsProvider()
                      .fun(new Pair<>(project, FileUtil.toSystemIndependentName(projectDirectory)));
    }

    private static void configureConnector(GradleConnector connector, GradleExecutionSettings settings) {
        var gradleHome = settings.getGradleHome();
        if (StringUtils.isNotBlank(gradleHome)) {
            connector.useInstallation(new File(gradleHome));
        }
        var userHome = settings.getServiceDirectory();
        if (StringUtils.isNotBlank(userHome)) {
            connector.useGradleUserHomeDir(new File(userHome));
        }
    }

    private static void configureBuildLauncher(BuildLauncher build, GradleExecutionSettings settings) {
        // Gradle JVM can differ from the Project SDK. Let IntelliJ resolve its aliases.
        // An absent Java home leaves Gradle defaults / daemon JVM criteria in control.
        var javaHome = settings.getJavaHome();
        if (StringUtils.isNotBlank(javaHome)) {
            build.setJavaHome(new File(javaHome));
        }

        // Merge parent variables only when requested by IntelliJ settings.
        var env = settings.getEnv();
        if (!settings.isPassParentEnvs()) {
            build.setEnvironmentVariables(env);
        } else if (!env.isEmpty()) {
            var combined = new HashMap<String, String>(System.getenv());
            combined.putAll(env);
            build.setEnvironmentVariables(combined);
        }
    }

    private static void deleteTemporaryFiles(Path... files) {
        // Cleanup must not mask a Gradle failure.
        for (var file : files) {
            if (file == null) continue;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                file.toFile().deleteOnExit();
            }
        }
    }
}
