package main

// Tests for the type a THROW carries.
//
// A catch handler is matched against the type of the exception, so a THROW
// that does not say what it threw leaves every handler reachable from every
// throw -- and the engine cannot tell which catch parameter inherits the
// thrown value's taint.
//
// Go raises an exception in three ways, and each of them has to name the
// type it raises: the error result of a two-result return, an explicit panic,
// and a failed type assertion.

import "testing"

// throwsIn returns the THROW instructions of a function.
func throwsIn(function Function) []Instruction {
	found := []Instruction{}
	for _, instruction := range function.Instructions {
		if instruction.Kind == KindThrow {
			found = append(found, instruction)
		}
	}
	return found
}

// theOnlyThrow returns the function's single THROW, and fails the test when
// there is not exactly one.
func theOnlyThrow(t *testing.T, function Function) Instruction {
	t.Helper()
	throws := throwsIn(function)
	if len(throws) != 1 {
		t.Fatalf("the function raises %d exceptions, want exactly 1", len(throws))
	}
	return throws[0]
}

// TestErrorResultThrowCarriesTheErrorType pins the type of the THROW that
// stands for a returned error.
//
// The type is the DECLARED type of the error result -- the error interface --
// because that is the type a handler is matched against. The concrete error
// value is not known statically.
func TestErrorResultThrowCarriesTheErrorType(t *testing.T) {
	functions := translateSource(t, `
package sample

type myError struct{}

func (myError) Error() string { return "failed" }

func produce(fail bool) (int, error) {
	if fail {
		return 0, myError{}
	}
	return 1, nil
}
`)

	produce := functionNamed(t, functions, "produce")
	throw := theOnlyThrow(t, produce)

	if throw.CtorType == nil {
		t.Fatalf("the THROW of a returned error carries no type")
	}
	if throw.CtorType.Kind != TypeClass || throw.CtorType.Name != "error" {
		t.Errorf("the THROW carries %+v, want the class 'error'", throw.CtorType)
	}
}

// TestPanicCarriesTheTypeOfThePanickedValue pins the type of the THROW that
// stands for a panic.
//
// The type is the static type of the panicked value: a string literal panics
// with a string, and a value of a named type panics with that type.
func TestPanicCarriesTheTypeOfThePanickedValue(t *testing.T) {
	functions := translateSource(t, `
package sample

type myError struct{}

func (myError) Error() string { return "failed" }

func panicString() {
	panic("boom")
}

func panicNamed() {
	panic(myError{})
}
`)

	stringThrow := theOnlyThrow(t, functionNamed(t, functions, "panicString"))
	if stringThrow.CtorType == nil {
		t.Fatalf("the THROW of a panic carries no type")
	}
	if stringThrow.CtorType.Kind != TypeString {
		t.Errorf("the THROW carries %+v, want the string type", stringThrow.CtorType)
	}

	namedThrow := theOnlyThrow(t, functionNamed(t, functions, "panicNamed"))
	if namedThrow.CtorType == nil {
		t.Fatalf("the THROW of a panic carries no type")
	}
	if namedThrow.CtorType.Kind != TypeClass || namedThrow.CtorType.Name != "sample.myError" {
		t.Errorf("the THROW carries %+v, want the class 'sample.myError'", namedThrow.CtorType)
	}
}

// TestTypeAssertionThrowCarriesItsType pins the type of the THROW that stands
// for a failed type assertion.
//
// This one already carried its type; the test is here so that a change to the
// THROW's shape cannot drop it unnoticed.
func TestTypeAssertionThrowCarriesItsType(t *testing.T) {
	functions := translateSource(t, `
package sample

func asString(value any) string {
	result := value.(string)
	return result
}
`)

	throw := theOnlyThrow(t, functionNamed(t, functions, "asString"))
	if throw.CtorType == nil {
		t.Fatalf("the THROW of a failed type assertion carries no type")
	}
	if throw.CtorType.Kind != TypeClass || throw.CtorType.Name != "runtime.TypeAssertionError" {
		t.Errorf("the THROW carries %+v, want the class 'runtime.TypeAssertionError'", throw.CtorType)
	}
}
