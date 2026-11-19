import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
  java
  id("org.jetbrains.changelog")
  id("org.jetbrains.intellij.platform")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
  implementation(gradleApi())
  implementation(libs.maven.artifact) { isTransitive = false }
  compileOnly(libs.lombok)
  annotationProcessor(libs.lombok)
  testImplementation(libs.junit)

  // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
  intellijPlatform {
    intellijIdea("2025.1")
    testFramework(TestFrameworkType.Platform)

    bundledPlugins("com.intellij.java", "com.intellij.gradle")
    pluginVerifier()
  }
}

// https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html#intellijPlatform-pluginVerification-ides
// https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html#platformVersions
intellijPlatform {
  pluginVerification {
    ides {
      recommended()
      select {
        types.set(listOf(IntelliJPlatformType.IntellijIdeaCommunity))
        channels.set(listOf(ProductRelease.Channel.RELEASE))
        sinceBuild.set("251")
        untilBuild.set("263.*")
      }
    }
  }
}

tasks.buildSearchableOptions {
  enabled = false
}

tasks.patchPluginXml {
  sinceBuild.set("251")
  untilBuild.set(provider<String> { null })
  changeNotes.set("""
<h2>2.1.0</h2>
<ul>
    <li>Fix TreeUI should be accessed only from EDT</li>
</ul>
<h2>2.0.2</h2>
<ul>
    <li>Improve running background tasks</li>
</ul>
<h2>2.0.1</h2>
<ul>
    <li>Fix plugin not available for new version of IntelliJ</li>
</ul>
<h2>2.0.0</h2>
<ul>
    <li>Upgrade to IntelliJ Platform Gradle Plugin (2.x)</li>
</ul>
<h2>1.6.4</h2>
<ul>
    <li>Remove deprecated and internal api usage</li>
</ul>
<h2>1.6.3</h2>
<ul>
    <li>Support authenticate private maven repository by include credentials in the URL<br>https://username:password@your-repo.com/</li>
    <li>Improve Android project support</li>
</ul>
<h2>1.6.2</h2>
<ul>
    <li>Fix tool window title</li>
</ul>
<h2>1.6.1</h2>
<ul>
    <li>Fix not detect android test implementation</li>
    <li>Resolve conflict tool window name</li>
</ul>
<h2>1.6</h2>
<ul>
    <li>Support custom maven repository</li>
    <li>Filtering unstable versions</li>
</ul>
<h2>1.5.2</h2>
<ul>
    <li>Move refresh action from popup menu to tool window title</li>
    <li>Remove close button from tool window title</li>
</ul>
<h2>1.5.1</h2>
<ul>
    <li>Support a prefix version range: e.g. 1.+, 1.3.+</li>
    <li>Fix bug android projects not show dependencies</li>
</ul>
<h2>1.5</h2>
<ul>
    <li>Support nested-project</li>
    <li>Prevent multiple check update for same dependency</li>
    <li>Resolve existing tool window name</li>
</ul>
<h2>1.4</h2>
<ul>
    <li>Support multi-project build</li>
    <li>Remove require dependencies plugins</li>
</ul>
""")
}
