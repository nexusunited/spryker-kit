import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.*

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "1.6.10"
    // Gradle IntelliJ Plugin
    id("org.jetbrains.intellij.platform") version "2.2.1"
    // Gradle Changelog Plugin
    id("org.jetbrains.changelog") version "2.2.1"
    // Gradle Qodana Plugin
    id("org.jetbrains.qodana") version "0.1.13"
    // detekt linter - read more: https://detekt.github.io/detekt/gradle.html
    id("io.gitlab.arturbosch.detekt") version "1.14.2"
    // ktlint linter - read more: https://github.com/JLLeitschuh/ktlint-gradle
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    // id("com.x5dev.chunk.templates") version "3.5.0"
}

group = properties("pluginGroup")
version = properties("pluginVersion")

// Configure project's dependencies
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.13.3")
    implementation("com.jayway.jsonpath:json-path:2.7.0") {
        exclude("org.slf4j", "slf4j-api")
    }

    // als twig parser https://pebbletemplates.io/ + exclude slf4j, da es anscheinend schon intellij added und singleton ist
    implementation("io.pebbletemplates:pebble:3.1.6") {
        exclude("org.slf4j", "slf4j-api")
    }
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.20.0")
}

// Configure Gradle IntelliJ Plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
//intellijPlatform {
   // pluginName.set(properties("pluginName"))
    //version.set(properties("platformVersion"))
  //  type.set(properties("platformType"))

    // localPath.set("/home/patrickjaja/.local/share/JetBrains/Toolbox/apps/PhpStorm/ch-0/213.6777.58")
    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
   // plugins.set(properties("platformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))
//}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
}

// Configure Gradle Qodana Plugin - read more: https://github.com/JetBrains/gradle-qodana-plugin
qodana {
    cachePath.set(projectDir.resolve(".qodana").canonicalPath)
    reportPath.set(projectDir.resolve("build/reports/inspections").canonicalPath)
    saveReport.set(true)
    showReport.set(System.getenv("QODANA_SHOW_REPORT")?.toBoolean() ?: false)
}

tasks {
    // Set the JVM compatibility versions
    properties("javaVersion").let {
        withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }
        withType<KotlinCompile> {
            kotlinOptions.jvmTarget  = it
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set(properties("pluginSinceBuild"))
        untilBuild.set(properties("pluginUntilBuild"))

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run { markdownToHTML(this) }
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(
            provider {
                changelog.renderItem(changelog.run {
                    getOrNull(properties("pluginVersion")) ?: getLatest()
                }, Changelog.OutputType.HTML)
            }
        )
    }

    runIde {
        jvmArgs("-Xmx2024m", "-Xms512m", "-ea")
    }

    // Configure UI tests plugin
    // Read more: https://github.com/JetBrains/intellij-ui-test-robot
    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("ide.mac.message.dialogs.as.sheets", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("jb.consents.confirmation.enabled", "false")
        jvmArgs("-Xmx2024m", "-Xms512m", "-XX:MaxPermSize=500m", "-ea")
    }

    runIdePerformanceTest {
        testDataDir.set(projectDir.resolve("src/test").canonicalPath)
    }

    signPlugin {
        if (System.getenv("CHAIN_CERT").isNullOrEmpty()) {
            certificateChain.set(File(".ssl-keys/chain.crt").readText(Charsets.UTF_8))
        } else {
            certificateChain.set(System.getenv("CHAIN_CERT"))
        }

        if (System.getenv("PRIVATE_PEM").isNullOrEmpty()) {
            certificateChain.set(File(".ssl-keys/private.pem").readText(Charsets.UTF_8))
        } else {
            certificateChain.set(System.getenv("PRIVATE_PEM"))
        }

        if (System.getenv("PASSWORD_TXT").isNullOrEmpty()) {
            certificateChain.set(File(".ssl-keys/password.txt").readText(Charsets.UTF_8))
        } else {
            certificateChain.set(System.getenv("PASSWORD_TXT"))
        }
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token.set(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }

    buildPlugin {
        doFirst {
            project.exec {
                workingDir(".")
                executable("./build_twig_cache.sh")
            }
        }
    }
}