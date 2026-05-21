# Ergo IntelliJ plugin

JetBrains IDE plugin that surfaces the concrete errors a Go function can
return, computed by the `ergo` analyzer in the parent directory.

## Status

Phase 2 — hovering a Go function or method shows an "Errors (ergo)" section in
the Quick Documentation popup, listing the concrete errors it can return
(`ErgoDocumentationProvider`). Results are not cached yet — each hover re-runs
the analyzer.

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
