// Command ergo locates Go function and method declarations by name.
//
// Usage:
//
//	ergo <function-name> <package>
//
// <package> is a Go import path ("encoding/json") or a directory path
// ("./internal/search"); it is resolved to a source directory via `go list`.
// ergo prints one tab-separated "file:line:col<TAB>name" record per matching
// declaration; methods are shown as "(Recv).Name".
package main

import (
	"flag"
	"fmt"
	"os"

	"ergo/internal/resolve"
	"ergo/internal/search"
)

func main() {
	flag.Usage = func() {
		fmt.Fprintln(os.Stderr, "usage: ergo <function-name> <package>")
	}
	flag.Parse()
	if flag.NArg() != 2 {
		flag.Usage()
		os.Exit(2)
	}
	name, pkg := flag.Arg(0), flag.Arg(1)

	dir, err := resolve.Package(pkg, "")
	if err != nil {
		fmt.Fprintln(os.Stderr, "ergo:", err)
		os.Exit(1)
	}

	results, err := search.FindFunction(dir, name)
	if err != nil {
		fmt.Fprintln(os.Stderr, "ergo:", err)
		os.Exit(1)
	}
	if len(results) == 0 {
		fmt.Fprintf(os.Stderr, "ergo: no function %q in %s\n", name, pkg)
		os.Exit(1)
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
