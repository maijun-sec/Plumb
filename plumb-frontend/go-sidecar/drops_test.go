package main

// Tests for the lowerings that replace a placeholder with real TAC
// instructions, and for the drop channel that records what is still lost.
//
// The rule the whole design turns on is: a construct that CAN be lowered is
// lowered, and a construct that cannot is recorded. A construct that used to
// be silently dropped -- and these tests each name one -- must now produce
// instructions, and the function's precision must follow the drops.

import (
	"strings"
	"testing"
)

// dropIdsOf returns the construct ids of a function's drops.
func dropIdsOf(function Function) []string {
	ids := []string{}
	for _, drop := range function.Drops {
		ids = append(ids, drop.Construct)
	}
	return ids
}

// hasDrop reports whether a function recorded a drop with an id.
func hasDrop(function Function, construct string) bool {
	for _, drop := range function.Drops {
		if drop.Construct == construct {
			return true
		}
	}
	return false
}

// dropOf returns the drop with an id, and whether it was there.
func dropOf(function Function, construct string) (Drop, bool) {
	for _, drop := range function.Drops {
		if drop.Construct == construct {
			return drop, true
		}
	}
	return Drop{}, false
}

// binOps returns the binary operators of a function's instructions.
func binOps(function Function) []string {
	operators := []string{}
	for _, instruction := range function.Instructions {
		if instruction.Kind == KindBinOp {
			operators = append(operators, instruction.BinOp)
		}
	}
	return operators
}

// kindsOf returns the instruction kinds of a function.
func kindsOf(function Function) []string {
	kinds := []string{}
	for _, instruction := range function.Instructions {
		kinds = append(kinds, instruction.Kind)
	}
	return kinds
}

func TestCompoundAssignmentComputesAndWritesBack(t *testing.T) {
	functions := translateSource(t, `
package sample

func accumulate(total int, amount int) int {
	total += amount
	return total
}
`)

	accumulate := functionNamed(t, functions, "accumulate")

	// The ADDITION is in the stream. It used not to be: the compound
	// assignment was lowered as a MOVE of the right-hand side, so the
	// accumulator became the amount and every rule that counts afterwards
	// read the wrong value.
	if !containsString(binOps(accumulate), "ADD") {
		t.Errorf("the compound assignment produced no addition: %v", binOps(accumulate))
	}

	// And the result goes BACK into the target.
	written := false
	for _, instruction := range accumulate.Instructions {
		if instruction.Kind == KindMove && instruction.Dest != nil &&
			instruction.Dest.Name == "total" && instruction.Lhs1 != nil &&
			instruction.Lhs1.IsTemp {
			written = true
		}
	}
	if !written {
		t.Errorf("the compound assignment did not write back: %v", kindsOf(accumulate))
	}
}

func TestAndNotIsABitClearAndNotAnAddition(t *testing.T) {
	functions := translateSource(t, `
package sample

func clear(mask int, bits int) int {
	return mask &^ bits
}
`)

	clear := functionNamed(t, functions, "clear")

	// `a &^ b` is `a AND (NOT b)`. It used to fall through the operator
	// table's catch-all, which said ADD: a bit-clear was analysed as a sum.
	unops := []string{}
	for _, instruction := range clear.Instructions {
		if instruction.Kind == KindUnaryOp {
			unops = append(unops, instruction.UnOp)
		}
	}
	if !containsString(unops, "BITNOT") {
		t.Errorf("the AND-NOT produced no bit-not: %v", unops)
	}
	if !containsString(binOps(clear), "AND") {
		t.Errorf("the AND-NOT produced no AND: %v", binOps(clear))
	}
	if containsString(binOps(clear), "ADD") {
		t.Errorf("the AND-NOT was lowered as an addition: %v", binOps(clear))
	}
}

func TestAWriteThroughAPointerIsAFieldStore(t *testing.T) {
	functions := translateSource(t, `
package sample

func writeThrough(target *int, value int) {
	*target = value
}
`)

	writeThrough := functionNamed(t, functions, "writeThrough")

	found := false
	for _, instruction := range writeThrough.Instructions {
		if instruction.Kind == KindFieldStore && instruction.FieldName == cellValueField {
			found = true
		}
	}
	if !found {
		t.Errorf("the write through the pointer produced no field store: %v", kindsOf(writeThrough))
	}
}

func TestAnUnresolvedCalleeStillLowersItsArguments(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() int {
	return 1
}

func use(handler func(int) int) int {
	return handler(produce())
}
`)

	use := functionNamed(t, functions, "use")

	// The argument is evaluated even when the callee cannot be resolved.
	// The whole argument subtree used to go with the unresolved callee, and
	// every call inside it -- and every taint it carried -- went with it.
	calls := callsTo(use, "sample.produce/0")
	if len(calls) != 1 {
		t.Errorf("the argument's call was not lowered: the calls are %v", kindsOf(use))
	}
}

func TestAPositionalStructLiteralBindsItsFieldsInOrder(t *testing.T) {
	functions := translateSource(t, `
package sample

type Point struct {
	X int
	Y int
}

func make() Point {
	return Point{1, 2}
}
`)

	make := functionNamed(t, functions, "make")

	// A positional literal used to be reported and SKIPPED, so the struct
	// was allocated with none of its fields set.
	fields := []string{}
	for _, instruction := range make.Instructions {
		if instruction.Kind == KindFieldStore {
			fields = append(fields, instruction.FieldName)
		}
	}
	if len(fields) != 2 || fields[0] != "X" || fields[1] != "Y" {
		t.Errorf("the positional literal bound %v, want [X Y]", fields)
	}
}

func TestAnImaginaryLiteralIsAnUninterpretedValueAndAValueDrop(t *testing.T) {
	functions := translateSource(t, `
package sample

func imaginary() complex128 {
	return 3i
}
`)

	imaginary := functionNamed(t, functions, "imaginary")

	if !containsString(kindsOf(imaginary), KindUninterpreted) {
		t.Errorf("the imaginary literal produced no uninterpreted value: %v", kindsOf(imaginary))
	}

	// It is a VALUE loss: exactly one value is unknown, and the function
	// keeps its precision.
	drop, ok := dropOf(imaginary, DropGoComplexLiteral)
	if !ok {
		t.Fatalf("the imaginary literal recorded no drop: %v", dropIdsOf(imaginary))
	}
	if drop.Impact != ImpactValue {
		t.Errorf("the drop's impact is %q, want %q", drop.Impact, ImpactValue)
	}
	if imaginary.Precision != PrecisionFull {
		t.Errorf("the precision is %q, want %q", imaginary.Precision, PrecisionFull)
	}
}

func TestAGotoIsAJumpAndTheLabelIsATarget(t *testing.T) {
	functions := translateSource(t, `
package sample

func skip(n int) int {
	goto done
	n = n + 1
done:
	return n
}
`)

	skip := functionNamed(t, functions, "skip")

	jumps := []string{}
	labels := []string{}
	for _, instruction := range skip.Instructions {
		switch instruction.Kind {
		case KindJump:
			jumps = append(jumps, instruction.Label)
		case KindLabel:
			labels = append(labels, instruction.Label)
		}
	}

	// The goto used to be a NOP: the jump was not there, and the code after
	// it still ran, so the analysis believed the program fell through it.
	if !containsString(jumps, "goto_done") {
		t.Errorf("the goto produced no jump: %v", jumps)
	}
	if !containsString(labels, "goto_done") {
		t.Errorf("the label produced no label: %v", labels)
	}
}

func TestASelectCaseWithATwoValueReceiveIsReachable(t *testing.T) {
	functions := translateSource(t, `
package sample

func choose(channel chan int) int {
	select {
	case value, ok := <-channel:
		if ok {
			return value
		}
		return 0
	default:
		return 1
	}
}
`)

	choose := functionNamed(t, functions, "choose")

	// The case used to be unreachable: the channel of a two-value receive
	// could not be resolved, so the whole case was skipped and its
	// variables were never bound.
	branches := 0
	for _, instruction := range choose.Instructions {
		if instruction.Kind == KindBranch {
			branches++
		}
	}
	if branches < 2 {
		t.Errorf("the select produced %d branches, want at least 2", branches)
	}

	// The received value is bound.
	bound := false
	for _, instruction := range choose.Instructions {
		if instruction.Kind == KindMove && instruction.Dest != nil &&
			instruction.Dest.Name == "value" {
			bound = true
		}
	}
	if !bound {
		t.Errorf("the select case did not bind its value: %v", kindsOf(choose))
	}
}

func TestAMultiValueCallBindsEveryResult(t *testing.T) {
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
	if len(calls[0].ResultDests) != 2 {
		t.Fatalf("the call carries %d results, want 2", len(calls[0].ResultDests))
	}
	if calls[0].ResultDests[1] == nil || calls[0].ResultDests[1].Name != "err" {
		t.Errorf("the second result is %+v, want the variable 'err'", calls[0].ResultDests[1])
	}
}

func TestABareReturnReturnsTheNamedResults(t *testing.T) {
	functions := translateSource(t, `
package sample

func produce() (out string, err error) {
	out = "value"
	return
}
`)

	produce := functionNamed(t, functions, "produce")

	// A bare return used to return NOTHING, so the value the caller
	// receives was unknown.
	returned := false
	for _, instruction := range produce.Instructions {
		if instruction.Kind == KindReturn && instruction.Lhs1 != nil {
			returned = true
		}
	}
	if !returned {
		t.Errorf("the bare return returned no value: %v", kindsOf(produce))
	}
}

func TestTheFunctionRecordCarriesItsMemberKindAndBindings(t *testing.T) {
	functions := translateSource(t, `
package sample

type Counter struct {
	value int
}

func (c *Counter) Bump(by int) int {
	c.value += by
	return c.value
}
`)

	bump := functionNamed(t, functions, "Bump")

	if bump.MemberKind != memberKindMethod {
		t.Errorf("the member kind is %q, want %q", bump.MemberKind, memberKindMethod)
	}
	// The receiver is one of the parameters: the TAC's call convention puts
	// it at args[0], and the engine reads every argument index against that.
	if len(bump.Parameters) != 2 {
		t.Errorf("the parameter count is %d, want 2 (the receiver and the argument)",
			len(bump.Parameters))
	}
	if len(bump.ScopeBindings) == 0 {
		t.Errorf("the function carries no scope bindings")
	}
}

func TestThePrecisionFollowsTheDrops(t *testing.T) {
	// A SELECT records a SEMANTICS drop -- which case is ready is a
	// run-time fact -- so the function is DEGRADED, not merely lowered.
	functions := translateSource(t, `
package sample

func choose(channel chan int) int {
	select {
	case value := <-channel:
		return value
	}
}
`)

	choose := functionNamed(t, functions, "choose")

	drop, ok := dropOf(choose, DropGoSelectReadiness)
	if !ok {
		t.Fatalf("the select recorded no readiness drop: %v", dropIdsOf(choose))
	}
	if drop.Impact != ImpactSemantics {
		t.Errorf("the drop's impact is %q, want %q", drop.Impact, ImpactSemantics)
	}
	if choose.Precision != PrecisionDegraded {
		t.Errorf("the precision is %q, want %q", choose.Precision, PrecisionDegraded)
	}
	if drop.Line <= 0 {
		t.Errorf("the drop has no source line: %+v", drop)
	}
}

func TestEveryProblemHasADrop(t *testing.T) {
	// The two channels say different things -- a problem says the frontend
	// could not do its job, a drop says what the job cost -- but a loss
	// must never be reported in one and not the other. A function with a
	// problem and no drop is a SILENT loss, which is the thing the whole
	// channel exists to prevent.
	functions := translateSource(t, `
package sample

func choose(channel chan int) int {
	select {
	case value := <-channel:
		return value
	}
}

func use(handler func(int) int, value int) int {
	return handler(value)
}
`)

	for _, function := range functions {
		if len(function.Problems) == 0 {
			continue
		}
		if len(function.Drops) == 0 {
			t.Errorf("the function %q reported %d problems and no drop: the loss is silent",
				function.Name, len(function.Problems))
		}
		for _, drop := range function.Drops {
			if drop.Construct == "" {
				t.Errorf("the function %q has a drop with no construct id: %+v",
					function.Name, drop)
			}
			if drop.Scope != DropScopeFunction {
				t.Errorf("the function %q has a drop whose scope is %q, want %q",
					function.Name, drop.Scope, DropScopeFunction)
			}
		}
	}
}

func TestTheStructTagsAreCarried(t *testing.T) {
	// The struct tag says which key the field is written under when the
	// value is encoded. The sidecar read NO tag at all.
	fset, file, info, pkg := typeCheckSource(t, `
package sample

type Account struct {
	Owner  string `+"`json:\"owner\"`"+`
	Secret string `+"`json:\"secret\"`"+`
}
`)

	loaded := &loadedPackage{
		spec:  &PackageSpec{ImportPath: "sample"},
		pkg:   pkg,
		info:  info,
		files: []parsedSource{{path: "sample.go", file: file}},
	}

	declarations := translateFileTypes(fset, loaded, file)
	if len(declarations) != 1 {
		t.Fatalf("the type declaration count is %d, want 1", len(declarations))
	}
	declaration := declarations[0]
	if len(declaration.Fields) != 2 {
		t.Fatalf("the field count is %d, want 2", len(declaration.Fields))
	}
	if !strings.Contains(declaration.Fields[1].Tag, "secret") {
		t.Errorf("the second field's tag is %q, want it to name the secret key",
			declaration.Fields[1].Tag)
	}
}

// containsString reports whether a list holds a value.
func containsString(values []string, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}

func TestADeferRecordsTheLossOfItsPosition(t *testing.T) {
	// The design's ledger (row 45) asks for the deferred call to be moved
	// before every exit of the function, with a TryRegion of its own. That
	// was not done: the call stays where the `defer` is written, while Go
	// runs it at the function's exit. The call is still made -- so the
	// SHAPE is lost, not the meaning -- and the loss is recorded, because a
	// loss that is only visible in the precision field is a loss nobody can
	// count.
	functions := translateSource(t, `
package sample

func closeFile(name string) {}

func release(name string) {
	defer closeFile(name)
}
`)

	release := functionNamed(t, functions, "release")

	drop, ok := dropOf(release, DropGoDeferOrder)
	if !ok {
		t.Fatalf("the defer recorded no order drop: %v", dropIdsOf(release))
	}
	if drop.Impact != ImpactStructure {
		t.Errorf("the drop's impact is %q, want %q", drop.Impact, ImpactStructure)
	}
	if drop.Line <= 0 {
		t.Errorf("the drop has no source line: %+v", drop)
	}
	if release.Precision != PrecisionLowered {
		t.Errorf("the precision is %q, want %q", release.Precision, PrecisionLowered)
	}
}
