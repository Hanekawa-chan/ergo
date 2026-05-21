// Command ergo inspects Go functions.
//
// Usage:
//
//	ergo find   [-json] <function-name> <package>   locate a declaration by name
//	ergo errors [-json] <function-name> <package>   report the errors it can return
//
// <package> is a Go import path ("encoding/json") or a directory path
// ("./internal/search").
//
// With -json, results are written to stdout as a single JSON object — the
// machine-readable format consumed by the JetBrains plugin. The schema is:
//
//	find:   {"results":   [{"name","recv","file","line","col"}, ...]}
//	errors: {"functions": [{"name","recv","pos","findings":[
//	                          {"kind","type","name","message","wrapped","reason","pos"}]}, ...]}
//
// On failure the object is {"error": "<message>"} and the exit status is 1.
// Optional fields are omitted when empty; "kind" is one of "sentinel", "type",
// "constructed", or "unresolved".
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"ergo/internal/analyze"
	"ergo/internal/resolve"
	"ergo/internal/search"
)

// jsonOut reports whether output (results and errors alike) should be emitted
// as JSON. It is set from the -json flag before any output is produced.
var jsonOut bool

func main() {
	args := os.Args[1:]
	if len(args) == 0 {
		usage()
		os.Exit(2)
	}
	cmd, rest := args[0], args[1:]

	switch cmd {
	case "find":
		name, pkg := parseArgs(cmd, rest)
		runFind(name, pkg)
	case "errors":
		name, pkg := parseArgs(cmd, rest)
		runErrors(name, pkg)
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage:")
	fmt.Fprintln(os.Stderr, "  ergo find   [-json] <function-name> <package>")
	fmt.Fprintln(os.Stderr, "  ergo errors [-json] <function-name> <package>")
}

// parseArgs parses the flags and two positional arguments of a subcommand,
// setting jsonOut as a side effect. It exits with status 2 on a usage error.
func parseArgs(cmd string, args []string) (name, pkg string) {
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	fs.Usage = usage
	fs.BoolVar(&jsonOut, "json", false, "emit machine-readable JSON")
	fs.Parse(args)
	if fs.NArg() != 2 {
		usage()
		os.Exit(2)
	}
	return fs.Arg(0), fs.Arg(1)
}

// runFind locates declarations of name. In text mode it prints one
// "file:line:col<TAB>name" line per match; in JSON mode it emits {"results": …}.
func runFind(name, pkg string) {
	dir, err := resolve.Package(pkg, "")
	if err != nil {
		fatal(err)
	}
	results, err := search.FindFunction(dir, name)
	if err != nil {
		fatal(err)
	}
	if len(results) == 0 {
		fatalf("no function %q in %s", name, pkg)
	}

	if jsonOut {
		emitJSON(struct {
			Results []search.Result `json:"results"`
		}{results})
		return
	}
	for _, r := range results {
		where := fmt.Sprintf("%s:%d:%d", r.File, r.Line, r.Col)
		if r.Recv != "" {
			fmt.Printf("%s\t(%s).%s\n", where, r.Recv, r.Name)
		} else {
			fmt.Printf("%s\t%s\n", where, r.Name)
		}
	}
}

// runErrors reports the errors each function named name can return. In text
// mode it prints a human-readable listing; in JSON mode it emits
// {"functions": …}.
func runErrors(name, pkg string) {
	fns, err := analyze.Analyze(pkg, name, "")
	if err != nil {
		fatal(err)
	}

	if jsonOut {
		emitJSON(struct {
			Functions []analyze.Function `json:"functions"`
		}{fns})
		return
	}
	for _, fn := range fns {
		title := fn.Name
		if fn.Recv != "" {
			title = "(" + fn.Recv + ")." + fn.Name
		}
		fmt.Printf("%s\t%s\n", title, fn.Pos)
		if len(fn.Findings) == 0 {
			fmt.Println("  (returns no error)")
			continue
		}
		for _, f := range fn.Findings {
			fmt.Printf("  %-11s %s\t%s\n", f.Kind, describe(f), f.Pos)
		}
	}
}

// describe renders the identifying detail of a finding.
func describe(f analyze.Finding) string {
	switch f.Kind {
	case analyze.KindSentinel:
		return f.Name
	case analyze.KindType:
		return f.Type
	case analyze.KindConstructed:
		s := fmt.Sprintf("%q", f.Message)
		if f.Message == "" {
			s = "(non-constant message)"
		}
		if f.Wrapped {
			s += " (wraps another error)"
		}
		return s
	default:
		return f.Reason
	}
}

// emitJSON writes v to stdout as indented JSON, exiting on a write/encode error.
func emitJSON(v any) {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		fmt.Fprintln(os.Stderr, "ergo:", err)
		os.Exit(1)
	}
}

// fatal reports err and exits with status 1. In JSON mode the error is written
// to stdout as {"error": …} so the plugin can parse a single stream.
func fatal(err error) {
	if jsonOut {
		emitJSON(struct {
			Error string `json:"error"`
		}{err.Error()})
	} else {
		fmt.Fprintln(os.Stderr, "ergo:", err)
	}
	os.Exit(1)
}

func fatalf(format string, args ...any) {
	fatal(fmt.Errorf(format, args...))
}
