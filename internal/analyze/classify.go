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

	if !types.IsInterface(v.Type()) {
		if isPointerToInterface(v.Type()) {
			// The address of an error cell — follow what is stored into it.
			return a.classifyContents(v, seen)
		}
		// A concrete (non-interface) error value.
		return a.classifyConcrete(v, v, seen)
	}

	switch v := v.(type) {
	case *ssa.Const:
		// A constant of error type can only be nil — no error returned.
		return nil

	case *ssa.MakeInterface:
		// A concrete value boxed into the error interface.
		return a.classifyConcrete(v.X, v, seen)

	case *ssa.Phi:
		var out []Finding
		for _, edge := range v.Edges {
			out = append(out, a.classify(edge, seen)...)
		}
		return out

	case *ssa.Call:
		return a.classifyCall(v, &v.Call, seen)

	case *ssa.Extract:
		// One component of a multi-value result, typically `v, err := f()`.
		if call, ok := v.Tuple.(*ssa.Call); ok {
			return a.classifyCall(call, &call.Call, seen)
		}
		return []Finding{a.unresolved(v, "error from a non-call multi-value expression")}

	case *ssa.Global:
		// A package-level variable used directly as an error.
		return []Finding{a.sentinel(v)}

	case *ssa.UnOp:
		if v.Op == token.MUL {
			// Load through a pointer — follow what is stored into the cell.
			return a.classifyContents(v.X, seen)
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
func (a *analyzer) classifyConcrete(x ssa.Value, at ssa.Value, seen map[ssa.Value]bool) []Finding {
	switch x := x.(type) {
	case *ssa.Global:
		return []Finding{a.sentinel(x)}
	case *ssa.UnOp:
		if x.Op == token.MUL {
			return a.classifyContents(x.X, seen)
		}
	}
	return []Finding{a.typed(x.Type(), x, at)}
}

// classifyContents classifies the error held in a memory cell, by examining
// every value stored through the pointer ptr.
func (a *analyzer) classifyContents(ptr ssa.Value, seen map[ssa.Value]bool) []Finding {
	if ptr == nil || seen[ptr] {
		return nil
	}
	seen[ptr] = true

	// A pointer to a package-level variable is a sentinel error, e.g. io.EOF.
	if g, ok := ptr.(*ssa.Global); ok {
		return []Finding{a.sentinel(g)}
	}

	refs := ptr.Referrers()
	if refs == nil {
		return []Finding{a.unresolved(ptr, "error from an opaque memory cell")}
	}
	var out []Finding
	stored := false
	for _, instr := range *refs {
		if st, ok := instr.(*ssa.Store); ok && st.Addr == ptr {
			stored = true
			out = append(out, a.classify(st.Val, seen)...)
		}
	}
	if !stored {
		return []Finding{a.unresolved(ptr, "error from a cell with no visible store")}
	}
	return out
}

// isPointerToInterface reports whether t is a pointer whose element is an
// interface — i.e. the address of a cell that holds an interface value.
func isPointerToInterface(t types.Type) bool {
	p, ok := t.Underlying().(*types.Pointer)
	return ok && types.IsInterface(p.Elem())
}

// classifyCall classifies the error produced by a call. at locates the call;
// seen guards the recursion into errors wrapped by fmt.Errorf.
func (a *analyzer) classifyCall(at ssa.Value, call *ssa.CallCommon, seen map[ssa.Value]bool) []Finding {
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
		wrapIdx, reliable := wrapArgIndices(msg)
		out := []Finding{a.constructed(at, msg, len(wrapIdx) > 0)}
		return append(out, a.classifyWrapped(call, wrapIdx, reliable, seen)...)
	}

	// A concrete callee: recurse into it.
	return a.analyzeFunc(callee)
}

// classifyWrapped classifies the error values wrapped by a fmt.Errorf %w verb.
// wrapIdx holds the variadic-argument indices targeted by %w; when reliable is
// false those indices are untrustworthy and every error argument is classified.
func (a *analyzer) classifyWrapped(call *ssa.CallCommon, wrapIdx []int, reliable bool, seen map[ssa.Value]bool) []Finding {
	if len(wrapIdx) == 0 || len(call.Args) < 2 {
		return nil
	}
	elems, ok := variadicElements(call.Args[1])
	if !ok {
		return nil // variadic arguments were not built inline; cannot recover
	}

	var targets []ssa.Value
	if reliable {
		for _, i := range wrapIdx {
			if v := elems[i]; v != nil {
				targets = append(targets, v)
			}
		}
	} else {
		for _, v := range elems {
			targets = append(targets, v)
		}
	}

	var out []Finding
	for _, v := range targets {
		if u := unbox(v); u != nil && types.Implements(u.Type(), errorIface) {
			out = append(out, a.classify(v, seen)...)
		}
	}
	return out
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

// wrapArgIndices parses a printf-style format string and returns the 0-based
// variadic-argument indices consumed by %w verbs. reliable is false when the
// format uses explicit argument indices (e.g. %[2]w), which break sequential
// argument counting; callers should then treat every error argument as wrapped.
func wrapArgIndices(format string) (idx []int, reliable bool) {
	reliable = true
	arg, i := 0, 0

	skipIndex := func() { // an explicit "[n]" argument index
		reliable = false
		for i < len(format) && format[i] != ']' {
			i++
		}
		if i < len(format) {
			i++
		}
	}
	skipWidth := func() { // a width or precision: digits, or '*' (consumes an arg)
		if i < len(format) && format[i] == '*' {
			arg++
			i++
			return
		}
		for i < len(format) && format[i] >= '0' && format[i] <= '9' {
			i++
		}
	}

	for i < len(format) {
		if format[i] != '%' {
			i++
			continue
		}
		i++ // consume '%'
		if i >= len(format) {
			break
		}
		if format[i] == '%' { // escaped percent: consumes no argument
			i++
			continue
		}
		for i < len(format) && strings.IndexByte("+-# 0", format[i]) >= 0 {
			i++ // flags
		}
		if i < len(format) && format[i] == '[' {
			skipIndex()
		}
		skipWidth()
		if i < len(format) && format[i] == '.' {
			i++
			skipWidth()
		}
		if i < len(format) && format[i] == '[' {
			skipIndex()
		}
		if i >= len(format) {
			break
		}
		if format[i] == 'w' {
			idx = append(idx, arg)
		}
		i++ // consume the verb
		arg++
	}
	return idx, reliable
}

// variadicElements recovers the elements of an inline-constructed variadic
// slice argument, keyed by position. ok is false when the slice was not built
// inline (e.g. the call spread an existing slice with `args...`).
func variadicElements(slice ssa.Value) (elems map[int]ssa.Value, ok bool) {
	sl, ok := slice.(*ssa.Slice)
	if !ok {
		return nil, false
	}
	alloc, ok := sl.X.(*ssa.Alloc)
	if !ok {
		return nil, false
	}
	elems = make(map[int]ssa.Value)
	for _, instr := range *alloc.Referrers() {
		ia, ok := instr.(*ssa.IndexAddr)
		if !ok || ia.X != ssa.Value(alloc) {
			continue
		}
		ci, ok := ia.Index.(*ssa.Const)
		if !ok || ci.Value == nil {
			continue
		}
		pos, exact := constant.Int64Val(ci.Value)
		if !exact {
			continue
		}
		for _, use := range *ia.Referrers() {
			if st, ok := use.(*ssa.Store); ok && st.Addr == ssa.Value(ia) {
				elems[int(pos)] = st.Val
			}
		}
	}
	return elems, true
}

// unbox strips interface-boxing operations to reach the underlying value.
func unbox(v ssa.Value) ssa.Value {
	for {
		switch x := v.(type) {
		case *ssa.MakeInterface:
			return x.X
		case *ssa.ChangeInterface:
			v = x.X
		default:
			return v
		}
	}
}
