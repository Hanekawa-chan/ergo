import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // Signs the plugin distribution with the JetBrains Marketplace ZIP signer.
    // The certificate chain, private key, and key password are read from the
    // environment so no secret is committed; `signPlugin` runs only when
    // invoked (e.g. by `publishPlugin`), so ordinary builds need none of them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // `publishPlugin` uploads the signed distribution to the JetBrains
    // Marketplace. PUBLISH_TOKEN is a Marketplace permanent token. The release
    // channel is taken from the version's pre-release segment — a version of
    // "0.2.0-eap.1" publishes to the "eap" channel, a plain "0.1.0" to "default".
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("version").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // `verifyPlugin` runs the JetBrains Plugin Verifier. The plugin depends on
    // the Go plugin, so it is checked against GoLand specifically — IDEs
    // without the Go plugin cannot satisfy that dependency.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.GoLand, providers.gradleProperty("platformVersion"))
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
