import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

// group and version are read from gradle.properties.

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        // GoLand bundles the Go plugin, whose PSI this plugin analyzes.
        goland(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.go")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild is left at the plugin default (<branch>.*); the Go
            // plugin's PSI APIs are version-sensitive, so compatibility is
            // not assumed across major IDE releases.
        }
    }
}
