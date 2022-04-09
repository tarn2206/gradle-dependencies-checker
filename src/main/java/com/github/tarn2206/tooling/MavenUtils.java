package com.github.tarn2206.tooling;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MavenUtils
{
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final String ANDROID_MAVEN = "https://dl.google.com/dl/android/maven2/";

    private MavenUtils()
    {}

    public static Dependency checkForUpdate(Dependency dependency) throws IOException
    {
        String url;
        if (dependency.group.contains("android") || dependency.group.contains("firebase"))
        {
            url = ANDROID_MAVEN + dependency.group.replace('.', '/') + "/" + dependency.name + "/maven-metadata.xml";
        }
        else
        {
            url = MAVEN_CENTRAL + dependency.group.replace('.', '/') + "/" + dependency.name + "/maven-metadata.xml";
        }
        var connection = (HttpURLConnection)new URL(url).openConnection();
        if (connection.getResponseCode() != 200)
        {
            dependency.error = StringUtils.toRootLowerCase(connection.getResponseMessage());
            return dependency;
        }

        try
        {
            var text = IOUtils.toString(connection.getInputStream(), UTF_8);
            var matcher = Pattern.compile("<latest>(.*)</latest>").matcher(text);
            if (matcher.find())
            {
                var latestVersion = matcher.group(1);
                if (latestVersion != null)
                {
                    setLatestVersion(dependency, latestVersion);
                }
            }
        }
        catch (Exception e)
        {
            dependency.error = e.getMessage();
        }
        return dependency;
    }

    private static void setLatestVersion(Dependency dependency, String latestVersion)
    {
        var current = parseVersion(dependency.version);
        while (current.size() < 3) current.add(0);
        var latest = parseVersion(latestVersion);
        var min = Math.min(current.size(), latest.size());
        for (var i = 0; i < min; i++)
        {
            if (!latest.get(i).equals(current.get(i)))
            {
                if (latest.get(i) > current.get(i))
                {
                    dependency.latestVersion = latestVersion;
                }
                break;
            }
        }
    }

    private static List<Integer> parseVersion(String version)
    {
        var list = new ArrayList<Integer>();
        var a = version.split("\\.");
        for (var s : a)
        {
            try
            {
                var n = Integer.parseInt(s);
                list.add(n);
            }
            catch (Exception e)
            {
                break;
            }
        }
        return list;
    }
}
