// Command ergo locates Go function and method declarations by name.
//
// Usage:
//
//	ergo <function-name> <package-dir>
//
// It prints one tab-separated "file:line:col<TAB>name" record per matching
// declaration; methods are shown as "(Recv).Name".
package main

import (
	"flag"
	"fmt"
	"os"

	"ergo/internal/search"
)

func main() {
	flag.Usage = func() {
		fmt.Fprintln(os.Stderr, "usage: ergo <function-name> <package-dir>")
	}
	flag.Parse()
	if flag.NArg() != 2 {
		flag.Usage()
		os.Exit(2)
	}
	name, dir := flag.Arg(0), flag.Arg(1)

	results, err := search.FindFunction(dir, name)
	if err != nil {
		fmt.Fprintln(os.Stderr, "ergo:", err)
		os.Exit(1)
	}
	if len(results) == 0 {
		fmt.Fprintf(os.Stderr, "ergo: no function %q in %s\n", name, dir)
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