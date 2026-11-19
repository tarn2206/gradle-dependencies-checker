package com.github.tarn2206.tooling;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.intellij.openapi.diagnostic.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Repository paths, transport, authentication and safe diagnostics. Does not mutate UI dependencies.
 */
final class MavenRepositoryClient {
    private static final Logger LOG = Logger.getInstance(MavenRepositoryClient.class);

    private MavenRepositoryClient() { }

    record Result(MavenMetadata metadata, String error) { }

    static List<String> metadataUrls(String repository, String group, String artifact) {
        var base = repository;
        if (!base.contains("://") && !base.startsWith("file:")) base = new File(base).toURI().toString();
        var directory = (base.endsWith("/") ? base : base + "/") + group.replace('.', '/') + "/" + artifact + "/";
        return "file".equalsIgnoreCase(URI.create(base).getScheme())
               ? List.of(directory + "maven-metadata.xml", directory + "maven-metadata-local.xml")
               : List.of(directory + "maven-metadata.xml");
    }

    static Result read(String url) throws IOException, GeneralSecurityException {
        var connection = openConnection(url);
        try {
            if (connection instanceof HttpURLConnection http) {
                var code = http.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    if (code == HttpURLConnection.HTTP_NOT_FOUND) return new Result(null, null);
                    LOG.warn(code + " " + sanitizeUrl(url));
                    return new Result(null, http.getResponseMessage());
                }
            }
            try (var input = connection.getInputStream()) {
                return new Result(MavenMetadata.read(input), null);
            } catch (FileNotFoundException ignored) {
                return new Result(null, null);
            }
        } finally {
            if (connection instanceof HttpURLConnection http) http.disconnect();
        }
    }

    static URLConnection openConnection(String url) throws GeneralSecurityException, IOException {
        var uri = URI.create(url);
        var userInfo = uri.getUserInfo();
        var connection = withoutCredentials(uri).toURL().openConnection();
        if (connection instanceof HttpsURLConnection httpsConnection) {
            trustAll(httpsConnection);
        }
        if (connection instanceof HttpURLConnection && StringUtils.isNotBlank(userInfo)) {
            var authorization = "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(UTF_8));
            connection.setRequestProperty("Authorization", authorization);
        }
        return connection;
    }

    private static URI withoutCredentials(URI uri) {
        if (uri.getRawUserInfo() == null) return uri;
        // Strip only user-info, preserving percent-encoded paths and query parameters.
        var text = uri.toString();
        var authorityStart = uri.getScheme().length() + 3;
        return URI.create(text.substring(0, authorityStart)
                          + text.substring(authorityStart + uri.getRawUserInfo().length() + 1));
    }

    private static void trustAll(HttpsURLConnection connection) throws GeneralSecurityException {
        var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {/*ignored*/}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {/*ignored*/}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, new X509TrustManager[] { trustAll }, new SecureRandom());
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
    }

    static String sanitizeUrl(String url) {
        try {
            var uri = withoutCredentials(URI.create(url));
            if ("file".equalsIgnoreCase(uri.getScheme())) return "<local Maven repository>";
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                                           || "https".equalsIgnoreCase(uri.getScheme()))) {
                return "<invalid repository URL>";
            }
            // Query parameters and fragments may also contain tokens.
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return "<invalid repository URL>";
        }
    }
}
