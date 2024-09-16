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
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class MavenUtils
{
    private static final Logger LOG = Logger.getInstance(MavenUtils.class);

    private MavenUtils() {}

    public static Dependency checkForUpdate(Dependency dependency, AppSettings settings)
    {
        var activeList = settings.getRepos().stream().filter(AppSettings.Repo::isActive).toList();
        for (var repo : activeList)
        {
            try
            {
                var url = combine(repo.getUrl(), dependency.getGroup().replace('.', '/') + "/" + dependency.getName() + "/maven-metadata.xml");
                var connection = (HttpURLConnection)openConnection(url);
                if (connection.getResponseCode() == 200)
                {
                    var latestVersion = getLatestVersion(connection.getInputStream(), settings);
                    if (latestVersion != null)
                    {
                        setLatestVersion(dependency, latestVersion);
                        dependency.setError(null);
                    }
                    break;
                }

                dependency.setError(connection.getResponseMessage());
                LOG.warn(connection.getResponseCode() + " " + url);
            }
            catch (Exception e)
            {
                dependency.setError(e.getMessage());
                LOG.error(e);
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
        if (connection instanceof HttpsURLConnection httpsConnection)
        {
            trustAll(httpsConnection);
        }
        var userInfo = URI.create(url).getUserInfo();
        if (StringUtils.isNotBlank(userInfo))
        {
            var authorization = "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(UTF_8));
            connection.setRequestProperty("Authorization", authorization);
        }
        return connection;
    }

    private static void trustAll(HttpsURLConnection connection) throws GeneralSecurityException
    {
        var trustAll = new X509TrustManager()
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
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
    }

    public static String getLatestVersion(InputStream in, AppSettings settings) throws IOException
    {
        var text = IOUtils.toString(in, UTF_8);
        var latest = find("<latest>(.*)</latest>", text);
        if (StringUtils.isBlank(latest))
        {
            latest = find("<release>(.*)</release>", text);
        }
        if (!settings.isIgnoreUnstable() || isStable(latest, settings.getUnstablePatterns()))
        {
            return latest;
        }

        latest = null;
        var matcher = Pattern.compile("<version>(.*)</version>").matcher(text);
        while (matcher.find())
        {
            var version = matcher.group(1);
            if (!settings.isIgnoreUnstable() || isStable(version, settings.getUnstablePatterns()))
            {
                latest = version;
            }
        }
        return latest;
    }

    private static String find(String regex, String input)
    {
        var matcher = Pattern.compile(regex).matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isStable(String version, String patterns)
    {
        if (StringUtils.isBlank(version)) return false;
        if (StringUtils.isBlank(patterns)) return true;

        var array = patterns.split(",");
        for (var pattern : array)
        {
            pattern = StringUtils.trimToEmpty(pattern);
            if (!pattern.isEmpty() && StringUtils.containsIgnoreCase(version, pattern))
            {
                return false;
            }
        }
        return true;
    }

    private static void setLatestVersion(Dependency dependency, String latestVersion)
    {
        var current = parseVersion(dependency.getVersion());
        while (current.size() < 3) current.add(0);
        var latest = parseVersion(latestVersion);
        var min = Math.min(current.size(), latest.size());
        for (var i = 0; i < min; i++)
        {
            if (!latest.get(i).equals(current.get(i)))
            {
                if (latest.get(i) > current.get(i))
                {
                    dependency.setLatestVersion(latestVersion);
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
