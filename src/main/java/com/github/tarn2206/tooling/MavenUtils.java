package com.github.tarn2206.tooling;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MavenUtils
{
    private static final String MAVEN_REPO_URL = "https://repo1.maven.org/maven2/";
    private static final String MAVEN_SEARCH_URL = "https://search.maven.org/solrsearch/select?wt=json&rows=1";

    private MavenUtils()
    {}

    public static Dependency checkForUpdate(Dependency dependency) throws IOException
    {
        var url = MAVEN_REPO_URL + dependency.group.replace('.', '/') + "/" + dependency.name;
        var connection = (HttpURLConnection)new URL(url).openConnection();
        if (connection.getResponseCode() != 200)
        {
            dependency.error = connection.getResponseMessage();
            return dependency;
        }

        var html = IOUtils.toString(connection.getInputStream(), UTF_8);
        try (var scanner = new Scanner(html))
        {
            Date latest = null;
            while (scanner.hasNext())
            {
                var line = scanner.nextLine();
                if (line.startsWith("<a href="))
                {
                    latest = parseLine(line, dependency, latest);
                }
            }
        }
        return dependency;
    }

    private static Date parseLine(String line, Dependency dependency, Date latest)
    {
        var end = line.indexOf("</a>");
        var s = line.substring(end + 4).trim();
        if (s.length() >= 16)
        {
            try
            {
                var date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s.substring(0, 16));
                var latestVersion = parseLatestVersion(line, end);
                if (latest == null || (latest.getTime() < date.getTime() && latestVersion != null))
                {
                    latest = date;
                    setLatestVersion(dependency, latestVersion);
                }
            }
            catch (Exception e)
            {
                dependency.error = e.getMessage();
            }
        }
        return latest;
    }

    private static String parseLatestVersion(String line, int end)
    {
        if (line.charAt(end - 1) != '/') return null; // not a directory
        var start = line.indexOf('>');
        var latestVersion = line.substring(start + 1, end - 1);
        var lower = latestVersion.toLowerCase();
        return lower.contains(".rc") || lower.contains(".m") ? null : latestVersion; // ignore release candidate version
    }

    // incorrect result, eg. commons-io
    public static Dependency checkForUpdateApi(Dependency dependency) throws IOException
    {
        var url = MAVEN_SEARCH_URL + "&q=g:" + dependency.group + "+and+a:" + dependency.name;
        var connection = (HttpURLConnection)new URL(url).openConnection();
        if (connection.getResponseCode() != 200)
        {
            dependency.error = IOUtils.toString(connection.getErrorStream(), UTF_8);
            return dependency;
        }

        var json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                     .readTree(connection.getInputStream());
        var id = dependency.group + ':' + dependency.name;
        for (var node : json.get("response").get("docs"))
        {
            if (id.equals(node.get("id").asText()))
            {
                setLatestVersion(dependency, node.get("latestVersion").asText());
                break;
            }
        }
        return dependency;
    }

    private static void setLatestVersion(Dependency dependency, String latestVersion)
    {
        if (latestVersion == null) return;

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
