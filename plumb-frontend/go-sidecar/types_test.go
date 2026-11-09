package main

// Tests for the Go type system's mapping onto the TAC's type kinds.

import (
	"go/token"
	"go/types"
	"testing"
)

func TestMapBasicTypes(t *testing.T) {
	cases := []struct {
		name string
		give types.Type
		want string
	}{
		{"bool", types.Typ[types.Bool], TypeBoolean},
		{"int8", types.Typ[types.Int8], TypeByte},
		{"int16", types.Typ[types.Int16], TypeShort},
		{"int32", types.Typ[types.Int32], TypeInt},
		{"int", types.Typ[types.Int], TypeInt},
		{"int64", types.Typ[types.Int64], TypeLong},
		{"uint8", types.Typ[types.Uint8], TypeByte},
		{"uint64", types.Typ[types.Uint64], TypeLong},
		{"uintptr", types.Typ[types.Uintptr], TypeLong},
		{"float32", types.Typ[types.Float32], TypeFloat},
		{"float64", types.Typ[types.Float64], TypeDouble},
		{"string", types.Typ[types.String], TypeString},
		{"untyped int", types.Typ[types.UntypedInt], TypeInt},
		{"untyped string", types.Typ[types.UntypedString], TypeString},
		{"untyped bool", types.Typ[types.UntypedBool], TypeBoolean},
		{"untyped nil", types.Typ[types.UntypedNil], TypeNull},
	}

	for _, test := range cases {
		got := mapGoType(test.give)
		if got.Kind != test.want {
			t.Errorf("%s: the kind is %q, want %q", test.name, got.Kind, test.want)
		}
	}
}

func TestMapPointerBecomesClassOfTheElement(t *testing.T) {
	// A pointer is a reference, and the TAC has ONE reference kind. The
	// name is the element's name, so that "*os.File" and "os.File" land in
	// the same class bucket and a pointer method's callee key joins with it.
	pointer := types.NewPointer(types.Typ[types.Int])
	got := mapGoType(pointer)
	if got.Kind != TypeClass {
		t.Fatalf("a pointer's kind is %q, want %q", got.Kind, TypeClass)
	}
	if got.Name != "int" {
		t.Errorf("a pointer's name is %q, want %q", got.Name, "int")
	}
}

func TestMapSliceBecomesArray(t *testing.T) {
	slice := types.NewSlice(types.Typ[types.Int])
	got := mapGoType(slice)
	if got.Kind != TypeArray {
		t.Fatalf("a slice's kind is %q, want %q", got.Kind, TypeArray)
	}
	if got.Component == nil || got.Component.Kind != TypeInt {
		t.Errorf("a slice's component is %+v, want an INT", got.Component)
	}
}

func TestMapMapBecomesClass(t *testing.T) {
	goMap := types.NewMap(types.Typ[types.String], types.Typ[types.Int])
	got := mapGoType(goMap)
	if got.Kind != TypeClass {
		t.Fatalf("a map's kind is %q, want %q", got.Kind, TypeClass)
	}
	if got.Name != "map[string]int" {
		t.Errorf("a map's name is %q, want %q", got.Name, "map[string]int")
	}
}

func TestMapInterfaceBecomesClass(t *testing.T) {
	// The predeclared error interface is a named type with no package, so
	// it keeps its bare name.
	errorType := types.Universe.Lookup("error").Type()
	got := mapGoType(errorType)
	if got.Kind != TypeClass {
		t.Fatalf("the error type's kind is %q, want %q", got.Kind, TypeClass)
	}
	if got.Name != "error" {
		t.Errorf("the error type's name is %q, want %q", got.Name, "error")
	}
}

func TestMapNamedTypeKeepsItsQualifiedName(t *testing.T) {
	pkg := types.NewPackage("example.com/store", "store")
	name := types.NewTypeName(token.NoPos, pkg, "Store", nil)
	structure := types.NewStruct([]*types.Var{
		types.NewField(token.NoPos, pkg, "id", types.Typ[types.Int], false),
	}, nil)
	named := types.NewNamed(name, structure, nil)

	got := mapGoType(named)
	if got.Kind != TypeClass {
		t.Fatalf("a named type's kind is %q, want %q", got.Kind, TypeClass)
	}
	if got.Name != "example.com/store.Store" {
		t.Errorf("a named type's name is %q, want %q", got.Name, "example.com/store.Store")
	}
}

func TestMapTypeParameterIsUnknown(t *testing.T) {
	pkg := types.NewPackage("example.com/generic", "generic")
	parameter := types.NewTypeParam(types.NewTypeName(token.NoPos, pkg, "T", nil), nil)
	got := mapGoType(parameter)
	if got.Kind != TypeUnknown {
		t.Errorf("a type parameter's kind is %q, want %q", got.Kind, TypeUnknown)
	}
}

func TestCalleeSignatureUsesTheDeclaredArity(t *testing.T) {
	// The arity counts the DECLARED parameters. The receiver is not one of
	// them: it is args[0] in the TAC's call convention, and the engine adds
	// the receiver offset itself.
	if got := calleeSignature("os.File", "Close", 0); got != "os.File.Close/0" {
		t.Errorf("the callee signature is %q, want %q", got, "os.File.Close/0")
	}
	if got := calleeSignature("fmt", "Println", 1); got != "fmt.Println/1" {
		t.Errorf("the callee signature is %q, want %q", got, "fmt.Println/1")
	}
}

func TestMethodOwnerStripsThePointer(t *testing.T) {
	pkg := types.NewPackage("example.com/store", "store")
	name := types.NewTypeName(token.NoPos, pkg, "Store", nil)
	named := types.NewNamed(name, types.NewStruct(nil, nil), nil)

	pointer := types.NewPointer(named)
	if got := methodOwner(pointer); got != "example.com/store.Store" {
		t.Errorf("the method owner is %q, want %q", got, "example.com/store.Store")
	}
}
