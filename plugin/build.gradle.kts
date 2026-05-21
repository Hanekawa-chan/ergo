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

    // Bundled into the plugin: parses the `ergo` analyzer's JSON output.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
}

// --- Cross-compile the ergo Go analyzer for every bundled platform ------------
//
// The binaries are embedded under /bin in the plugin jar; ErgoBinary extracts
// the one matching the host at runtime. Requires the Go toolchain on PATH at
// build time.

val repoRoot = layout.projectDirectory.dir("..")
val ergoBinDir = layout.buildDirectory.dir("ergo-bin")

// GOOS, GOARCH, file extension.
val ergoTargets = listOf(
    Triple("linux", "amd64", ""),
    Triple("linux", "arm64", ""),
    Triple("darwin", "amd64", ""),
    Triple("darwin", "arm64", ""),
    Triple("windows", "amd64", ".exe"),
    Triple("windows", "arm64", ".exe"),
)

val buildErgoBinaries by tasks.registering {
    group = "build"
    description = "Cross-compiles the ergo Go analyzer for every bundled platform."
}

ergoTargets.forEach { (goos, goarch, ext) ->
    val compile = tasks.register<Exec>("compileErgo-$goos-$goarch") {
        description = "Builds the ergo analyzer for $goos/$goarch."
        workingDir = repoRoot.asFile
        environment("GOOS", goos)
        environment("GOARCH", goarch)
        environment("CGO_ENABLED", "0")

        inputs.dir(repoRoot.dir("cmd"))
        inputs.dir(repoRoot.dir("internal"))
        inputs.file(repoRoot.file("go.mod"))
        inputs.file(repoRoot.file("go.sum"))

        val binary = ergoBinDir.map { it.file("ergo-$goos-$goarch$ext") }
        outputs.file(binary)

        doFirst { binary.get().asFile.parentFile.mkdirs() }
        commandLine(
            "go", "build", "-trimpath", "-ldflags", "-s -w",
            "-o", binary.get().asFile.absolutePath, "./cmd/ergo",
        )
    }
    buildErgoBinaries.configure { dependsOn(compile) }
}

tasks.processResources {
    dependsOn(buildErgoBinaries)
    from(ergoBinDir) {
        into("bin")
    }
}
