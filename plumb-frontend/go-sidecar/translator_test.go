package main

// Tests for the Go-specific lowering decisions: the multi-value return, the
// defer side table, and the goroutine marker.

import (
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"testing"
)

// translateSource type-checks a source snippet and translates every function
// in it.
//
// The snippet must not import anything: the test has no export data to
// offer, and a dependency that cannot be resolved would make the test
// measure the importer rather than the translator.
func translateSource(t *testing.T, source string) []Function {
	t.Helper()
	return translateSourceWithVersion(t, source, "")
}

// translateSourceWithVersion type-checks a source snippet and translates every
// function in it, as a package built with a given Go version.
//
// The version is a build fact, and it decides the scope of a range variable:
// before Go 1.22 the loop variable was one variable shared by every
// iteration, and since Go 1.22 every iteration has its own.
func translateSourceWithVersion(t *testing.T, source string, version string) []Function {
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

	loaded := &loadedPackage{
		spec:  &PackageSpec{ImportPath: "sample", GoVersion: version},
		pkg:   pkg,
		info:  info,
		files: []parsedSource{{path: "sample.go", file: file}},
	}

	functions := []Function{}
	index := buildTypeIndex([]*loadedPackage{loaded})
	for _, declaration := range file.Decls {
		function, ok := declaration.(*ast.FuncDecl)
		if !ok || function.Body == nil {
			continue
		}
		functions = append(functions, translateFunction(fset, loaded, function, index)...)
	}
	return functions
}

// functionNamed returns the translated function with a name.
func functionNamed(t *testing.T, functions []Function, name string) Function {
	t.Helper()
	for _, function := range functions {
		if function.Name == name {
			return function
		}
	}
	t.Fatalf("the function %q was not translated", name)
	return Function{}
}

// callsTo returns the instructions that call a callee.
func callsTo(function Function, signature string) []Instruction {
	found := []Instruction{}
	for _, instruction := range function.Instructions {
		if instruction.Kind == KindCall && instruction.CalleeSig == signature {
			found = append(found, instruction)
		}
	}
	return found
}

// hasProblemOfKind reports whether a function reported a problem of a kind.
func hasProblemOfKind(function Function, kind string) bool {
	for _, problem := range function.Problems {
		if problem.Kind == kind {
			return true
		}
	}
	return false
}

// TestMultiValueCallBindsEveryResult pins the multi-value lowering.
//
// IT USED TO PIN THE OPPOSITE. The test asserted that the second result of
// a two-result call became an unknown value, that the function reported a
// lowering problem, and that its precision was lowered. All three were the
// old behaviour, and the design replaced them: a call's results are carried
// in the instruction's RESULT DESTS, so the error result is BOUND rather
// than unknown, and nothing is lost. The test was rewritten, not deleted:
// it still asserts that the first result is the call's destination and that
// no result is left as an unknown value.
func TestMultiValueCallBindsEveryResult(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() (int, error) {
	return 0, nil
}

func use() {
	value, err := produce()
	_ = value
	_ = err
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.produce/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	if calls[0].Dest == nil || calls[0].Dest.Name != "value" {
		t.Errorf("the call's destination is %+v, want the variable 'value'", calls[0].Dest)
	}

	// The error result is BOUND, not an unknown value: it is the call's
	// second result.
	if len(calls[0].ResultDests) != 2 {
		t.Fatalf("the call carries %d results, want 2", len(calls[0].ResultDests))
	}
	if calls[0].ResultDests[1] == nil || calls[0].ResultDests[1].Name != "err" {
		t.Errorf("the second result is %+v, want the variable 'err'", calls[0].ResultDests[1])
	}

	// No result is left as an unknown value.
	for _, instruction := range use.Instructions {
		if instruction.Kind == KindHavoc {
			t.Errorf("a result was bound to an unknown value: %+v", instruction)
		}
	}
	if hasProblemOfKind(use, ProblemSourceLowered) {
		t.Errorf("the function reported a lowering problem: %+v", use.Problems)
	}
	if use.Precision != PrecisionFull {
		t.Errorf("the precision is %q, want %q", use.Precision, PrecisionFull)
	}
}

func TestMultiValueCallWithABlankIdentifierIsNotReported(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() (int, error) {
	return 0, nil
}

func use() {
	value, _ := produce()
	_ = value
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.produce/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	// The blank identifier discards the error on purpose, so nothing is
	// lost and nothing is reported.
	for _, instruction := range use.Instructions {
		if instruction.Kind == KindHavoc {
			t.Errorf("the blank result was bound to an unknown value: %+v", instruction)
		}
	}
	if hasProblemOfKind(use, ProblemSourceLowered) {
		t.Errorf("the function reported a lowering problem: %+v", use.Problems)
	}
}

func TestReturnWithNilErrorIsAReturn(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() (int, error) {
	return 7, nil
}
`)

	produce := functionNamed(t, functions, "produce")

	returns := 0
	for _, instruction := range produce.Instructions {
		if instruction.Kind == KindReturn {
			returns++
		}
		if instruction.Kind == KindThrow {
			t.Errorf("the nil error was lowered to a throw: %+v", instruction)
		}
	}
	if returns != 1 {
		t.Errorf("the return count is %d, want exactly 1", returns)
	}
	if produce.Precision != PrecisionFull {
		t.Errorf("the precision is %q, want %q", produce.Precision, PrecisionFull)
	}
}

func TestReturnWithAnErrorIsAThrow(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() (int, error) {
	return 0, errFailed
}

var errFailed = errFailedType{}

type errFailedType struct{}

func (errFailedType) Error() string {
	return "failed"
}
`)

	produce := functionNamed(t, functions, "produce")

	throws := 0
	for _, instruction := range produce.Instructions {
		if instruction.Kind == KindThrow {
			throws++
		}
	}
	if throws != 1 {
		t.Errorf("the throw count is %d, want exactly 1", throws)
	}
}

// TestTheErrorExitHandsBackItsResults pins the fix for the lost error.
//
// A Go function that returns (T, error) hands back TWO values, and the
// caller binds them to two variables: `v, err := f()`. When the error exit
// is lowered to a THROW and the THROW names no results, the second result
// goes NOWHERE -- the caller's `err` is a variable nothing writes, and a
// variable nothing writes is indistinguishable from a clean one.
//
// So a callee that put attacker-controlled text into its error result lost
// that text at the call boundary, and a sink that consumed the error was
// never reported. The THROW now carries the results.
//
// The results ride on the THROW rather than on a separate RETURN because
// the function does NOT return normally on this path: it exits
// exceptionally. A second instruction would say "this function also
// returns", which is false.
//
// The EXCEPTIONAL EXIT must survive this: the THROW is still there, it
// still carries the thrown value, and it still carries the type that a
// handler is matched against. That is what the second half of this test
// checks.
func TestTheErrorExitHandsBackItsResults(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() (int, error) {
	return 0, errFailed
}

var errFailed = errFailedType{}

type errFailedType struct{}

func (errFailedType) Error() string {
	return "failed"
}
`)

	produce := functionNamed(t, functions, "produce")

	throws := throwsIn(produce)
	if len(throws) != 1 {
		t.Fatalf("the throw count is %d, want exactly 1", len(throws))
	}
	throw := throws[0]

	// The THROW hands back BOTH results: the value first, the error second.
	//
	// The order is the meaning. A list that started at the error would
	// shift every slot to the left, and the engine would read the error
	// as the value.
	if len(throw.ResultDests) != 2 {
		t.Fatalf("the throw carries %d results, want 2", len(throw.ResultDests))
	}

	// The second result is the ERROR, and it is the same value the throw
	// raises: one value plays both roles.
	if throw.ResultDests[1] == nil {
		t.Fatalf("the second result is not bound: %+v", throw)
	}
	if throw.Lhs1 == nil || throw.Lhs1.Name != throw.ResultDests[1].Name {
		t.Errorf("the thrown value is %+v, want the error result %+v",
			throw.Lhs1, throw.ResultDests[1])
	}

	// The EXCEPTIONAL EXIT is unchanged: the type is still there, so a
	// handler is still matched against it.
	if throw.CtorType == nil {
		t.Errorf("the throw does not name the type it raises: %+v", throw)
	}

	// Nothing was lost, so nothing is reported and the function is still
	// fully understood.
	if hasProblemOfKind(produce, ProblemSourceLowered) {
		t.Errorf("the function reported a lowering problem: %+v", produce.Problems)
	}
	if produce.Precision != PrecisionFull {
		t.Errorf("the precision is %q, want %q", produce.Precision, PrecisionFull)
	}
}

func TestFunctionWithTwoMeaningfulResultsIsDegraded(t *testing.T) {
	functions := translateSource(t, `
package sample

func pair() (int, string) {
	return 1, "one"
}
`)

	pair := functionNamed(t, functions, "pair")

	if !hasProblemOfKind(pair, ProblemSourceLowered) {
		t.Errorf("the function did not report a lowering problem: %+v", pair.Problems)
	}
	if pair.Precision != PrecisionLowered {
		t.Errorf("the precision is %q, want %q", pair.Precision, PrecisionLowered)
	}
	// Only the first result is lowered.
	returns := 0
	for _, instruction := range pair.Instructions {
		if instruction.Kind == KindReturn {
			returns++
		}
	}
	if returns != 1 {
		t.Errorf("the return count is %d, want exactly 1", returns)
	}
}

func TestDeferEmitsTheCallAndRecordsItInTheSideTable(t *testing.T) {
	functions := translateSource(t, `
package sample

type File struct{}

func (f *File) Close() error {
	return nil
}

func use(file *File) {
	defer file.Close()
}
`)

	use := functionNamed(t, functions, "use")

	// The call IS in the instruction stream. A deferred call that is only
	// in the side table would leave the taint path invisible to the engine:
	// the finding would be absent while the scan reported success.
	calls := callsTo(use, "sample.File.Close/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	if len(calls[0].Args) != 1 || calls[0].Args[0].Name != "file" {
		t.Errorf("the call's arguments are %+v, want the receiver", calls[0].Args)
	}

	// And the side table records that the call really runs at the exit.
	if len(use.DeferredCalls) != 1 {
		t.Fatalf("the deferred-call count is %d, want exactly 1", len(use.DeferredCalls))
	}
	deferred := use.DeferredCalls[0]
	if deferred.CalleeSig != "sample.File.Close/0" {
		t.Errorf("the deferred callee is %q, want %q", deferred.CalleeSig, "sample.File.Close/0")
	}
	if len(deferred.Args) != 1 {
		t.Fatalf("the deferred argument count is %d, want exactly 1", len(deferred.Args))
	}
	if deferred.Args[0].Name != "file" {
		t.Errorf("the deferred argument is %q, want the receiver %q", deferred.Args[0].Name, "file")
	}
	if deferred.Line < 0 || deferred.Column < 0 {
		t.Errorf("the deferred call has no source position: line %d, column %d",
			deferred.Line, deferred.Column)
	}

	// And the function says that the approximation was made.
	if use.Precision != PrecisionLowered {
		t.Errorf("the precision is %q, want %q", use.Precision, PrecisionLowered)
	}
	// The FACT -- that the call runs at the exit -- travels in the side
	// table, and the Java side reports it from there. The sidecar only
	// marks the function as not fully understood, so the fact is said
	// once and not twice.
	if hasProblemOfKind(use, ProblemSourceLowered) {
		t.Errorf("the function reported the defer as a lowering problem: %+v", use.Problems)
	}
}

func TestGoStatementIsACallAndMarksTheFunction(t *testing.T) {
	functions := translateSource(t, `
package sample

func work() {
}

func use() {
	go work()
}
`)

	use := functionNamed(t, functions, "use")

	if !use.StartsGoroutine {
		t.Error("the function did not report that it starts a goroutine")
	}
	calls := callsTo(use, "sample.work/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}

	// The TAC has no concurrency model, so the approximation is recorded.
	if use.Precision != PrecisionLowered {
		t.Errorf("the precision is %q, want %q", use.Precision, PrecisionLowered)
	}
	// The FACT -- that the function starts a goroutine -- travels in the
	// wire's own flag, and the Java side reports it from there.
	if hasProblemOfKind(use, ProblemSourceLowered) {
		t.Errorf("the function reported the goroutine as a lowering problem: %+v", use.Problems)
	}

	work := functionNamed(t, functions, "work")
	if work.StartsGoroutine {
		t.Error("a function that does not start a goroutine reported that it does")
	}
	if work.Precision != PrecisionFull {
		t.Errorf("a function without a goroutine has the precision %q, want %q",
			work.Precision, PrecisionFull)
	}
}

func TestMethodCallPutsTheReceiverFirst(t *testing.T) {
	functions := translateSource(t, `
package sample

type File struct{}

func (f *File) Write(data []byte) (int, error) {
	return 0, nil
}

func use(file *File, data []byte) {
	file.Write(data)
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.File.Write/1")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	args := calls[0].Args
	if len(args) != 2 {
		t.Fatalf("the argument count is %d, want exactly 2", len(args))
	}
	// args[0] is the receiver. This is the TAC's call convention, and the
	// engine reads a sink's argument index against it.
	if args[0].Name != "file" {
		t.Errorf("args[0] is %q, want the receiver %q", args[0].Name, "file")
	}
	if args[1].Name != "data" {
		t.Errorf("args[1] is %q, want the argument %q", args[1].Name, "data")
	}
}

// A package-level function carries the PACKAGE as its enclosing scope.
//
// WHY THIS IS PINNED. The TAC's store indexes a function under
// "enclosingClass :: signature". A function with no enclosing scope is not
// indexed at all, and the call graph is built over that index -- so a
// language whose functions have no enclosing scope produces a call graph
// with no edges for it, SILENTLY. The finding that depends on a call into
// another package is then missing while the scan reports success.
//
// The enclosing scope is the FULL package path, not its short name: the
// owner convention compares owners by their last dot-separated segment, and
// the call site sees the qualified owner.
func TestPackageLevelFunctionCarriesThePackageAsItsEnclosingScope(t *testing.T) {
	functions := translateSource(t, `package sample

func helper(value int) int {
	return value + 1
}
`)

	helper := functionNamed(t, functions, "helper")

	if helper.EnclosingClass != "sample" {
		t.Errorf("the enclosing scope is %q, want the package path %q", helper.EnclosingClass, "sample")
	}
}

// A method carries the RECEIVER TYPE as its enclosing scope, and the same
// rule applies: the qualified name, so the owner convention can match it
// against the qualified owner a call site names.
func TestMethodCarriesTheReceiverTypeAsItsEnclosingScope(t *testing.T) {
	functions := translateSource(t, `package sample

type File struct {
	data string
}

func (file *File) Write(data string) {
	file.data = data
}
`)

	write := functionNamed(t, functions, "Write")

	if write.EnclosingClass != "sample.File" {
		t.Errorf("the enclosing scope is %q, want the receiver type %q", write.EnclosingClass, "sample.File")
	}
}

// A deferred call runs when the function returns, not where it is written. The
// instruction stream says "here", so the instruction carries the marker that
// says otherwise.
func TestTheDeferredCallCarriesTheDeferredEffect(t *testing.T) {
	functions := translateSource(t, `
package sample

type File struct{}

func (f *File) Close() error {
	return nil
}

func use(file *File) {
	defer file.Close()
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.File.Close/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	if calls[0].Effect != effectDeferred {
		t.Errorf("the deferred call's effect is %q, want %q", calls[0].Effect, effectDeferred)
	}
}

// The goroutine's call runs on another goroutine, concurrently with the rest of
// the function.
func TestTheGoroutineCallCarriesTheConcurrentEffect(t *testing.T) {
	functions := translateSource(t, `
package sample

func work() {
}

func use() {
	go work()
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.work/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	if calls[0].Effect != effectConcurrent {
		t.Errorf("the goroutine call's effect is %q, want %q", calls[0].Effect, effectConcurrent)
	}
}

// An ordinary call runs here, in this order. It must not be marked: a marker
// on everything would make the marker say nothing.
func TestAnOrdinaryCallCarriesNoEffect(t *testing.T) {
	functions := translateSource(t, `
package sample

type File struct{}

func (f *File) Close() error {
	return nil
}

func use(file *File) {
	file.Close()
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.File.Close/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	if calls[0].Effect != "" {
		t.Errorf("an ordinary call carries the effect %q, want none", calls[0].Effect)
	}
}

// A deferred release inside a loop is the case the marker exists for: the
// release runs when the function returns, so every iteration holds the
// resource until then. The marker has to survive the loop -- the sidecar
// lowers the loop, and the marker travels on the call.
func TestADeferredReleaseInsideALoopIsStillMarked(t *testing.T) {
	functions := translateSource(t, `
package sample

type File struct{}

func (f *File) Close() error {
	return nil
}

func use(files []*File) {
	for _, file := range files {
		defer file.Close()
	}
}
`)

	use := functionNamed(t, functions, "use")

	calls := callsTo(use, "sample.File.Close/0")
	if len(calls) != 1 {
		t.Fatalf("the call count is %d, want exactly 1", len(calls))
	}
	if calls[0].Effect != effectDeferred {
		t.Errorf("the deferred call's effect is %q, want %q", calls[0].Effect, effectDeferred)
	}
}
