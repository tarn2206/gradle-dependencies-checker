package com.github.tarn2206.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import javax.net.ssl.HttpsURLConnection;

//import com.fasterxml.jackson.databind.DeserializationFeature;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;

public class MavenUtils
{
    private static final String MAVEN_REPO_URL = "https://repo1.maven.org/maven2/";
    private static final String MAVEN_SEARCH_URL = "https://search.maven.org/solrsearch/select?wt=json&rows=1";

    private MavenUtils()
    {}

    public static Dependency checkForUpdate(Dependency dependency) throws IOException
    {
        String url = MAVEN_REPO_URL + dependency.group.replace('.', '/') + "/" + dependency.name;
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url).openConnection();
        if (connection.getResponseCode() != 200)
        {
            dependency.error = toString(connection.getErrorStream(), StandardCharsets.UTF_8);
            return dependency;
        }

        String html = toString(connection.getInputStream(), StandardCharsets.UTF_8);
        Scanner scanner = new Scanner(html);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date latest = null;
        while (scanner.hasNext())
        {
            String line = scanner.nextLine();
            if (!line.startsWith("<a href=")) continue;

            int end = line.indexOf("</a>");
            String s = line.substring(end + 4).trim();
            if (s.length() < 16) continue; // no date information

            try
            {
                Date date = df.parse(s.substring(0, 16));
                String latestVersion = parseLatestVersion(line, end);
                if (latest == null || (latest.getTime() < date.getTime() && latestVersion != null))
                {
                    latest = date;
                    dependency.latestVersion = latestVersion;
                }
            }
            catch (Exception e)
            {
                dependency.error = e.getMessage();
            }
        }
        return dependency;
    }

    private static String parseLatestVersion(String line, int end)
    {
        if (line.charAt(end - 1) != '/') return null; // not a directory
        int start = line.indexOf('>');
        String latestVersion = line.substring(start + 1, end - 1);
        String lower = latestVersion.toLowerCase();
        return lower.contains(".rc") || lower.contains(".m") ? null : latestVersion; // ignore release candidate version
    }

    private static String toString(InputStream in, Charset charset) throws IOException
    {
        StringBuilder s = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(in, charset))
        {
            char[] buffer = new char[4096];
            int n = 0;
            while ((n = reader.read(buffer)) != -1)
            {
                s.append(buffer, 0, n);
            }
        }
        return s.toString();
    }

    // incorrect result, eg. commons-io
    /*public static Dependency checkForUpdateApi(Dependency dependency) throws IOException
    {
        String url = MAVEN_SEARCH_URL + "&q=g:" + dependency.group + "+and+a:" + dependency.name;
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url).openConnection();
        if (connection.getResponseCode() != 200)
        {
            dependency.error = IOUtils.toString(connection.getErrorStream(), StandardCharsets.UTF_8);
            return dependency;
        }

        JsonNode json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                          .readTree(connection.getInputStream());
        String id = dependency.group + ':' + dependency.name;
        for (JsonNode node : json.get("response").get("docs"))
        {
            if (id.equals(node.get("id").asText()))
            {
                dependency.latestVersion = node.get("latestVersion").asText();
                break;
            }
        }
        return dependency;
    }*/
}
