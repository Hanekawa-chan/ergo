package resolve

import (
	"os"
	"path/filepath"
	"testing"
)

// These tests shell out to the `go` tool, which is always present when the
// suite itself is run by `go test`.

func TestPackageStdlib(t *testing.T) {
	dir, err := Package("errors", "")
	if err != nil {
		t.Fatalf("Package(errors): %v", err)
	}
	if filepath.Base(dir) != "errors" {
		t.Errorf("dir = %q, want base %q", dir, "errors")
	}
	if _, err := os.Stat(filepath.Join(dir, "errors.go")); err != nil {
		t.Errorf("resolved dir %q does not contain errors.go: %v", dir, err)
	}
}

func TestPackageImportPathInModule(t *testing.T) {
	// The test runs inside module "ergo", so its own packages resolve by
	// import path regardless of the working directory within the module.
	dir, err := Package("ergo/internal/search", "")
	if err != nil {
		t.Fatalf("Package(ergo/internal/search): %v", err)
	}
	if filepath.Base(dir) != "search" {
		t.Errorf("dir = %q, want base %q", dir, "search")
	}
	if _, err := os.Stat(filepath.Join(dir, "search.go")); err != nil {
		t.Errorf("resolved dir %q does not contain search.go: %v", dir, err)
	}
}

func TestPackageDirectorySpec(t *testing.T) {
	// A directory spec is resolved relative to workdir.
	dir, err := Package(".", filepath.FromSlash("."))
	if err != nil {
		t.Fatalf("Package(.): %v", err)
	}
	if filepath.Base(dir) != "resolve" {
		t.Errorf("dir = %q, want base %q", dir, "resolve")
	}
}

func TestPackageNotFound(t *testing.T) {
	_, err := Package("ergo/no/such/package", "")
	if err == nil {
		t.Fatal("Package: want error for nonexistent import path, got nil")
	}
}

func TestPackageMultipleMatches(t *testing.T) {
	_, err := Package("ergo/...", "")
	if err == nil {
		t.Fatal("Package: want error when a pattern matches multiple packages, got nil")
	}
}
