# Ergo IntelliJ plugin

JetBrains IDE plugin that surfaces the concrete errors a Go function can
return, computed by the `ergo` analyzer in the parent directory.

## Status

Phase 3 — hovering a Go function or method shows an "Errors (ergo)" section in
the Quick Documentation popup, and each listed error links to the source
location it originates from. Successful results are cached (invalidated on any
Go edit), the analyzer subprocess is cancellable, and its PATH is augmented
from the project's Go SDK. An integration test drives the analyzer end to end
against a real Go module.

## Prerequisites

- A JDK 17+ on `PATH` (or `JAVA_HOME`) to start Gradle. The build provisions
  JDK 21 for compilation automatically via the Foojay toolchain resolver.
- The **Go toolchain** on `PATH` at build time — Gradle cross-compiles `ergo`
  for every bundled platform.
- At runtime the bundled `ergo` analyzer also needs a Go toolchain; for now it
  inherits the IDE's environment, and a later phase derives it from the
  project's Go SDK.

## Build

```sh
./gradlew buildPlugin   # assemble the plugin .zip under build/distributions
./gradlew runIde        # launch a sandbox GoLand with the plugin loaded
./gradlew test          # run tests
```

`platformVersion` in `gradle.properties` selects the GoLand release the plugin
is built and run against; set it to one available in JetBrains' repositories.

## Publishing

`build.gradle.kts` is wired for the JetBrains Marketplace:

```sh
./gradlew signPlugin      # sign the distribution with the Marketplace ZIP signer
./gradlew publishPlugin   # sign, then upload to the Marketplace
```

Both read their secrets from the environment, so nothing sensitive is committed
and ordinary builds (`buildPlugin`, `test`) need none of them:

| Variable               | Used by         | Meaning                     |
| ---------------------- | --------------- | --------------------------- |
| `CERTIFICATE_CHAIN`    | `signPlugin`    | PEM certificate chain       |
| `PRIVATE_KEY`          | `signPlugin`    | PEM private key             |
| `PRIVATE_KEY_PASSWORD` | `signPlugin`    | private-key password        |
| `PUBLISH_TOKEN`        | `publishPlugin` | Marketplace permanent token |

The release channel is derived from the plugin version: a pre-release version
such as `0.2.0-eap.1` publishes to the `eap` channel, a plain `0.1.0` to
`default`.

Pushing a `v*` tag (e.g. `v0.1.0`) runs
[`.github/workflows/release.yml`](../.github/workflows/release.yml), which
tests, signs, publishes, and opens a GitHub Release — the tag name sets the
published version. The variables above must then be configured as GitHub
repository secrets; the current signing key is unencrypted, so
`PRIVATE_KEY_PASSWORD` is left unset.

The **first** version of a new plugin must instead be uploaded through the
Marketplace web form, where it goes through manual review.
