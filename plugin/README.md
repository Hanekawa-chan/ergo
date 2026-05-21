# Ergo IntelliJ plugin

JetBrains IDE plugin that surfaces the concrete errors a Go function can
return, computed by the `ergo` analyzer in the parent directory.

## Status

Phase 0 — project scaffold only. No features yet.

## Prerequisites

- A JDK 17+ on `PATH` (or `JAVA_HOME`) to start Gradle. The build provisions
  JDK 21 for compilation automatically via the Foojay toolchain resolver.
- At runtime the bundled `ergo` analyzer needs a Go toolchain on the end
  user's machine; later phases derive it from the project's Go SDK.

## Build

```sh
./gradlew buildPlugin   # assemble the plugin .zip under build/distributions
./gradlew runIde        # launch a sandbox GoLand with the plugin loaded
./gradlew test          # run tests
```

`platformVersion` in `gradle.properties` selects the GoLand release the plugin
is built and run against; set it to one available in JetBrains' repositories.
