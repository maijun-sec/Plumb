package main

// Tests for the switch lowering: the expression-less form, the chain of the
// cases, and the default clause.
//
// THE MEASUREMENT THAT BROUGHT THIS HERE.  A switch with no tag
// (`switch { case cond: }`) used to take the WHOLE file down: the sidecar
// died on it with a nil pointer dereference, and the file produced no TAC at
// all -- not a degraded TAC, none.  The cause was the discriminant: the tag
// of such a switch is nil, and lowering a nil expression crashed.
//
// THE DECISION THIS FILE PINS.  A switch with no tag has no discriminant:
// `case cond:` means "if cond is true".  So the branch is on the case's own
// condition, exactly as it is in an `if`, and the cases are tried in order.

import (
	"go/token"
	"testing"
)

// branchesOf returns the branch instructions of a function.
func branchesOf(function Function) []Instruction {
	found := []Instruction{}
	for _, instruction := range function.Instructions {
		if instruction.Kind == KindBranch {
			found = append(found, instruction)
		}
	}
	return found
}

// binopWithOperator returns the instruction of a binary operation, or the
// zero instruction when the function has none.
func binopWithOperator(function Function, operator string) *Instruction {
	for index := range function.Instructions {
		instruction := function.Instructions[index]
		if instruction.Kind == KindBinOp && instruction.BinOp == operator {
			return &function.Instructions[index]
		}
	}
	return nil
}

// labelPreceding returns the label of the label instruction that comes
// before the given instruction, or "" when there is none.
//
// The label instruction is what says "the test of this case starts here",
// and the branch that follows it is the case's own test.
func labelPreceding(function Function, target *Instruction) string {
	for index := range function.Instructions {
		instruction := function.Instructions[index]
		if instruction.Kind != KindLabel {
			continue
		}
		found := false
		for later := index + 1; later < len(function.Instructions); later++ {
			candidate := function.Instructions[later]
			if candidate.Kind == KindLabel {
				break
			}
			if target != nil && candidate.Kind == target.Kind &&
				candidate.TrueLabel == target.TrueLabel && candidate.FalseLabel == target.FalseLabel {
				found = true
				break
			}
		}
		if found {
			return instruction.Label
		}
	}
	return ""
}

// labelOfReturn returns the label the given return value lives under, or ""
// when the function does not return it.
func labelOfReturn(function Function, value string) string {
	for index := range function.Instructions {
		instruction := function.Instructions[index]
		if instruction.Kind != KindReturn {
			continue
		}
		if instruction.Lhs1 == nil || instruction.Lhs1.Value != value {
			continue
		}
		for earlier := index - 1; earlier >= 0; earlier-- {
			previous := function.Instructions[earlier]
			if previous.Kind == KindLabel {
				return previous.Label
			}
		}
	}
	return ""
}

// unreachableLabels returns the labels of a function that nothing can reach.
//
// A label is reachable when a branch or a jump names it, or when the
// instruction before it falls through into it.  A label that nothing reaches
// is a branch the analysis believes cannot happen -- and the code under it
// is then analysed as dead.
func unreachableLabels(function Function) []string {
	targets := map[string]bool{}
	for _, instruction := range function.Instructions {
		switch instruction.Kind {
		case KindBranch:
			targets[instruction.TrueLabel] = true
			targets[instruction.FalseLabel] = true
		case KindJump:
			targets[instruction.Label] = true
		}
	}

	unreachable := []string{}
	for index, instruction := range function.Instructions {
		if instruction.Kind != KindLabel || targets[instruction.Label] {
			continue
		}
		if index == 0 {
			// The function's own entry.
			continue
		}
		previous := function.Instructions[index-1]
		if previous.Kind != KindJump && previous.Kind != KindBranch {
			// The instruction before it falls through into it.
			continue
		}
		unreachable = append(unreachable, instruction.Label)
	}
	return unreachable
}

// TestAnExpressionlessSwitchBranchesOnItsCondition pins the decision.
//
// A switch with no tag has no discriminant, so there is nothing to compare
// the case's expression against: the case's expression IS the condition,
// and the branch is on it -- the shape an `if` has.
func TestAnExpressionlessSwitchBranchesOnItsCondition(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(value string) string {
	switch {
	case value != "":
		return "set"
	default:
		return "empty"
	}
}
`)

	classify := functionNamed(t, functions, "classify")

	branches := branchesOf(classify)
	if len(branches) != 1 {
		t.Fatalf("the branch count is %d, want exactly 1: the case's condition is the only test", len(branches))
	}

	comparison := binopWithOperator(classify, "NE")
	if comparison == nil {
		t.Fatalf("the case's own comparison was not lowered")
	}
	branch := branches[0]
	if comparison.Dest == nil || branch.Lhs1 == nil {
		t.Fatalf("the comparison has no result or the branch has no condition")
	}
	if branch.Lhs1.Name != comparison.Dest.Name {
		t.Fatalf("the branch is on %q, want the comparison's own result %q", branch.Lhs1.Name, comparison.Dest.Name)
	}

	// And nothing compares the condition with a discriminant: there is no
	// discriminant, and a comparison against one would lose both the meaning
	// and the taint of the condition.
	if invented := binopWithOperator(classify, "EQ"); invented != nil {
		t.Fatalf("the switch compared something with EQ: %v", invented)
	}
}

// labelLeadingTo returns the label the control flow comes from when it jumps
// to the given label, or "" when nothing jumps to it.
//
// The default's BODY is entered from the default's own test label, so this
// is how a test says "the default's body is entered from here".
func labelLeadingTo(function Function, target string) string {
	for index := range function.Instructions {
		instruction := function.Instructions[index]
		jumps := false
		switch instruction.Kind {
		case KindJump:
			jumps = instruction.Label == target
		case KindBranch:
			jumps = instruction.TrueLabel == target || instruction.FalseLabel == target
		}
		if !jumps {
			continue
		}
		for earlier := index - 1; earlier >= 0; earlier-- {
			previous := function.Instructions[earlier]
			if previous.Kind == KindLabel {
				return previous.Label
			}
		}
	}
	return ""
}

// TestTheCasesOfAnExpressionlessSwitchAreTriedInOrder pins the chain.
//
// The first case's false branch goes to the second case's test, and the last
// case's false branch goes to the default's body.
func TestTheCasesOfAnExpressionlessSwitchAreTriedInOrder(t *testing.T) {
	functions := translateSource(t, `
package sample

func bucket(size int) string {
	switch {
	case size > 10:
		return "big"
	case size > 5:
		return "medium"
	default:
		return "small"
	}
}
`)

	bucket := functionNamed(t, functions, "bucket")

	branches := branchesOf(bucket)
	if len(branches) != 2 {
		t.Fatalf("the branch count is %d, want exactly 2: one test per case", len(branches))
	}

	secondTest := labelPreceding(bucket, &branches[1])
	if secondTest == "" {
		t.Fatalf("the second case has no test label")
	}
	if branches[0].FalseLabel != secondTest {
		t.Fatalf("the first case fails to %q, want the second case's test %q", branches[0].FalseLabel, secondTest)
	}

	defaultBody := labelOfReturn(bucket, "small")
	if defaultBody == "" {
		t.Fatalf("the default's body is not in the instruction stream")
	}
	// The default's body is entered from the default's own test label, and
	// that is where the last case fails to.
	defaultTest := labelLeadingTo(bucket, defaultBody)
	if defaultTest == "" {
		t.Fatalf("nothing enters the default's body")
	}
	if branches[1].FalseLabel != defaultTest {
		t.Fatalf("the last case fails to %q, want the default's test %q", branches[1].FalseLabel, defaultTest)
	}
}

// TestADefaultInTheMiddleKeepsTheCasesAfterItReachable pins the default.
//
// Go allows the default clause between two cases, and the cases after it
// must still be tested.  The default is not a TEST: the chain continues past
// it, and its BODY is entered when nothing matched.
func TestADefaultInTheMiddleKeepsTheCasesAfterItReachable(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(mode int) string {
	switch mode {
	case 1:
		return "one"
	default:
		return "other"
	case 2:
		return "two"
	}
}
`)

	classify := functionNamed(t, functions, "classify")

	if unreachable := unreachableLabels(classify); len(unreachable) != 0 {
		t.Fatalf("these labels cannot be reached: %v", unreachable)
	}
}

// TestATaggedSwitchComparesAgainstItsDiscriminant pins the other form.
//
// The switch with a tag compares its discriminant with the case's value, and
// that must stay: it is the shape the analysis reads the tagged form by.
func TestATaggedSwitchComparesAgainstItsDiscriminant(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(mode int) string {
	switch mode {
	case 1:
		return "one"
	default:
		return "other"
	}
}
`)

	classify := functionNamed(t, functions, "classify")

	comparison := binopWithOperator(classify, "EQ")
	if comparison == nil {
		t.Fatalf("the tagged switch did not compare its discriminant with the case's value")
	}
	if comparison.Lhs1 == nil || comparison.Lhs1.Name != "mode" {
		t.Fatalf("the comparison is on %v, want the switch's discriminant", comparison.Lhs1)
	}
}

// TestHavocToleratesAMissingExpression pins the safety net.
//
// An unknown value is what the translator falls back to when an expression
// cannot be lowered.  The expression can be ABSENT -- a switch with no tag
// has no discriminant, and a type switch's subject can be missing -- and
// then the fallback must not reach for the expression's position, because
// there is no expression to ask.
//
// This is the difference between "one value is unknown" and "the sidecar
// dies and the whole file produces nothing".
func TestHavocToleratesAMissingExpression(t *testing.T) {
	tr := &translator{fset: token.NewFileSet()}

	operand := tr.havoc(nil)

	if operand == nil {
		t.Fatalf("the unknown value is not an operand")
	}
	if operand.Kind != "VAR" {
		t.Fatalf("the unknown value is a %q, want a variable", operand.Kind)
	}
	if len(tr.instructions) != 1 {
		t.Fatalf("the instruction count is %d, want exactly 1", len(tr.instructions))
	}
	if tr.instructions[0].Kind != KindHavoc {
		t.Fatalf("the instruction is a %q, want %q", tr.instructions[0].Kind, KindHavoc)
	}
}
