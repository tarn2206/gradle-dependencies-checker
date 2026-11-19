package com.github.tarn2206.tooling;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.github.tarn2206.AppSettings;
import com.sun.net.httpserver.HttpServer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MavenUtilsTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void checksLegacyPluginImplementationCoordinates() throws IOException {
        var repository = temporary.newFolder().toPath();
        var metadata = repository.resolve("example/legacy/maven-metadata.xml");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                                    <metadata><versioning><versions><version>1.0</version><version>2.0</version>
                                    <version>3.0-RC1</version></versions></versioning></metadata>
                                    """);
        var settings = settings(true);
        settings.setRepos(List.of(new AppSettings.Repo(true, "Fixture", repository.toUri().toString())));
        var plugin = Dependency.classpathPlugin("example", "legacy", "1.0");
        MavenUtils.checkForUpdate(plugin, settings);
        assertEquals("2.0", plugin.getLatestVersion());
        assertNull(plugin.getError());
    }

    @Test
    public void checksPluginMarkerInConfiguredRepositoryBeforePortal() throws IOException {
        var repository = temporary.newFolder().toPath();
        var metadata = repository.resolve("example/plugin/example.plugin.gradle.plugin/maven-metadata.xml");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                                    <metadata><versioning><versions>
                                    <version>1.0</version><version>2.0</version><version>3.0-RC1</version>
                                    </versions></versioning></metadata>
                                    """);
        var settings = settings(true);
        settings.setRepos(List.of(new AppSettings.Repo(true, "Fixture", repository.toUri().toString())));
        var plugin = Dependency.plugin("example.plugin", "1.0");
        MavenUtils.checkForUpdate(plugin, settings);
        assertEquals("2.0", plugin.getLatestVersion());
        assertNull(plugin.getError());
    }

    @Test
    public void prefersLatestAndFallsBackToRelease() throws IOException {
        assertEquals("2.0", latest("<latest>2.0</latest>\n<release>1.0</release>", true));
        assertEquals("1.0", latest("<release>1.0</release>", true));
        assertEquals("1.0", latest("<latest> </latest>\n<release>1.0</release>", true));
    }

    @Test
    public void selectsHighestStableEntryRegardlessOfMetadataOrder() throws IOException {
        assertEquals("3.0", latest("""
                                   <latest>4.0-RC1</latest>
                                   <release>3.0</release>
                                   <versions>
                                   <version>3.0</version>
                                   <version>2.0</version>
                                   <version>4.0-BETA</version>
                                   </versions>
                                   """, true));
    }

    @Test
    public void preservesBehaviorWhenUnstableVersionsAreAllowed() throws IOException {
        assertEquals("4.0-RC1", latest("<latest>4.0-RC1</latest>", false));
        assertEquals("2.0", latest("<versions><version>2.0</version></versions>", false));
        assertEquals("2.0", latest("<versions><version>2.0</version></versions>", true));
        assertNull(latest("<versions><version>2.0-BETA</version></versions>", true));
        assertNull(latest("<metadata/>", true));
    }

    @Test
    public void honorsBlankAndCaseInsensitiveUnstablePatterns() throws IOException {
        var settings = settings(true);
        settings.setUnstablePatterns(" , Rc , ");
        assertNull(read("<latest>2.0-rc1</latest>", settings));
        settings.setUnstablePatterns(" ");
        assertEquals("2.0-rc1", read("<latest>2.0-rc1</latest>", settings));
    }

    private static String latest(String xml, boolean ignoreUnstable) throws IOException {
        return read(xml, settings(ignoreUnstable));
    }

    private static AppSettings settings(boolean ignoreUnstable) {
        var settings = new AppSettings();
        settings.setIgnoreUnstable(ignoreUnstable);
        settings.setUnstablePatterns("alpha, beta, rc, snapshot");
        return settings;
    }

    private static String read(String xml, AppSettings settings) throws IOException {
        return readDocument("<metadata><versioning>" + xml + "</versioning></metadata>", settings);
    }

    private static String readDocument(String xml, AppSettings settings) throws IOException {
        var input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var unstablePatterns = settings.isIgnoreUnstable() ? settings.getUnstablePatterns() : null;
        return MavenUtils.selectVersion(MavenMetadata.read(input), unstablePatterns);
    }

    @Test
    public void parsesCompactMultilineAndEscapedXml() throws IOException {
        assertEquals("2.0", latest("<latest>\n 2.0\n</latest><release>1.0</release>", true));
        assertEquals("2.0&custom", latest("<latest>2.0&amp;custom</latest>", false));
        assertEquals("2.0", latest("<latest><![CDATA[2.0]]></latest>", true));
        assertNull(readDocument("<metadata><version>9.0</version></metadata>", settings(true)));
    }

    @Test
    public void rejectsMalformedXmlAndExternalEntities() {
        assertThrows(IOException.class, () -> readDocument("<metadata>", settings(true)));
        assertThrows(IOException.class, () -> readDocument("""
                                                           <!DOCTYPE metadata [<!ENTITY secret SYSTEM "file:///must-not-be-read">]>
                                                           <metadata><versioning><latest>&secret;</latest></versioning></metadata>
                                                           """, settings(true)));
        assertThrows(IOException.class, () -> readDocument("""
                                                           <!DOCTYPE metadata SYSTEM "http://127.0.0.1:1/must-not-be-requested">
                                                           <metadata/>
                                                           """, settings(true)));
    }

    @Test
    public void removesCredentialsWithoutChangingEncodedRequestUrl() throws Exception {
        var url = "http://user:p%40ss@localhost:1234/repo%2Fpath/artifact?x=a%2Fb";
        var connection = (HttpURLConnection)MavenRepositoryClient.openConnection(url);
        try {
            assertEquals("http://localhost:1234/repo%2Fpath/artifact?x=a%2Fb", connection.getURL().toString());
            assertNull(connection.getURL().getUserInfo());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    public void sendsBasicAuthenticationToLocalServer() throws Exception {
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://user:p%40ss@127.0.0.1:" + server.getAddress().getPort() + "/";
            var connection = (HttpURLConnection)MavenRepositoryClient.openConnection(url);
            try {
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                assertEquals(204, connection.getResponseCode());
                var base64 = Base64.getEncoder().encodeToString("user:p@ss".getBytes(StandardCharsets.UTF_8));
                assertEquals("Basic " + base64, authorization.get());
            } finally {
                connection.disconnect();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void logUrlsNeverIncludeUserInfoQueryOrFragment() {
        assertEquals("https://example.com/repo", MavenRepositoryClient.sanitizeUrl("https://user:p%40ss@example.com/repo?token=secret#secret"));
        assertEquals("<invalid repository URL>", MavenRepositoryClient.sanitizeUrl("https://user:secret@example.com/bad path"));
        assertEquals("<invalid repository URL>", MavenRepositoryClient.sanitizeUrl("user:secret@example.com"));
        assertEquals("<invalid repository URL>", MavenRepositoryClient.sanitizeUrl(null));
        assertEquals("https://example.com/repo", MavenRepositoryClient.sanitizeUrl("https://example.com/repo"));
    }

    @Test
    public void comparesMavenQualifiersNumericallyAndRecognizesReleases() {
        var ordered = List.of("1.0-alpha1", "1.0-beta1", "1.0-rc1", "1.0-rc2", "1.0-rc10",
                              "1.0-SNAPSHOT", "1.0", "1.0-sp1", "1.0.1");
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                assertTrue(ordered.get(i) + " < " + ordered.get(j),
                           MavenUtils.compareVersions(ordered.get(i), ordered.get(j)) < 0);
                assertTrue(MavenUtils.compareVersions(ordered.get(j), ordered.get(i)) > 0);
            }
        }
        assertEquals(0, MavenUtils.compareVersions("1.0-RC2", "1.0-rc2"));
        assertEquals(0, MavenUtils.compareVersions("1", "1.0.0"));
        assertEquals(0, MavenUtils.compareVersions("1.0-final", "1.0"));
        assertTrue(MavenUtils.compareVersions("1.99999999999999999999999", "1.9999999999999999999999") > 0);
    }

    @Test
    public void prefersHighestListedVersionOverLatestAndFiltersBeforeComparing() throws IOException {
        var xml = """
                  <latest>1.0-rc1</latest><release>0.8</release>
                  <versions><version>1.0-rc2</version><version>0.9</version>
                  <version>1.0-rc10</version><version>1.0-rc1</version><version> </version></versions>
                  """;
        assertEquals("1.0-rc10", latest(xml, false));
        assertEquals("0.9", latest(xml, true));
        assertEquals("1.0", latest("<latest>2.0-rc1</latest><release>1.0</release><versions/>", true));
    }

    @Test
    public void readsLocalMetadataFromFileUriAndPlainPathWithSpaces() throws IOException {
        var repo = temporary.newFolder("local maven repo").toPath();
        writeMetadata(repo, "maven-metadata-local.xml", "1.0-rc2", "1.0-rc10", "1.0-rc1");
        for (var location : List.of(repo.toUri().toString(), repo.toString())) {
            var settings = settings(false);
            settings.setRepos(List.of(new AppSettings.Repo(true, "local", location)));
            var dependency = new Dependency("example", "library", "1.0-rc2");
            dependency.setError("old error");
            MavenUtils.checkForUpdate(dependency, settings);
            assertEquals("1.0-rc10", dependency.getLatestVersion());
            assertNull(dependency.getError());
            var newer = new Dependency("example", "library", "1.0");
            MavenUtils.checkForUpdate(newer, settings);
            assertNull(newer.getLatestVersion());
        }
    }

    @Test
    public void skipsMissingAndDisabledRepositoriesAndKeepsRepositoryPriority() throws IOException {
        var missing = temporary.newFolder("missing").toPath();
        var first = temporary.newFolder("first").toPath();
        var second = temporary.newFolder("second").toPath();
        writeMetadata(first, "maven-metadata.xml", "2.0", "1.0");
        writeMetadata(second, "maven-metadata-local.xml", "9.0");
        var settings = settings(true);
        settings.setRepos(List.of(
                new AppSettings.Repo(false, "disabled", second.toUri().toString()),
                new AppSettings.Repo(true, "missing", missing.toUri().toString()),
                new AppSettings.Repo(true, "first", first.toUri().toString()),
                new AppSettings.Repo(true, "second", second.toUri().toString())));
        var dependency = new Dependency("example", "library", "1.0");
        MavenUtils.checkForUpdate(dependency, settings);
        assertEquals("2.0", dependency.getLatestVersion());
    }

    @Test
    public void triesNextRepositoryWhenHttpMetadataHasNoUsableVersion() throws IOException {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (var name : List.of("empty", "unstable", "usable", "unused")) {
            var version = switch (name) {
                case "empty" -> "";
                case "unstable" -> "2.0-rc10";
                case "usable" -> "2.0";
                default -> "9.0";
            };
            server.createContext("/" + name + "/", exchange -> {
                requests.add(name);
                var xml = "<metadata><versioning><versions><version>" + version
                          + "</version></versions></versioning></metadata>";
                var bytes = xml.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (exchange; var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
        }
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var settings = settings(true);
            settings.setRepos(Stream.of("empty", "unstable", "usable", "unused")
                                    .map(name -> new AppSettings.Repo(true, name, base + "/" + name)).toList());
            var dependency = new Dependency("example", "library", "1.0");
            dependency.setError("previous error");
            MavenUtils.checkForUpdate(dependency, settings);
            assertEquals("2.0", dependency.getLatestVersion());
            assertNull(dependency.getError());
            assertEquals(List.of("empty", "unstable", "usable"), requests);
        } finally {
            server.stop(0);
        }
    }

    private static void writeMetadata(Path repo, String filename, String... versions) throws IOException {
        var directory = Files.createDirectories(repo.resolve("example/library"));
        var xml = new StringBuilder("<metadata><versioning><versions>");
        for (var version : versions) xml.append("<version>").append(version).append("</version>");
        Files.writeString(directory.resolve(filename), xml.append("</versions></versioning></metadata>").toString());
    }
}
