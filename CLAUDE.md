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

- `internal/resolve/` — `Package(spec, workdir)` maps a package spec (import
  path *or* directory path) to its absolute source directory by shelling out to
  `go list -json`. Delegating to the `go` tool gives module / module-cache /
  GOROOT awareness for free.
- `internal/search/` — `FindFunction(dir, name)` parses every non-test `.go`
  file in a single package directory and returns each function/method
  declaration matching `name` (as `Result`: name, receiver, file, line, col).
  Stdlib only — `go/parser` + `go/ast`, no type checking.
- `internal/analyze/` — `Analyze(pkg, name, workdir)` reports the errors each
  function named `name` can return. It loads the package with `go/packages`,
  builds SSA (`go/ssa`), and traces every value reaching an error-typed
  `return`, recursing into concrete callees. Each `Finding` has a `Kind`:
  `sentinel` (e.g. `io.EOF`), `type` (e.g. `*os.PathError`), `constructed`
  (`errors.New` / `fmt.Errorf`, with the literal message and a `Wrapped` flag),
  or `unresolved` (with a reason). `analyze.go` = loading + public API;
  `classify.go` = the SSA value tracing.
- `cmd/ergo/` — CLI with two subcommands, both `<function-name> <package>`:
  `find` (resolve → search) and `errors` (analyze).

Dependency: `golang.org/x/tools` (for `go/packages` + `go/ssa`).

### Analyzer specifics & known limits

- `fmt.Errorf("%w", err)` reports the `constructed` wrapper *and* recurses into
  the wrapped argument(s): the format string is parsed to map `%w` verbs to
  variadic arguments, which are recovered from the SSA variadic slice.
- Wrapped errors passed via a spread (`fmt.Errorf(f, args...)`) cannot be
  recovered — the wrapper is still flagged `Wrapped`.
- Errors flowing through addressed local variables are followed via their SSA
  stores; errors stored through a pointer handed to another function are not.
- Analysis recurses transitively into stdlib, so results normally mix resolved
  findings with `unresolved` ones (interface calls, dynamic function values).
- Interface method calls become `unresolved`, by design (see Scope decisions).
- Generics are not specially handled (SSA built without `InstantiateGenerics`).
- Recursion cycles contribute nothing, so mutually recursive functions can be
  slightly under-approximated.
- `internal/resolve` and `internal/analyze` tests drive the `go` tool, so the
  toolchain must be on `PATH` (always true under `go test`).

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