# ergo

[![CI](https://github.com/Hanekawa-chan/ergo/actions/workflows/ci.yml/badge.svg)](https://github.com/Hanekawa-chan/ergo/actions/workflows/ci.yml)

**ergo** statically determines the concrete error types and values a Go
function can return.

Go functions return the `error` *interface*, not concrete types, so a caller
cannot tell from a signature *which* errors might come back. `ergo` answers that
by tracing data flow through the SSA form of a program: for a given function it
reports every concrete error — sentinel values like `io.EOF`, named types like
`*os.PathError`, and `errors.New` / `fmt.Errorf` constructions — that can reach
one of its `return` statements.

It is built to back a JetBrains IDE plugin (see [`plugin/`](plugin/)) that
surfaces this error set in the Quick Documentation popup.

## Components

| Path                 | Language | Role                                              |
| -------------------- | -------- | ------------------------------------------------- |
| repository root      | Go       | the `ergo` analyzer and its CLI                   |
| [`plugin/`](plugin/) | Kotlin   | the JetBrains / GoLand IDE plugin (own Gradle build) |

## Install

Requires the Go toolchain (Go 1.25+).

```sh
go build -o ergo ./cmd/ergo      # build the CLI into ./ergo
```

## CLI

Both subcommands take a function name and a package — a Go import path
(`encoding/json`) or a directory (`./internal/search`):

```
ergo find   [-json] <function-name> <package>   locate a declaration
ergo errors [-json] <function-name> <package>   report the errors it can return
```

### `find` — locate a declaration

```
$ ergo find FindFunction ./internal/search
internal/search/search.go:33:6   FindFunction
```

More than one line is possible: methods sharing a name on different receiver
types, or declarations split across files by build constraints.

### `errors` — report returnable errors

```
$ ergo errors FindFunction ./internal/search
FindFunction   internal/search/search.go:33:6
  sentinel    io.EOF                                            io/io.go:44:5
  type        *os.PathError                                     os/file_unix.go:294:25
  constructed "invalid source"                                  go/parser/interface.go:39:25
  constructed "parse %s: %w" (wraps another error)              internal/search/search.go:49:26
  unresolved  error from an interface method call (Read)        io/io.go:712:19
```

Each finding has one of four kinds:

- **`sentinel`** — a package-level error variable, e.g. `io.EOF`.
- **`type`** — a concrete named error type, e.g. `*os.PathError`.
- **`constructed`** — an ad-hoc error from `errors.New` or `fmt.Errorf`, with
  its literal message; `%w` wrapping is followed into the wrapped error.
- **`unresolved`** — an error whose origin could not be determined, with a
  reason (e.g. an interface method call — see [Scope](#scope-and-limitations)).

### JSON output

With `-json`, each command emits a single machine-readable JSON object on
stdout — the format the IDE plugin consumes:

```sh
$ ergo errors -json Read ./demo
```

```json
{
  "functions": [
    {
      "name": "Read",
      "pos": "demo/demo.go:8:6",
      "findings": [
        { "kind": "sentinel", "type": "error", "name": "io.EOF", "pos": "io/io.go:44:5" },
        { "kind": "constructed", "message": "read failed", "pos": "demo/demo.go:12:19" }
      ]
    }
  ]
}
```

On failure the object is `{"error": "<message>"}` and the exit status is 1.
(Source positions are absolute paths in real output; they are shortened above
for readability.)

## How it works

`errors` is the heavy path: `internal/analyze` loads the package with
`go/packages` (full type information), builds its SSA form with `go/ssa`, and
for every function matching the requested name traces each value that can reach
an error-typed `return` — recursing into concrete callees, including across the
standard library.

`find` is lightweight: `internal/search` parses the package's `.go` files with
`go/parser` and matches declarations by name, with no type checking.

Package specs are resolved by `internal/resolve`, which shells out to
`go list -json` so module, module-cache, and `GOROOT` awareness come for free.

## Project layout

- `cmd/ergo/` — the CLI: the `find` and `errors` subcommands.
- `internal/resolve/` — maps a package spec (import path or directory) to its
  absolute source directory.
- `internal/search/` — `FindFunction`: parse-based declaration lookup by name.
- `internal/analyze/` — `Analyze`: the SSA-based error-flow analysis.
- `plugin/` — the JetBrains IDE plugin (Kotlin, Gradle); see
  [`plugin/README.md`](plugin/README.md).

Dependency: `golang.org/x/tools` (`go/packages` + `go/ssa`).

## Development

```sh
go build ./...              # build all packages
go test ./...               # run all tests
go vet ./...                # static analysis
gofmt -l -w .               # format all Go files
```

CI runs these checks plus the plugin build on every push — see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Scope and limitations

- **Interface method calls are out of scope.** When error flow passes through
  an interface method, `ergo` reports it `unresolved` rather than guessing the
  implementation. Analysis recurses into the standard library, so results
  normally mix resolved findings with `unresolved` ones.
- Generics are not specially handled; mutually recursive functions can be
  slightly under-approximated.
- Errors stored through a pointer handed to another function are not followed.

## Status

Early development. The Go analyzer and CLI are functional; the JetBrains plugin
shows the error set in Quick Documentation and is not yet published to the
JetBrains Marketplace.
