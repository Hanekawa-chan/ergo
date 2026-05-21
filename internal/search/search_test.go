package search

import (
	"os"
	"path/filepath"
	"testing"
)

// writePkg creates a temporary package directory populated with the given
// files (name -> source) and returns its path.
func writePkg(t *testing.T, files map[string]string) string {
	t.Helper()
	dir := t.TempDir()
	for name, src := range files {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte(src), 0o644); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	return dir
}

func TestFindFunctionPlain(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"a.go": "package p\n\nfunc Target() error { return nil }\n\nfunc Other() {}\n",
	})

	got, err := FindFunction(dir, "Target")
	if err != nil {
		t.Fatalf("FindFunction: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("got %d results, want 1: %+v", len(got), got)
	}
	if got[0].Name != "Target" {
		t.Errorf("Name = %q, want %q", got[0].Name, "Target")
	}
	if got[0].Recv != "" {
		t.Errorf("Recv = %q, want empty", got[0].Recv)
	}
}

func TestFindFunctionMethods(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"a.go": `package p

type Reader struct{}
type Writer struct{}

func (r Reader) Do() {}
func (w *Writer) Do() {}
func Do() {}
`,
	})

	got, err := FindFunction(dir, "Do")
	if err != nil {
		t.Fatalf("FindFunction: %v", err)
	}
	if len(got) != 3 {
		t.Fatalf("got %d results, want 3: %+v", len(got), got)
	}

	recvs := map[string]bool{}
	for _, r := range got {
		recvs[r.Recv] = true
	}
	for _, want := range []string{"", "Reader", "*Writer"} {
		if !recvs[want] {
			t.Errorf("missing result with Recv %q; got %v", want, recvs)
		}
	}
}

func TestFindFunctionGenericReceiver(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"a.go": `package p

type List[T any] struct{}

func (l List[T]) Len() int { return 0 }
`,
	})

	got, err := FindFunction(dir, "Len")
	if err != nil {
		t.Fatalf("FindFunction: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("got %d results, want 1: %+v", len(got), got)
	}
	if got[0].Recv != "List" {
		t.Errorf("Recv = %q, want %q", got[0].Recv, "List")
	}
}

func TestFindFunctionNotFound(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"a.go": "package p\n\nfunc Present() {}\n",
	})

	got, err := FindFunction(dir, "Absent")
	if err != nil {
		t.Fatalf("FindFunction: %v", err)
	}
	if len(got) != 0 {
		t.Errorf("got %d results, want 0: %+v", len(got), got)
	}
}

func TestFindFunctionSkipsTestFiles(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"a.go":      "package p\n\nfunc Target() {}\n",
		"a_test.go": "package p\n\nfunc Target() {}\n",
	})

	got, err := FindFunction(dir, "Target")
	if err != nil {
		t.Fatalf("FindFunction: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("got %d results, want 1 (test file should be ignored): %+v", len(got), got)
	}
}

func TestFindFunctionScansEveryFile(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"a.go": "package p\n\nfunc Target() {}\n",
		"b.go": "package p\n\nfunc Other() {}\n\nfunc Target() {}\n",
	})

	got, err := FindFunction(dir, "Target")
	if err != nil {
		t.Fatalf("FindFunction: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d results, want 2: %+v", len(got), got)
	}
}

func TestFindFunctionMissingDir(t *testing.T) {
	_, err := FindFunction(filepath.Join(t.TempDir(), "does-not-exist"), "X")
	if err == nil {
		t.Fatal("FindFunction: want error for missing directory, got nil")
	}
}

func TestFindFunctionParseError(t *testing.T) {
	dir := writePkg(t, map[string]string{
		"bad.go": "package p\n\nfunc Broken( {}\n",
	})

	_, err := FindFunction(dir, "Broken")
	if err == nil {
		t.Fatal("FindFunction: want error for unparseable file, got nil")
	}
}
