// Command ergo inspects Go functions.
//
// Usage:
//
//	ergo find   <function-name> <package>   locate a declaration by name
//	ergo errors <function-name> <package>   report the errors it can return
//
// <package> is a Go import path ("encoding/json") or a directory path
// ("./internal/search").
package main

import (
	"fmt"
	"os"

	"ergo/internal/analyze"
	"ergo/internal/resolve"
	"ergo/internal/search"
)

func main() {
	args := os.Args[1:]
	if len(args) != 3 {
		usage()
		os.Exit(2)
	}
	cmd, name, pkg := args[0], args[1], args[2]

	switch cmd {
	case "find":
		runFind(name, pkg)
	case "errors":
		runErrors(name, pkg)
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage:")
	fmt.Fprintln(os.Stderr, "  ergo find   <function-name> <package>")
	fmt.Fprintln(os.Stderr, "  ergo errors <function-name> <package>")
}

// runFind locates declarations of name and prints "file:line:col<TAB>name".
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
	for _, r := range results {
		where := fmt.Sprintf("%s:%d:%d", r.File, r.Line, r.Col)
		if r.Recv != "" {
			fmt.Printf("%s\t(%s).%s\n", where, r.Recv, r.Name)
		} else {
			fmt.Printf("%s\t%s\n", where, r.Name)
		}
	}
}

// runErrors prints the errors each function named name can return.
func runErrors(name, pkg string) {
	fns, err := analyze.Analyze(pkg, name, "")
	if err != nil {
		fatal(err)
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

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "ergo:", err)
	os.Exit(1)
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "ergo: "+format+"\n", args...)
	os.Exit(1)
}
