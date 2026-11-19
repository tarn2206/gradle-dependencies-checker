Checking for new Gradle dependency updates
========================================

[![Java CI with Gradle](https://github.com/tarn2206/gradle-dependencies-checker/actions/workflows/gradle.yml/badge.svg)](https://github.com/tarn2206/gradle-dependencies-checker/actions/workflows/gradle.yml)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/fcd2dd6c76ab4cdfba496acbbbeda796)](https://app.codacy.com/gh/tarn2206/gradle-dependencies-checker/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Plugin Download page
----------------------------------------

<https://plugins.jetbrains.com/plugin/14243-check-for-dependency-updates>

Direct dependency integration test
----------------------------------------

Run from the repository root with a JDK supported by the Gradle wrapper (for
example, JDK 21). No PowerShell or other additional runtime is required.

macOS/Linux:

```sh
./gradlew -p tests testDirectDependencies --offline --no-configuration-cache
```

Windows (Command Prompt or PowerShell):

```text
.\gradlew.bat -p tests testDirectDependencies --offline --no-configuration-cache
```

The standalone build in `tests` uses an offline Maven fixture and does not load
the plugin build or download IDE dependencies. The wrapper downloads its Gradle
distribution on first use, so network access is needed until that distribution
is cached. Fixture files and results are written under `tests/build`.
To use an installed Gradle instead, replace the wrapper command with `gradle`.
