# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

`ergo` is intended to become a plugin for JetBrains IDEs.

**End goal:** given an imported function, statically determine every concrete
error type/value it can return, so the plugin can display that set to the user.
Go functions return the `error` interface (not concrete types), so this requires
data-flow analysis tracing which concrete error values reach each `return`.

**First step:** a single function that searches for a function name inside an
imported module.

The project is split across two languages:

- **Go** — the code-searching and error-analysis logic. This is what currently
  lives in this repository.
- **Kotlin** — the JetBrains IDE plugin side, written against the IntelliJ
  Platform SDK. Not present yet; it will invoke the Go search component.

### Scope decisions

- **Interface method calls are out of scope.** Only analyze actual implemented
  (concrete) functions. When error flow passes through an interface method call,
  do not attempt to resolve the implementation — skip it rather than
  over-approximate.

## Layout

- `internal/search/` — `FindFunction(dir, name)` parses every non-test `.go`
  file in a single package directory and returns each function/method
  declaration matching `name` (as `Result`: name, receiver, file, line, col).
  Stdlib only — `go/parser` + `go/ast`, no type checking yet.
- `cmd/ergo/` — thin CLI wrapper: `ergo <function-name> <package-dir>`, prints
  `file:line:col<TAB>name` per match.

`go.mod` has no third-party dependencies. Module-path resolution (import path
→ directory) and full type analysis are deferred to the error-analysis step.

## Commands

```sh
go build ./...              # build all packages
go test ./...               # run all tests
go test ./path/to/pkg       # run tests for one package
go test -run TestName ./... # run a single test by name
go vet ./...                # static analysis
gofmt -l -w .               # format all Go files
go mod tidy                 # sync go.mod/go.sum with imports
```