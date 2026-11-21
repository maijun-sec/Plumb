package main

// Tests for the type switch's chain: every type of a case is tested, the next
// case is tried before its body runs, and the default clause's body is
// reachable.
//
// THE MEASUREMENT THAT BROUGHT THIS HERE.  The VALUE switch had this fixed
// long ago -- its false branch points at the next case's TEST, and the
// comment in translateSwitch says so.  The TYPE switch was the same code
// shape and never got the same treatment: its false branch pointed at the
// next clause's BODY.  Three things followed from that:
//
//   - every clause after the first had a DEAD test: the false edge of the
//     first test went straight into the second clause's body, so the second
//     clause's own type test was never reached;
//   - the 2nd..n-th type of a `case int, int64:` was never tested, because
//     the first type's branch did not fall through;
//   - in a switch with three clauses the default clause's BODY was
//     unreachable, because the only edge that reached it came from a test
//     that was itself dead.
//
// The engine's CFG is built from these edges, so the defect was a wrong
// graph, not a missing annotation.
//
// THE MEASUREMENT THAT CAPPED THE EXPECTATION.  The engine has no rule that
// reads the type test's result: the TAC kind INSTANCEOF has no consumer in
// the analysis engine at all.  Nine end-to-end shapes were scanned with the
// old chain and with the new one, and the findings were the same in every
// one of them -- the shape was wrong, and the rules did not notice.  So
// these tests pin the SHAPE, not a finding: what they assert is that the
// chain reaches the code the source says it reaches.

import "testing"

// reachableInstructions returns the indexes of the instructions that can be
// reached from the function's entry.
//
// A branch has TWO targets and does not fall through; a jump has one target
// and does not fall through; a return and a throw end the path; every other
// instruction falls through into the next one.  A label is a marker: the
// instruction a branch or a jump names is the one the label stands for.
//
// WHY INSTRUCTIONS AND NOT LABELS.  The defect this file pins is a DEAD
// TEST: the instruction was emitted, but nothing could reach it.  A label
// that nothing names is a different thing, and the existing helper for that
// is in switch_test.go.
func reachableInstructions(function Function) map[int]bool {
	labelIndex := map[string]int{}
	for index, instruction := range function.Instructions {
		if instruction.Kind == KindLabel {
			labelIndex[instruction.Label] = index
		}
	}

	reachable := map[int]bool{}
	queue := []int{}
	if len(function.Instructions) > 0 {
		queue = append(queue, 0)
	}
	for len(queue) > 0 {
		index := queue[0]
		queue = queue[1:]
		if index < 0 || index >= len(function.Instructions) || reachable[index] {
			continue
		}
		reachable[index] = true
		instruction := function.Instructions[index]
		switch instruction.Kind {
		case KindBranch:
			for _, label := range []string{instruction.TrueLabel, instruction.FalseLabel} {
				if target, ok := labelIndex[label]; ok {
					queue = append(queue, target)
				}
			}
		case KindJump:
			if target, ok := labelIndex[instruction.Label]; ok {
				queue = append(queue, target)
			}
		case KindReturn, KindThrow:
			// The path ends here.
		default:
			queue = append(queue, index+1)
		}
	}
	return reachable
}

// typeTestsOf returns the indexes of the reachable type tests of a function.
//
// A type test is an INSTANCEOF whose result a branch is on.  Only the
// reachable ones count: a test nothing can reach is a test that never runs.
func typeTestsOf(function Function, reachable map[int]bool) []int {
	tests := []int{}
	for index, instruction := range function.Instructions {
		if instruction.Kind == KindInstanceOf && reachable[index] {
			tests = append(tests, index)
		}
	}
	return tests
}

// branchAfter returns the branch that follows the instruction at the given
// index, or nil when there is none.
func branchAfter(function Function, index int) *Instruction {
	if index+1 >= len(function.Instructions) {
		return nil
	}
	candidate := function.Instructions[index+1]
	if candidate.Kind != KindBranch {
		return nil
	}
	return &function.Instructions[index+1]
}

// labelIndexOf returns the index of the instruction the given label stands
// for, or -1 when no instruction carries it.
func labelIndexOf(function Function, label string) int {
	for index, instruction := range function.Instructions {
		if instruction.Kind == KindLabel && instruction.Label == label {
			return index
		}
	}
	return -1
}

// TestATypeSwitchTestsEveryTypeOfItsCase pins the multi-type case.
//
// `case int, int64:` names two types, and each of them is a test the value
// has to pass.  The lowering used to test only the first one: the second
// test was emitted after the first branch, and a branch has two targets and
// does not fall through -- so the second test was dead code, and the engine
// believed `int64` could never reach that clause.
func TestATypeSwitchTestsEveryTypeOfItsCase(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(v interface{}) string {
	switch v.(type) {
	case int, int64:
		return "integer"
	case string:
		return "string"
	default:
		return "other"
	}
}
`)

	classify := functionNamed(t, functions, "classify")
	reachable := reachableInstructions(classify)
	tests := typeTestsOf(classify, reachable)

	if len(tests) != 3 {
		t.Fatalf(
			"the switch names three types (int, int64, string) and every one of them must be a"+
				" reachable test; the reachable tests are %v",
			tests)
	}

	first := branchAfter(classify, tests[0])
	second := branchAfter(classify, tests[1])
	if first == nil || second == nil {
		t.Fatalf("every type test must be followed by the branch on its result")
	}
	if first.TrueLabel != second.TrueLabel {
		t.Fatalf(
			"the two types of one clause lead to the same body; the first leads to %q and the"+
				" second to %q",
			first.TrueLabel,
			second.TrueLabel)
	}
}

// TestATypeSwitchTriesTheNextCaseBeforeItsBody pins the chain.
//
// The false branch of a type test goes to the next case's TEST, not to its
// BODY.  The old chain pointed at the body, so the next case ran without
// its type test: a value that matched nothing reached the next clause's
// body, and the clause's own test was dead code.
func TestATypeSwitchTriesTheNextCaseBeforeItsBody(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(v interface{}) string {
	switch v.(type) {
	case int:
		return "integer"
	case string:
		return "string"
	default:
		return "other"
	}
}
`)

	classify := functionNamed(t, functions, "classify")
	reachable := reachableInstructions(classify)
	tests := typeTestsOf(classify, reachable)

	if len(tests) != 2 {
		t.Fatalf("the switch names two types and both of them must be tested; the reachable tests are %v", tests)
	}

	branch := branchAfter(classify, tests[0])
	if branch == nil {
		t.Fatalf("the type test must be followed by the branch on its result")
	}

	target := labelIndexOf(classify, branch.FalseLabel)
	if target < 0 {
		t.Fatalf("the false branch names the label %q, which no instruction carries", branch.FalseLabel)
	}
	if !reachable[target] {
		t.Fatalf("the next case's test must be reachable; the label %q is not", branch.FalseLabel)
	}
	if target+1 >= len(classify.Instructions) {
		t.Fatalf("the label %q stands for the last instruction", branch.FalseLabel)
	}
	next := classify.Instructions[target+1]
	if next.Kind != KindInstanceOf {
		t.Fatalf(
			"the false branch of a type test goes to the next case's TEST; the label %q is"+
				" followed by a %s, so the next case's body runs without its test",
			branch.FalseLabel,
			next.Kind)
	}
}

// TestTheDefaultClauseOfATypeSwitchIsReachable pins the default.
//
// The default clause is where a value that matches no type goes.  With the
// old chain the default's body was reachable only from the false edge of a
// test that was itself dead -- so in a switch with three clauses the default
// body was unreachable, and the engine analysed it as dead code.
func TestTheDefaultClauseOfATypeSwitchIsReachable(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(v interface{}) string {
	switch v.(type) {
	case int:
		return "integer"
	case string:
		return "string"
	default:
		return "other"
	}
}
`)

	classify := functionNamed(t, functions, "classify")
	reachable := reachableInstructions(classify)

	body := -1
	for index, instruction := range classify.Instructions {
		if instruction.Kind != KindReturn {
			continue
		}
		if instruction.Lhs1 == nil || instruction.Lhs1.Value != "other" {
			continue
		}
		body = index
		break
	}
	if body < 0 {
		t.Fatalf("the default clause returns \"other\" and that return is not in the TAC")
	}

	// The return sits in the default clause's body, so the label above it
	// is the body's own label.
	label := -1
	for index := body - 1; index >= 0; index-- {
		if classify.Instructions[index].Kind == KindLabel {
			label = index
			break
		}
	}
	if label < 0 {
		t.Fatalf("the default clause's body has no label above it")
	}
	if !reachable[label] {
		t.Fatalf(
			"the default clause's body must be reachable: a value that matches no type goes"+
				" there; the label %q is not reachable",
			classify.Instructions[label].Label)
	}
}

// TestADefaultInTheMiddleOfATypeSwitchKeepsTheCasesAfterItReachable pins the
// default that is not last.
//
// A default in the middle is not a test: the chain continues past it.  With
// the old chain the clause after the default was never tested at all, because
// the false edge of the first test went into the default's body.
func TestADefaultInTheMiddleOfATypeSwitchKeepsTheCasesAfterItReachable(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(v interface{}) string {
	switch v.(type) {
	case int:
		return "integer"
	default:
		return "other"
	case string:
		return "string"
	}
}
`)

	classify := functionNamed(t, functions, "classify")
	reachable := reachableInstructions(classify)
	tests := typeTestsOf(classify, reachable)

	if len(tests) != 2 {
		t.Fatalf(
			"the switch names two types (int and string) and both of them must be tested, the"+
				" default in the middle being no test; the reachable tests are %v",
			tests)
	}
}
