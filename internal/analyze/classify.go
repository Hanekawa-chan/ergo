package analyze

import (
	"fmt"
	"go/constant"
	"go/token"
	"go/types"
	"strings"

	"golang.org/x/tools/go/ssa"
)

// analyzer carries the SSA program and the memo tables used while tracing
// errors across function boundaries.
type analyzer struct {
	prog *ssa.Program
	memo map[*ssa.Function][]Finding // completed results, keyed by function
	busy map[*ssa.Function]bool      // functions currently on the recursion stack
}

// errorIface is the method set of the predeclared error interface.
var errorIface = types.Universe.Lookup("error").Type().Underlying().(*types.Interface)

// analyzeFunc returns every error fn can return, recursing into concrete
// callees. Results are memoized; a recursion cycle contributes nothing, which
// can slightly under-approximate mutually recursive functions.
func (a *analyzer) analyzeFunc(fn *ssa.Function) []Finding {
	if done, ok := a.memo[fn]; ok {
		return done
	}
	if a.busy[fn] {
		return nil // cycle: avoid infinite recursion
	}
	a.busy[fn] = true
	defer delete(a.busy, fn)

	var findings []Finding
	if fn.Blocks == nil {
		// No Go source body: assembly, or a function known only by signature.
		findings = []Finding{{
			Kind:   KindUnresolved,
			Reason: "function has no analyzable Go body",
			Pos:    a.pos(fn.Pos()),
		}}
		a.memo[fn] = findings
		return findings
	}

	results := fn.Signature.Results()
	for _, b := range fn.Blocks {
		if len(b.Instrs) == 0 {
			continue
		}
		ret, ok := b.Instrs[len(b.Instrs)-1].(*ssa.Return)
		if !ok {
			continue
		}
		for i, res := range ret.Results {
			if i >= results.Len() || !types.Implements(results.At(i).Type(), errorIface) {
				continue
			}
			findings = append(findings, a.classify(res, map[ssa.Value]bool{})...)
		}
	}

	findings = dedup(findings)
	a.memo[fn] = findings
	return findings
}

// classify traces a single error-typed SSA value to the concrete errors it can
// hold. seen guards against cyclic SSA values such as phi nodes.
func (a *analyzer) classify(v ssa.Value, seen map[ssa.Value]bool) []Finding {
	if v == nil || seen[v] {
		return nil
	}
	seen[v] = true

	switch v := v.(type) {
	case *ssa.Const:
		// A constant of error type can only be nil — no error returned.
		return nil

	case *ssa.MakeInterface:
		// A concrete value boxed into the error interface.
		return a.classifyConcrete(v.X, v)

	case *ssa.Phi:
		var out []Finding
		for _, edge := range v.Edges {
			out = append(out, a.classify(edge, seen)...)
		}
		return out

	case *ssa.Call:
		return a.classifyCall(v, &v.Call)

	case *ssa.Extract:
		// One component of a multi-value result, typically `v, err := f()`.
		if call, ok := v.Tuple.(*ssa.Call); ok {
			return a.classifyCall(call, &call.Call)
		}
		return []Finding{a.unresolved(v, "error from a non-call multi-value expression")}

	case *ssa.Global:
		// A package-level variable used directly as an error.
		return []Finding{a.sentinel(v)}

	case *ssa.UnOp:
		if v.Op == token.MUL {
			return a.classify(v.X, seen) // load through a pointer
		}
		return []Finding{a.unresolved(v, "error from an unsupported operation")}

	case *ssa.ChangeInterface:
		return a.classify(v.X, seen)

	case *ssa.TypeAssert:
		return []Finding{a.typed(v.AssertedType, v.X, v)}

	case *ssa.Parameter:
		return []Finding{a.unresolved(v, "error received as a parameter")}

	default:
		return []Finding{a.unresolved(v, fmt.Sprintf("error from %T", v))}
	}
}

// classifyConcrete classifies x, a concrete (non-interface) value being used as
// an error. at is the boxing site, used as a fallback when x has no position.
func (a *analyzer) classifyConcrete(x ssa.Value, at ssa.Value) []Finding {
	switch x := x.(type) {
	case *ssa.Global:
		return []Finding{a.sentinel(x)}
	case *ssa.UnOp:
		if x.Op == token.MUL {
			return a.classifyConcrete(x.X, at)
		}
	}
	return []Finding{a.typed(x.Type(), x, at)}
}

// classifyCall classifies the error produced by a call. at locates the call.
func (a *analyzer) classifyCall(at ssa.Value, call *ssa.CallCommon) []Finding {
	if call.IsInvoke() {
		// Interface method call — out of scope by design.
		return []Finding{a.unresolved(at,
			"error from an interface method call ("+call.Method.Name()+")")}
	}
	callee := call.StaticCallee()
	if callee == nil {
		return []Finding{a.unresolved(at, "error from a dynamic function value")}
	}

	switch calleeName(callee) {
	case "errors.New":
		return []Finding{a.constructed(at, stringArg(call.Args, 0), false)}
	case "fmt.Errorf":
		msg := stringArg(call.Args, 0)
		return []Finding{a.constructed(at, msg, strings.Contains(msg, "%w"))}
	}

	// A concrete callee: recurse into it.
	return a.analyzeFunc(callee)
}

// calleeName is the package-qualified name of fn, e.g. "errors.New".
func calleeName(fn *ssa.Function) string {
	if fn.Pkg != nil && fn.Pkg.Pkg != nil {
		return fn.Pkg.Pkg.Path() + "." + fn.Name()
	}
	return fn.Name()
}

// stringArg returns the i-th argument as a string when it is a constant.
func stringArg(args []ssa.Value, i int) string {
	if i >= len(args) {
		return ""
	}
	if c, ok := args[i].(*ssa.Const); ok && c.Value != nil && c.Value.Kind() == constant.String {
		return constant.StringVal(c.Value)
	}
	return ""
}

func (a *analyzer) sentinel(g *ssa.Global) Finding {
	name := g.Name()
	if g.Pkg != nil && g.Pkg.Pkg != nil {
		name = g.Pkg.Pkg.Path() + "." + g.Name()
	}
	t := g.Type()
	if p, ok := t.(*types.Pointer); ok {
		t = p.Elem() // a global's type is the address of the variable
	}
	return Finding{
		Kind: KindSentinel,
		Name: name,
		Type: types.TypeString(t, qualifier),
		Pos:  a.pos(g.Pos()),
	}
}

func (a *analyzer) typed(t types.Type, at ...ssa.Value) Finding {
	return Finding{
		Kind: KindType,
		Type: types.TypeString(t, qualifier),
		Pos:  a.valuePos(at...),
	}
}

// valuePos returns the position of the first value that has a valid one.
func (a *analyzer) valuePos(vals ...ssa.Value) string {
	for _, v := range vals {
		if v != nil && v.Pos().IsValid() {
			return a.pos(v.Pos())
		}
	}
	return ""
}

func (a *analyzer) constructed(at ssa.Value, msg string, wrapped bool) Finding {
	return Finding{
		Kind:    KindConstructed,
		Message: msg,
		Wrapped: wrapped,
		Pos:     a.pos(at.Pos()),
	}
}

func (a *analyzer) unresolved(at ssa.Value, reason string) Finding {
	f := Finding{Kind: KindUnresolved, Reason: reason}
	if at != nil {
		f.Pos = a.pos(at.Pos())
	}
	return f
}
