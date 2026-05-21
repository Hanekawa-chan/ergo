package analyze

import (
	"os"
	"path/filepath"
	"testing"
)

// fixture is a self-contained package (stdlib imports only) exercising each
// error pattern the analyzer recognizes.
const fixture = `package testpkg

import (
	"errors"
	"fmt"
	"io"
)

var ErrLocal = errors.New("local sentinel")

type MyError struct{ msg string }

func (e *MyError) Error() string { return e.msg }

func (e *MyError) Fail() error { return ErrLocal }

func ReturnsNil() error { return nil }

func ReturnsConstructed() error { return errors.New("boom") }

func ReturnsFormatted() error { return fmt.Errorf("formatted") }

func ReturnsStdSentinel() error { return io.EOF }

func ReturnsLocalSentinel() error { return ErrLocal }

func ReturnsTyped() error { return &MyError{msg: "typed"} }

func ReturnsWrapped(e error) error { return fmt.Errorf("ctx: %w", e) }

func helper() error { return errors.New("from helper") }

func ReturnsTransitive() error { return helper() }

func ReturnsParam(e error) error { return e }

func ReturnsBranch(cond bool) error {
	if cond {
		return io.EOF
	}
	return ReturnsTyped()
}
`

// loadFixture writes the fixture into a fresh temporary module and returns its
// directory.
func loadFixture(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	write := func(name, content string) {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	write("go.mod", "module testpkg\n\ngo 1.25\n")
	write("errs.go", fixture)
	return dir
}

// findingsFor analyzes the single function named name in the fixture package.
func findingsFor(t *testing.T, dir, name string) []Finding {
	t.Helper()
	fns, err := Analyze(".", name, dir)
	if err != nil {
		t.Fatalf("Analyze(%s): %v", name, err)
	}
	if len(fns) != 1 {
		t.Fatalf("Analyze(%s): got %d functions, want 1", name, len(fns))
	}
	return fns[0].Findings
}

func TestAnalyzeSingleReturn(t *testing.T) {
	dir := loadFixture(t)
	tests := []struct {
		fn    string
		kind  Kind
		check func(Finding) bool
		desc  string
	}{
		{"ReturnsConstructed", KindConstructed, func(f Finding) bool { return f.Message == "boom" && !f.Wrapped }, `message "boom"`},
		{"ReturnsFormatted", KindConstructed, func(f Finding) bool { return f.Message == "formatted" }, `message "formatted"`},
		{"ReturnsStdSentinel", KindSentinel, func(f Finding) bool { return f.Name == "io.EOF" }, "io.EOF"},
		{"ReturnsLocalSentinel", KindSentinel, func(f Finding) bool { return f.Name == "testpkg.ErrLocal" }, "testpkg.ErrLocal"},
		{"ReturnsTyped", KindType, func(f Finding) bool { return f.Type == "*testpkg.MyError" }, "*testpkg.MyError"},
		{"ReturnsWrapped", KindConstructed, func(f Finding) bool { return f.Wrapped }, "wraps another error"},
		{"ReturnsTransitive", KindConstructed, func(f Finding) bool { return f.Message == "from helper" }, `transitive message "from helper"`},
		{"ReturnsParam", KindUnresolved, func(Finding) bool { return true }, "unresolved"},
	}
	for _, tt := range tests {
		t.Run(tt.fn, func(t *testing.T) {
			got := findingsFor(t, dir, tt.fn)
			if len(got) != 1 {
				t.Fatalf("got %d findings, want 1: %+v", len(got), got)
			}
			if got[0].Kind != tt.kind {
				t.Errorf("kind = %s, want %s", got[0].Kind, tt.kind)
			}
			if !tt.check(got[0]) {
				t.Errorf("finding %+v does not satisfy: %s", got[0], tt.desc)
			}
			if got[0].Pos == "" {
				t.Errorf("finding %+v has no source position", got[0])
			}
		})
	}
}

func TestAnalyzeReturnsNil(t *testing.T) {
	got := findingsFor(t, loadFixture(t), "ReturnsNil")
	if len(got) != 0 {
		t.Fatalf("got %d findings, want 0: %+v", len(got), got)
	}
}

func TestAnalyzeBranches(t *testing.T) {
	got := findingsFor(t, loadFixture(t), "ReturnsBranch")
	if len(got) != 2 {
		t.Fatalf("got %d findings, want 2: %+v", len(got), got)
	}
	var sentinel, typed bool
	for _, f := range got {
		switch f.Kind {
		case KindSentinel:
			sentinel = sentinel || f.Name == "io.EOF"
		case KindType:
			typed = typed || f.Type == "*testpkg.MyError"
		}
	}
	if !sentinel || !typed {
		t.Errorf("got %+v, want io.EOF sentinel and *testpkg.MyError type", got)
	}
}

func TestAnalyzeMethod(t *testing.T) {
	fns, err := Analyze(".", "Fail", loadFixture(t))
	if err != nil {
		t.Fatalf("Analyze(Fail): %v", err)
	}
	if len(fns) != 1 {
		t.Fatalf("got %d functions, want 1", len(fns))
	}
	if fns[0].Recv != "*testpkg.MyError" {
		t.Errorf("Recv = %q, want %q", fns[0].Recv, "*testpkg.MyError")
	}
	if len(fns[0].Findings) != 1 || fns[0].Findings[0].Kind != KindSentinel {
		t.Errorf("findings = %+v, want one sentinel", fns[0].Findings)
	}
}

func TestAnalyzeNotFound(t *testing.T) {
	if _, err := Analyze(".", "DoesNotExist", loadFixture(t)); err == nil {
		t.Fatal("Analyze: want error for missing function, got nil")
	}
}
