package com.github.tarn2206.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import com.github.tarn2206.AppSettings;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MavenUtils
{
    private MavenUtils()
    {}

    public static Dependency checkForUpdate(Dependency dependency, AppSettings settings)
    {
        var activeList = settings.repos.stream().filter(repo -> repo.active).collect(Collectors.toList());
        for (var repo : activeList)
        {
            try
            {
                var url = combine(repo.url, dependency.group.replace('.', '/') + "/" + dependency.name + "/maven-metadata.xml");
                var connection = (HttpURLConnection)openConnection(url);
                if (connection.getResponseCode() == 200)
                {
                    parseMetadata(connection.getInputStream(), dependency, settings);
                    break;
                }

                dependency.error = connection.getResponseMessage();
            }
            catch (Exception e)
            {
                dependency.error = e.getMessage();
            }
        }
        return dependency;
    }

    private static String combine(String a, String b)
    {
        return a.endsWith("/") ? a + b : a + "/" + b;
    }

    private static URLConnection openConnection(String url) throws GeneralSecurityException, IOException
    {
        var connection = new URL(url).openConnection();
        if (connection instanceof HttpsURLConnection)
        {
            X509TrustManager trustAll = new X509TrustManager()
            {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                {/*ignored*/}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                {/*ignored*/}

                @Override
                public X509Certificate[] getAcceptedIssuers()
                {
                    return new X509Certificate[0];
                }
            };
            var sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, new X509TrustManager[] { trustAll }, new SecureRandom());
            var httpsConnection = (HttpsURLConnection)connection;
            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
            httpsConnection.setHostnameVerifier((hostname, session) -> true);
        }
        setAuthentication(connection, url);
        return connection;
    }

    private static void setAuthentication(URLConnection connection, String url)
    {
        var uri = URI.create(url);
        var credentials = uri.getUserInfo();
        if (StringUtils.isNotBlank(credentials))
        {
            var authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
            connection.setRequestProperty("Authorization", authorization);
        }
    }

    public static void parseMetadata(InputStream in, Dependency dependency, AppSettings settings) throws IOException
    {
        var text = IOUtils.toString(in, UTF_8);
        var latestVersion = findLatestVersion(text, settings);
        if (latestVersion == null)
        {
            latestVersion = scanForStableVersion(text, settings);
        }
        if (latestVersion != null)
        {
            setLatestVersion(dependency, latestVersion);
            dependency.error = null;
        }
    }

    private static String findLatestVersion(String text, AppSettings settings)
    {
        String latestVersion = null;
        var matcher = Pattern.compile("<latest>(.*)</latest>").matcher(text);
        if (matcher.find())
        {
            latestVersion = matcher.group(1);
        }
        else
        {
            matcher = Pattern.compile("<release>(.*)</release>").matcher(text);
            if (matcher.find())
            {
                latestVersion = matcher.group(1);
            }
        }
        if (latestVersion != null && settings.ignoreUnstable && !isStable(latestVersion, settings.unstablePatterns))
        {
            latestVersion = null;
        }
        return latestVersion;
    }

    private static String scanForStableVersion(String text, AppSettings settings)
    {
        String latestVersion = null;
        var matcher = Pattern.compile("<version>(.*)</version>").matcher(text);
        while (matcher.find())
        {
            var version = matcher.group(1);
            if (!settings.ignoreUnstable || isStable(version, settings.unstablePatterns))
            {
                latestVersion = version;
            }
        }
        return latestVersion;
    }

    private static boolean isStable(String version, String patterns)
    {
        if (StringUtils.isBlank(patterns)) return true;

        var array = patterns.split(",");
        for (var pattern : array)
        {
            pattern = StringUtils.trimToEmpty(pattern);
            if (!pattern.isEmpty() && StringUtils.containsIgnoreCase(version, pattern)) return false;
        }
        return true;
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
