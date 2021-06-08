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

import org.apache.commons.lang3.StringUtils;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public class GradleHelper
{
    private GradleHelper()
    {}

    public static List<String> listProjects(String projectDir) throws IOException
    {
        String text = run(projectDir, "projects");
        return parseListProjects(text);
    }

    public static List<Dependency> listDependencies(String projectDir) throws IOException
    {
        String text = run(projectDir, "dependencies");
        return parseListDependencies(text);
    }

    private static String run(String projectDir, String task) throws IOException
    {
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(new File(projectDir));
        File gradleWrapper = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties");
        if (gradleWrapper.exists())
        {
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(gradleWrapper))
            {
                props.load(in);
            }
            String distributionUrl = props.getProperty("distributionUrl");
            connector.useDistribution(URI.create(distributionUrl));
        }
        else
        {
            String gradleHome = System.getenv("GRADLE_HOME");
            if (StringUtils.isNotBlank(gradleHome))
            {
                connector.useInstallation(new File(gradleHome));
            }
        }

        try (ProjectConnection project = connector.connect();
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            project.newBuild()
                   .forTasks(task)
                   .setStandardOutput(out)
                   .run();
            return out.toString();
        }
    }

    private static List<String> parseListProjects(String text)
    {
        List<String> list = new ArrayList<>();
        Scanner scanner = new Scanner(text);
        while (scanner.hasNext())
        {
            String line = scanner.nextLine();
            if (line.startsWith("Root project ") || line.startsWith("+--- Project ") || line.startsWith("\\--- Project "))
            {
                int i = line.indexOf('\'');
                if (line.charAt(i + 1) == ':') i++;
                String name = line.substring(i + 1, line.length() - 1);
                if (!list.contains(name))
                    list.add(name);
            }
        }
        return list;
    }

    private static List<Dependency> parseListDependencies(String text)
    {
        List<Dependency> list = new ArrayList<>();
        Scanner scanner = new Scanner(text);
        boolean inBlock = false;
        while (scanner.hasNext())
        {
            String line = scanner.nextLine();
            if (line.startsWith("compileClasspath - ") || line.startsWith("testCompileClasspath - ")
                || line.startsWith("debugRuntimeClasspath - "))
            {
                inBlock = true;
            }
            if (!inBlock) continue;
            if (line.isEmpty())
            {
                inBlock = false;
                continue;
            }

            if (line.startsWith("+--- ") || line.startsWith("\\--- "))
            {
                Dependency dependency = parseDependency(line.substring(5));
                if (dependency != null && !list.contains(dependency))
                {
                    list.add(dependency);
                }
            }
        }
        return list;
    }

    private static Dependency parseDependency(String s)
    {
        String[] a = s.split(":");
        if (a.length < 2) return null;

        if (a.length == 2)
        {
            int i = a[1].indexOf(" -> ");
            if (i == -1)
            {
                return new Dependency(a[0], trimEnd(a[1]), null);
            }
            String version = a[1].substring(i + 4);
            return new Dependency(a[0], a[1].substring(0, i), trimEnd(version));
        }

        if (a[2].contains(" "))
        {
            a[2] = a[2].substring(0, a[2].indexOf(' '));
        }
        return new Dependency(a[0], a[1], a[2]);
    }

    private static String trimEnd(String s)
    {
        return s.endsWith(" (*)") ? s.substring(0, s.length() - 4) : s;
    }
}
