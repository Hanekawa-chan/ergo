// Package resolve maps a Go package specification to the directory holding its
// source files.
//
// A specification is either an import path ("encoding/json", "ergo/internal/
// search") or a directory path ("./internal/search", "."). Resolution is
// delegated to the `go` tool, which already understands modules, the module
// cache, GOROOT, and vendoring — so ergo needs no third-party dependency for it.
package resolve

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"strings"
)

// Package resolves a single Go package spec to its absolute source directory by
// invoking `go list`.
//
// The spec is interpreted relative to workdir (pass "" for the current working
// directory). workdir matters for relative directory specs and for resolving
// third-party import paths within the correct module.
//
// It is an error for the spec to match zero or more than one package; callers
// that want to search a whole module should resolve and search each package
// individually.
func Package(spec, workdir string) (string, error) {
	cmd := exec.Command("go", "list", "-json", "-e", "--", spec)
	cmd.Dir = workdir // empty string means the current working directory

	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if _, ok := err.(*exec.ExitError); ok {
			msg := strings.TrimSpace(stderr.String())
			if msg == "" {
				msg = err.Error()
			}
			return "", fmt.Errorf("go list %q: %s", spec, msg)
		}
		return "", fmt.Errorf("running go list: %w", err)
	}

	// `go list` emits one JSON object per matched package, concatenated (not a
	// JSON array). Decode them in a stream and require exactly one.
	type listPackage struct {
		Dir   string
		Error *struct{ Err string }
	}
	dec := json.NewDecoder(&stdout)
	var dir string
	count := 0
	for {
		var pkg listPackage
		switch err := dec.Decode(&pkg); err {
		case nil:
			// handled below
		case io.EOF:
			switch {
			case count == 0:
				return "", fmt.Errorf("resolve %q: no package found", spec)
			case count > 1:
				return "", fmt.Errorf("resolve %q: matched %d packages, want exactly one", spec, count)
			case dir == "":
				return "", fmt.Errorf("resolve %q: go list reported no directory", spec)
			}
			return dir, nil
		default:
			return "", fmt.Errorf("parsing go list output for %q: %w", spec, err)
		}

		count++
		if pkg.Error != nil {
			return "", fmt.Errorf("resolve %q: %s", spec, pkg.Error.Err)
		}
		dir = pkg.Dir
	}
}
