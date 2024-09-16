package com.github.tarn2206.tooling;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Pair;
import org.apache.commons.lang3.StringUtils;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.model.GradleProject;
import org.jetbrains.plugins.gradle.GradleManager;
import org.jetbrains.plugins.gradle.util.GradleConstants;

public class GradleHelper
{
    private GradleHelper() {}

    public static ProjectInfo getProjectInfo(Project project)
    {
        if (project.getBasePath() == null) return null;
        var connector = createConnector(project, new File(project.getBasePath()));
        try (var connection = connector.connect())
        {
            var builder = connection.model(GradleProject.class);
            var projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (projectSdk != null && projectSdk.getHomePath() != null)
            {
                builder.setJavaHome(new File(projectSdk.getHomePath()));
            }
            return getProjectInfo(builder.get());
        }
    }

    private static ProjectInfo getProjectInfo(GradleProject gradleProject)
    {
        var children = gradleProject.getChildren().stream().map(GradleHelper::getProjectInfo).collect(Collectors.toList());
        return new ProjectInfo(gradleProject.getName(), gradleProject.getBuildScript().getSourceFile(), children);
    }

    public static List<Dependency> getDependencies(Project project, File projectDirectory) throws IOException
    {
        var connector = createConnector(project, projectDirectory);
        try (var connection = connector.connect(); var out = new ByteArrayOutputStream())
        {
            var buildLauncher = connection.newBuild();
            var projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (projectSdk != null && projectSdk.getHomePath() != null)
            {
                buildLauncher.setJavaHome(new File(projectSdk.getHomePath()));
            }
            buildLauncher.forTasks("dependencies").setStandardOutput(out).run();
            return parseDependencies(out.toString());
        }
    }

    private static GradleConnector createConnector(Project project, File projectDirectory)
    {
        var connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory);
        var manager = (GradleManager)ExternalSystemApiUtil.getManager(GradleConstants.SYSTEM_ID);
        if (manager != null)
        {
            var executionSettingsProvider = manager.getExecutionSettingsProvider()
                                                   .fun(new Pair<>(project, project.getBasePath()));
            var gradleHome = executionSettingsProvider.getGradleHome();
            if (gradleHome != null)
            {
                connector.useInstallation(new File(gradleHome));
            }
        }
        return connector;
    }

    private static List<Dependency> parseDependencies(String source)
    {
        var list = new ArrayList<Dependency>();
        try (var scanner = new Scanner(source))
        {
            var inBlock = false;
            while (scanner.hasNext())
            {
                var line = scanner.nextLine();
                if (StringUtils.containsIgnoreCase(line, "compileClasspath - ")
                        || StringUtils.containsIgnoreCase(line, "runtimeClasspath - ")
                        || StringUtils.containsIgnoreCase(line, "implementation - "))
                {
                    inBlock = true;
                }
                else if (line.isEmpty())
                {
                    inBlock = false;
                }

                if (inBlock && (line.startsWith("+--- ") || line.startsWith("\\--- "))) // only first level
                {
                    var dependency = parseDependency(line.substring(5));
                    if (dependency != null && list.stream().noneMatch(e -> e.sameModule(dependency)))
                    {
                        list.add(dependency);
                    }
                }
            }
        }
        return list;
    }

    private static Dependency parseDependency(String s)
    {
        if (s.startsWith("project :")) return null;

        var clean = s.replaceAll("\\{strictly (.*)}", "$1");
        var a = clean.split(":");
        if (a.length < 2) return null;

        if (a.length > 2)
        {
            return new Dependency(a[0], a[1], a[2]);
        }

        var i = a[1].indexOf(" -> ");
        if (i != -1)
        {
            var name = a[1].substring(0, i);
            var version = a[1].substring(i + 4);
            return new Dependency(a[0], name, version);
        }

        i = a[1].indexOf(" ");
        if (i == -1)
        {
            return new Dependency(a[0], a[1], null);
        }

        var name = a[1].substring(0, i);
        var d = new Dependency(a[0], name, null);
        d.setError(a[1].substring(i + 1));
        return "(n)".equals(d.getError()) ? null : d;
    }
}
