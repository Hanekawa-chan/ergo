// Package analyze determines the concrete error types and values a Go function
// can return.
//
// It loads a package with full type information, builds the SSA form, and
// traces every value that reaches an error-typed return — recursing into
// concrete callees. Interface method calls are deliberately not resolved (see
// CLAUDE.md): they are reported as unresolved rather than over-approximated.
package analyze

import (
	"fmt"
	"go/token"
	"go/types"
	"sort"

	"golang.org/x/tools/go/packages"
	"golang.org/x/tools/go/ssa"
	"golang.org/x/tools/go/ssa/ssautil"
)

// Kind classifies how an error value comes to be.
type Kind int

const (
	// KindSentinel is a package-level error variable, e.g. io.EOF.
	KindSentinel Kind = iota
	// KindType is a concrete named error type, e.g. *os.PathError.
	KindType
	// KindConstructed is an ad-hoc error from errors.New or fmt.Errorf.
	KindConstructed
	// KindUnresolved marks an error whose origin could not be determined,
	// e.g. one returned through an interface method call or a parameter.
	KindUnresolved
)

func (k Kind) String() string {
	switch k {
	case KindSentinel:
		return "sentinel"
	case KindType:
		return "type"
	case KindConstructed:
		return "constructed"
	default:
		return "unresolved"
	}
}

// Finding describes one error a function can return.
type Finding struct {
	Kind    Kind
	Type    string // concrete Go type, e.g. "*os.PathError"
	Name    string // qualified sentinel name, e.g. "io.EOF"; empty otherwise
	Message string // literal message of a constructed error, when constant
	Wrapped bool   // the error wraps another error (fmt.Errorf with %w)
	Reason  string // why a KindUnresolved finding could not be resolved
	Pos     string // file:line:col where the error originates
}

func (f Finding) key() string {
	return fmt.Sprintf("%d|%s|%s|%s|%s", f.Kind, f.Type, f.Name, f.Message, f.Reason)
}

// Function holds the analysis result for a single function or method.
type Function struct {
	Name     string
	Recv     string // receiver type for methods; empty for plain functions
	Pos      string
	Findings []Finding
}

// Analyze loads pkg (an import path or directory path, resolved relative to
// workdir) and analyzes every function or method named name, reporting the
// errors each can return.
func Analyze(pkg, name, workdir string) ([]Function, error) {
	cfg := &packages.Config{
		Mode: packages.NeedName | packages.NeedFiles | packages.NeedCompiledGoFiles |
			packages.NeedImports | packages.NeedDeps | packages.NeedExportFile |
			packages.NeedTypes | packages.NeedSyntax | packages.NeedTypesInfo,
		Dir: workdir,
	}
	pkgs, err := packages.Load(cfg, pkg)
	if err != nil {
		return nil, fmt.Errorf("loading %s: %w", pkg, err)
	}
	if len(pkgs) == 0 {
		return nil, fmt.Errorf("no package matched %q", pkg)
	}
	for _, p := range pkgs {
		for _, e := range p.Errors {
			return nil, fmt.Errorf("loading %s: %s", p.PkgPath, e)
		}
	}

	prog, ssaPkgs := ssautil.AllPackages(pkgs, ssa.BuilderMode(0))
	prog.Build()

	ssaPkg := ssaPkgs[0]
	if ssaPkg == nil {
		return nil, fmt.Errorf("could not build SSA for %q", pkg)
	}

	targets := findTargets(prog, ssaPkg, name)
	if len(targets) == 0 {
		return nil, fmt.Errorf("no function %q in %s", name, pkg)
	}

	a := &analyzer{
		prog: prog,
		memo: map[*ssa.Function][]Finding{},
		busy: map[*ssa.Function]bool{},
	}

	out := make([]Function, 0, len(targets))
	for _, fn := range targets {
		out = append(out, Function{
			Name:     fn.Name(),
			Recv:     receiver(fn),
			Pos:      a.pos(fn.Pos()),
			Findings: dedup(a.analyzeFunc(fn)),
		})
	}
	return out, nil
}

// findTargets returns every non-synthetic function or method named name that is
// defined in pkg.
func findTargets(prog *ssa.Program, pkg *ssa.Package, name string) []*ssa.Function {
	var fns []*ssa.Function
	for fn := range ssautil.AllFunctions(prog) {
		if fn.Pkg == pkg && fn.Synthetic == "" && fn.Name() == name {
			fns = append(fns, fn)
		}
	}
	sort.Slice(fns, func(i, j int) bool { return fns[i].Pos() < fns[j].Pos() })
	return fns
}

// receiver renders a method's receiver type, or "" for a plain function.
func receiver(fn *ssa.Function) string {
	if recv := fn.Signature.Recv(); recv != nil {
		return types.TypeString(recv.Type(), qualifier)
	}
	return ""
}

// qualifier renders package-qualified type names with the short package name.
func qualifier(p *types.Package) string { return p.Name() }

// pos formats a source position, or "" when p is invalid.
func (a *analyzer) pos(p token.Pos) string {
	if !p.IsValid() {
		return ""
	}
	return a.prog.Fset.Position(p).String()
}

// dedup removes duplicate findings and orders them deterministically.
func dedup(in []Finding) []Finding {
	seen := make(map[string]bool, len(in))
	out := make([]Finding, 0, len(in))
	for _, f := range in {
		if k := f.key(); !seen[k] {
			seen[k] = true
			out = append(out, f)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Kind != out[j].Kind {
			return out[i].Kind < out[j].Kind
		}
		return out[i].key() < out[j].key()
	})
	return out
}
