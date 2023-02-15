package com.github.tarn2206.tooling;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.model.GradleProject;

public class GradleHelper
{
    private GradleHelper()
    {}

    public static ProjectInfo getProjectInfo(String projectPath) throws IOException
    {
        var connector = newConnector(projectPath, new File(projectPath));
        try (var connection = connector.connect())
        {
            return create(connection.getModel(GradleProject.class));
        }
    }

    private static ProjectInfo create(GradleProject gradleProject)
    {
        var children = gradleProject.getChildren().stream().map(GradleHelper::create).collect(Collectors.toList());
        return new ProjectInfo(gradleProject.getName(), gradleProject.getBuildScript().getSourceFile(), children);
    }

    public static List<Dependency> getDependencies(String projectPath, File projectDirectory) throws IOException
    {
        var connector = newConnector(projectPath, projectDirectory);
        try (var connection = connector.connect(); var out = new ByteArrayOutputStream())
        {
            connection.newBuild()
                   .forTasks("dependencies")
                   .setStandardOutput(out)
                   .run();
            return parseDependencies(out.toString());
        }
    }

    private static GradleConnector newConnector(String rootProjectPath, File projectDirectory) throws IOException
    {
        var connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory);
        var gradleWrapper = new File(projectDirectory, "gradle/wrapper/gradle-wrapper.properties");
        if (!gradleWrapper.exists())
        {
            gradleWrapper = new File(rootProjectPath, "gradle/wrapper/gradle-wrapper.properties");
        }
        if (gradleWrapper.exists())
        {
            var props = new Properties();
            try (var in = new FileInputStream(gradleWrapper))
            {
                props.load(in);
            }
            var distributionUrl = props.getProperty("distributionUrl");
            connector.useDistribution(URI.create(distributionUrl));
        }
        else
        {
            var gradleHome = System.getenv("GRADLE_HOME");
            if (StringUtils.isNotBlank(gradleHome))
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
        d.error = a[1].substring(i + 1);
        return "(n)".equals(d.error) ? null : d;
    }
}
