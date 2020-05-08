package com.github.tarn2206.tooling;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public class GradleHelper
{
    private GradleHelper()
    {}

    public static List<Dependency> listDependencies(String projectDir)
    {
        return listDependencies(new File(projectDir));
    }

    public static List<Dependency> listDependencies(File projectDir)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ProjectConnection connection = GradleConnector.newConnector().forProjectDirectory(projectDir).connect())
        {
            connection.newBuild()
                      .setStandardOutput(out)
                      .forTasks("dependencies")
                      .run();
        }

        List<Dependency> list = new ArrayList<>();
        Scanner scanner = new Scanner(out.toString());
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
