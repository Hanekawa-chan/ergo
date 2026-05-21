// Package search locates Go function and method declarations by name within a
// single package directory.
//
// It is the first building block of ergo: later analysis traces the error
// types a located function can return.
package search

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strings"
)

// Result describes one function or method declaration that matched a search.
type Result struct {
	Name string `json:"name"`           // declared name of the function or method
	Recv string `json:"recv,omitempty"` // receiver type for methods (e.g. "*Buffer"); "" for plain functions
	File string `json:"file"`           // path to the file containing the declaration
	Line int    `json:"line"`           // 1-based line of the declared name
	Col  int    `json:"col"`            // 1-based column of the declared name
}

// FindFunction parses every non-test Go file in the package directory dir and
// returns each function or method declaration named name.
//
// More than one result is possible: methods with the same name on different
// receiver types, or declarations split across files by build constraints. An
// empty slice with a nil error means no match was found.
func FindFunction(dir, name string) ([]Result, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("read package directory: %w", err)
	}

	fset := token.NewFileSet()
	var results []Result

	for _, entry := range entries {
		if !isPackageGoFile(entry) {
			continue
		}
		path := filepath.Join(dir, entry.Name())
		file, err := parser.ParseFile(fset, path, nil, parser.SkipObjectResolution)
		if err != nil {
			return nil, fmt.Errorf("parse %s: %w", path, err)
		}
		for _, decl := range file.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok || fn.Name.Name != name {
				continue
			}
			pos := fset.Position(fn.Name.Pos())
			results = append(results, Result{
				Name: fn.Name.Name,
				Recv: receiverType(fn),
				File: path,
				Line: pos.Line,
				Col:  pos.Column,
			})
		}
	}
	return results, nil
}

// isPackageGoFile reports whether entry is a Go source file that belongs to the
// package's importable API — a regular ".go" file that is not a test file.
func isPackageGoFile(entry os.DirEntry) bool {
	if entry.IsDir() {
		return false
	}
	n := entry.Name()
	return strings.HasSuffix(n, ".go") && !strings.HasSuffix(n, "_test.go")
}

// receiverType returns a textual form of a method's receiver type, or "" when
// fn is a plain function. Pointer receivers keep their leading "*"; the type
// arguments of a generic receiver are dropped (e.g. "List[T]" becomes "List").
func receiverType(fn *ast.FuncDecl) string {
	if fn.Recv == nil || len(fn.Recv.List) == 0 {
		return ""
	}
	return typeName(fn.Recv.List[0].Type)
}

// typeName renders the bare name of a receiver type expression.
func typeName(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.Ident:
		return t.Name
	case *ast.StarExpr:
		return "*" + typeName(t.X)
	case *ast.IndexExpr: // generic receiver with one type parameter
		return typeName(t.X)
	case *ast.IndexListExpr: // generic receiver with multiple type parameters
		return typeName(t.X)
	default:
		return ""
	}
}
