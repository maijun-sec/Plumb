package main

// Tests for the literals: the wire carries a NUMBER and a CHARACTER, and the
// text a source file writes is not always the decimal form of either.
//
// The reader on the other side parses a decimal number and takes the first
// character of a string. A hexadecimal literal and a rune literal used to
// arrive as the text they were written with -- "0xFF" and "'a'" -- so the
// first one could not be parsed at all (and the constant changed its type,
// silently) and the second one became the apostrophe.

import "testing"

// constantOperands returns every constant operand of a function.
func constantOperands(function Function) []*Operand {
	found := []*Operand{}
	for _, instruction := range function.Instructions {
		for _, operand := range []*Operand{instruction.Dest, instruction.Lhs1, instruction.Lhs2} {
			if operand != nil && operand.Kind == "CONST" {
				found = append(found, operand)
			}
		}
	}
	return found
}

// hasConstant reports whether the function carries a constant of a kind and a
// value.
func hasConstant(function Function, kind string, value string) bool {
	for _, operand := range constantOperands(function) {
		if operand.ConstKind == kind && operand.Value == value {
			return true
		}
	}
	return false
}

// TestAHexadecimalLiteralIsCarriedAsItsValue pins the numeric normalisation.
//
// A hexadecimal literal used to be carried as the text "0xFF". The reader
// parses a decimal number, so it could not parse that one, and the constant
// changed its type without anybody being told: every comparison against it
// was comparing a string.
func TestAHexadecimalLiteralIsCarriedAsItsValue(t *testing.T) {
	functions := translateSource(t, `
package sample

func f(v int) int {
	if v > 0xFF {
		return 1
	}
	return 0
}
`)

	function := functionNamed(t, functions, "f")
	if !hasConstant(function, "INT", "255") {
		t.Fatalf("the hexadecimal literal is not carried as the number 255: %v", constantOperands(function))
	}
}

// TestALiteralWithASeparatorIsCarriedAsItsValue pins the same normalisation
// for the separator Go allows in a literal.
func TestALiteralWithASeparatorIsCarriedAsItsValue(t *testing.T) {
	functions := translateSource(t, `
package sample

func f(v int) int {
	if v > 1_000 {
		return 1
	}
	return 0
}
`)

	function := functionNamed(t, functions, "f")
	if !hasConstant(function, "INT", "1000") {
		t.Fatalf("the separated literal is not carried as the number 1000: %v", constantOperands(function))
	}
}

// TestARuneLiteralIsCarriedAsTheCharacter pins the character normalisation.
//
// A rune literal is written with its quotes: 'a'. The reader takes the FIRST
// character of the text it is given, so the value used to be the apostrophe,
// and every rune literal in the program was the same character.
func TestARuneLiteralIsCarriedAsTheCharacter(t *testing.T) {
	functions := translateSource(t, `
package sample

func f() rune {
	return 'a'
}
`)

	function := functionNamed(t, functions, "f")
	if !hasConstant(function, "CHAR", "a") {
		t.Fatalf("the rune literal is not carried as the character a: %v", constantOperands(function))
	}
}

// TestADecimalLiteralIsCarriedUnchanged pins the other half of the rule: a
// literal that already reads as a decimal number is carried exactly as it was
// written, so the normalisation touches nothing else.
func TestADecimalLiteralIsCarriedUnchanged(t *testing.T) {
	functions := translateSource(t, `
package sample

func f(v int) int {
	if v > 42 {
		return 1
	}
	return 0
}
`)

	function := functionNamed(t, functions, "f")
	if !hasConstant(function, "INT", "42") {
		t.Fatalf("the decimal literal is not carried as 42: %v", constantOperands(function))
	}
}
