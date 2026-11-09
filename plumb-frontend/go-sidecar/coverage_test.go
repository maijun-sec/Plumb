package main

// Tests for the constructs whose silent loss was the biggest gap in the Go
// frontend: the package-level declarations, the closures, and the shapes the
// type system decides rather than the spelling.

import (
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"strconv"
	"strings"
	"testing"
)

// canonicalKey reduces a signature to the owner.method/arity form the engine
// matches a call against.
//
// A declaration carries the PAREN form ("owner.name(Params):Result") and a
// call carries the SLASH form ("owner.name/2"); the two describe the same
// method, and the engine canonicalises both before comparing them.
func canonicalKey(signature string) string {
	open := strings.Index(signature, "(")
	if open < 0 {
		return signature
	}
	close := strings.Index(signature[open:], ")")
	if close < 0 {
		return signature
	}
	name := signature[:open]
	params := signature[open+1 : open+close]
	arity := 0
	if strings.TrimSpace(params) != "" {
		arity = len(strings.Split(params, ","))
	}
	return name + "/" + strconv.Itoa(arity)
}

// typeCheckSource type-checks a snippet and returns the parsed file, the
// type information and the type-checked package.
//
// The snippet must not import anything: the test has no export data to
// offer, and a dependency that cannot be resolved would make the test
// measure the importer rather than the translator.
func typeCheckSource(t *testing.T, source string) (*token.FileSet, *ast.File, *types.Info, *types.Package) {
	t.Helper()

	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "sample.go", source,
		parser.ParseComments|parser.SkipObjectResolution)
	if err != nil {
		t.Fatalf("the snippet did not parse: %v", err)
	}

	info := newTypeInfo()
	configuration := &types.Config{
		Error: func(err error) {
			t.Errorf("the snippet did not type-check: %v", err)
		},
	}
	pkg, err := configuration.Check("sample", fset, []*ast.File{file}, info)
	if err != nil {
		t.Fatalf("the snippet did not type-check: %v", err)
	}
	return fset, file, info, pkg
}

// A package-level function's call carries the PACKAGE in the receiver slot.
//
// WHY THIS IS PINNED. The TAC's call convention is "args[0] is the receiver,
// args[1..] are the arguments", and the engine reads every argument index
// against it:
//
//   - the sink check adds ONE to the catalogue's argument index, so a
//     package-level call whose first argument sat at args[0] would be read
//     one argument too far;
//   - the callee summary binds formal i to args[i+1] and falls back to
//     args[i] only when args[i+1] is out of range -- so a one-argument
//     package-level call binds NOTHING, and the taint a callee returns from
//     its parameter never reaches the caller.
//
// The slot holds a CONSTANT, because a static-like call has no receiver
// value that could carry taint.
func TestPackageLevelCallCarriesThePackageInTheReceiverSlot(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() string {
	return "x"
}

func use() {
	_ = produce()
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.produce/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	args := calls[0].Args
	if len(args) != 1 {
		t.Fatalf("the argument count is %d, want exactly 1 (the receiver slot)", len(args))
	}
	if args[0].Kind != "CONST" {
		t.Errorf("args[0] is %q, want the constant that names the package", args[0].Kind)
	}
	if args[0].Value != "sample" {
		t.Errorf("args[0] names %q, want the package path %q", args[0].Value, "sample")
	}
}

// A package-level VARIABLE is initialised, and the `init` function's body is
// lowered.
//
// WHY THIS IS PINNED. A package-level variable and an `init` function have
// no place in the TAC's function model, so without a home for them they are
// DROPPED -- and a dropped initialisation is a dropped taint: the value that
// flows into the variable would leave no trace, and the finding that depends
// on it would be absent while the scan reports success.
func TestPackageLevelVariableAndInitFunctionAreLowered(t *testing.T) {
	fset, file, info, pkg := typeCheckSource(t, `
package sample

var greeting = "hello"

func init() {
	greeting = "world"
}
`)

	loaded := &loadedPackage{
		spec:  &PackageSpec{ImportPath: "sample"},
		pkg:   pkg,
		info:  info,
		files: []parsedSource{{path: "sample.go", file: file}},
	}

	initializer := translatePackageInit(fset, loaded, file)
	if initializer == nil {
		t.Fatal("the package initialiser was not lowered")
	}
	if initializer.Name != "sample.init" {
		t.Errorf("the initialiser is named %q, want %q", initializer.Name, "sample.init")
	}
	if initializer.EnclosingClass != "sample" {
		t.Errorf("the initialiser's scope is %q, want the package path %q",
			initializer.EnclosingClass, "sample")
	}

	// The variable is written under its QUALIFIED name, so that the
	// functions of every file of the package read and write the same
	// TAC variable.
	wrote := false
	for _, instruction := range initializer.Instructions {
		if instruction.Kind == KindMove && instruction.Dest != nil &&
			instruction.Dest.Name == "sample.greeting" {
			wrote = true
		}
	}
	if !wrote {
		t.Errorf("the package variable was not initialised: %+v", initializer.Instructions)
	}

	// And the init function's body is in the same function.
	assigned := false
	for _, instruction := range initializer.Instructions {
		if instruction.Kind == KindMove && instruction.Dest != nil &&
			instruction.Dest.Name == "sample.greeting" &&
			instruction.Lhs1 != nil && instruction.Lhs1.Kind == "CONST" &&
			instruction.Lhs1.Value == "world" {
			assigned = true
		}
	}
	if !assigned {
		t.Errorf("the init function's assignment was not lowered: %+v", initializer.Instructions)
	}
}

// A range over an INTEGER counts, and it does not ask a collection for its
// length.
//
// WHY THIS IS PINNED. `for i := range 10` ranges over a COUNT, not over a
// collection. Lowering it as a collection produced a `len` call on the
// integer -- an instruction that means nothing -- and the loop's bound was
// then an unknown value, so the loop body was unreachable as far as the
// analysis was concerned.
func TestRangeOverAnIntegerCountsInsteadOfMeasuringACollection(t *testing.T) {
	functions := translateSource(t, `
package sample

func use() {
	total := 0
	for i := range 10 {
		total = total + i
	}
	_ = total
}
`)

	use := functionNamed(t, functions, "use")

	for _, instruction := range use.Instructions {
		if instruction.Kind == KindCall && instruction.CalleeSig == "<go>.len/1" {
			t.Errorf("the range asked a collection for its length: %+v", instruction)
		}
	}

	// The bound is the count itself, compared with the index.
	compared := false
	for _, instruction := range use.Instructions {
		if instruction.Kind == KindBinOp && instruction.BinOp == "LT" {
			compared = true
		}
	}
	if !compared {
		t.Errorf("the range did not compare the index with the count: %+v", use.Instructions)
	}
}

// A closure is a function of its own, and a call through the variable that
// holds it reaches the closure's body.
//
// WHY THIS IS PINNED. A function literal has no place in the TAC's function
// model, so before this the literal's WHOLE BODY was invisible: the finding
// inside a handler that the program registers was absent, and the scan
// reported success.
func TestClosureIsItsOwnFunctionAndTheCallReachesIt(t *testing.T) {
	functions := translateSource(t, `
package sample

func use(secret string) {
	handler := func(value string) {
		_ = secret + value
	}
	handler("x")
}
`)

	use := functionNamed(t, functions, "use")

	// The closure is a function of its own.
	closure := functionNamed(t, functions, "sample.closure@1")

	// The captured variable is the closure's LEADING parameter, so the
	// taint of the outer variable reaches the closure's body.
	if len(closure.Params) != 2 {
		t.Fatalf("the closure's parameter count is %d, want 2 (the capture and its own)",
			len(closure.Params))
	}
	if closure.Params[0].Name != "secret" {
		t.Errorf("the closure's first parameter is %q, want the captured %q",
			closure.Params[0].Name, "secret")
	}

	// The call site reaches it, and passes the capture.
	calls := callsTo(use, canonicalKey(closure.Signature))
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1: %+v", len(calls), use.Instructions)
	}
	args := calls[0].Args
	if len(args) != 3 {
		t.Fatalf("the argument count is %d, want 3 (the receiver, the capture, the argument)",
			len(args))
	}
	if args[1].Name != "secret" {
		t.Errorf("args[1] is %q, want the captured value %q", args[1].Name, "secret")
	}
	if args[2].Kind != "CONST" || args[2].Value != "x" {
		t.Errorf("args[2] is %+v, want the argument the source wrote", args[2])
	}
}

// `close` is a builtin, and the resource it releases is visible to the
// analysis.
//
// WHY THIS IS PINNED. `close` is in Go's builtin list, but it was missing
// from the translator's, so every `close(ch)` was reported as unlowered and
// the call disappeared: a checker that reasons about a channel's lifetime
// never saw the close at all.
func TestCloseIsLowered(t *testing.T) {
	functions := translateSource(t, `
package sample

func use(ch chan int) {
	close(ch)
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "<go>.close/1")
	if len(calls) != 1 {
		t.Fatalf("the close count is %d, want exactly 1: %+v", len(calls), use.Instructions)
	}
	if hasProblemOfKind(use, ProblemUnhandledNode) {
		t.Errorf("the close was reported as unlowered: %+v", use.Problems)
	}
}

// `make(chan T, n)` carries the size, and `make([]T, n, m)` says that the
// capacity was dropped.
//
// WHY THIS IS PINNED. The size argument of a channel is a value the program
// computed. Dropping it left a call with no arguments at all, so a checker
// that reasons about the channel's buffer size could not see it.
func TestMakeCarriesItsSizeArgument(t *testing.T) {
	functions := translateSource(t, `
package sample

func use() {
	ch := make(chan int, 4)
	_ = ch
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "<go>.make/1")
	if len(calls) != 1 {
		t.Fatalf("the make count is %d, want exactly 1: %+v", len(calls), use.Instructions)
	}
	if len(calls[0].Args) != 1 {
		t.Fatalf("the argument count is %d, want 1 (the size)", len(calls[0].Args))
	}
	if calls[0].Args[0].Kind != "CONST" || calls[0].Args[0].Value != "4" {
		t.Errorf("the size argument is %+v, want the constant 4", calls[0].Args[0])
	}
}

// A map range reads the key and the value out of the iterator's entry.
//
// WHY THIS IS PINNED. The key and the value used to be bound to unknown
// values, which says "the analysis does not know". They come from the
// iterator's entry, and saying so is a claim the TAC can carry.
func TestMapRangeReadsTheKeyAndTheValueOutOfTheEntry(t *testing.T) {
	functions := translateSource(t, `
package sample

func use(m map[string]int) {
	for key, value := range m {
		_ = key
		_ = value
	}
}
`)

	use := functionNamed(t, functions, "use")

	if len(callsTo(use, "<go>.mapKey/1")) != 1 {
		t.Errorf("the map range did not read the key: %+v", use.Instructions)
	}
	if len(callsTo(use, "<go>.mapValue/1")) != 1 {
		t.Errorf("the map range did not read the value: %+v", use.Instructions)
	}
	for _, instruction := range use.Instructions {
		if instruction.Kind == KindHavoc {
			t.Errorf("the map range bound an unknown value: %+v", instruction)
		}
	}
}
