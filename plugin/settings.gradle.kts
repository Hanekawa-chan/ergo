import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "ergo-intellij"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
}

plugins {
    // Provisions JDK toolchains on demand, so only a JRE 17+ is needed to start Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Registers the org.jetbrains.intellij.platform plugin for build.gradle.kts.
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
