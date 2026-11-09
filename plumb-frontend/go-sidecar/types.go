package main

// Maps Go's type system onto the TAC's type kinds.
//
// The mapping is deliberately coarse. The TAC models only what the dataflow
// engine needs: primitives, a tagged class reference, arrays, and the
// sentinels. Go's generics are erased, exactly as they are for Java, Kotlin
// and C#.
//
// The one thing the mapping does NOT do is invent structure. A Go type that
// has no TAC counterpart becomes CLASS, not a guess: the TAC's CLASS is a
// tagged reference, and a map, a channel and a struct ARE references in Go.
// The cost is that the dataflow engine cannot tell a map from a struct, and
// the benefit is that the engine can still see that the value is a reference
// at all.

import (
	"go/types"
	"strconv"
	"strings"
)

// errorType is Go's predeclared error interface.
//
// It is looked up once because the check "is this result the error?" decides
// the whole multi-value lowering, and it is asked once per call.
var errorType = types.Universe.Lookup("error").Type()

// mapGoType maps a Go type onto a TAC type.
//
// A nil type -- an unresolved symbol -- becomes UNKNOWN. UNKNOWN is not a
// claim that the value is unconstrained; it is the absence of a claim.
func mapGoType(goType types.Type) *TypeRef {
	if goType == nil {
		return Simple(TypeUnknown)
	}

	switch typed := goType.(type) {
	case *types.Basic:
		return mapBasic(typed)

	case *types.Pointer:
		// A pointer is a reference, and the TAC has ONE reference kind.
		//
		// The name is the ELEMENT's name, not "*os.File". That is not a
		// detail: the callee signature of a pointer method names the type
		// ("os.File.Close/0"), and a field tracker keys on the class name.
		// A class called "*os.File" would be a different bucket from one
		// called "os.File", and nothing would join the two -- so the taint
		// would be tracked but never joined to the call.
		return Class(className(typed.Elem()))

	case *types.Slice:
		return Array(mapGoType(typed.Elem()))

	case *types.Array:
		// A fixed-size array is an array in the TAC's model too. The length
		// is not carried: the TAC's ARRAY carries a component type only.
		return Array(mapGoType(typed.Elem()))

	case *types.Named:
		// A named type is a CLASS under its own name. That covers named
		// structs, named interfaces, named maps, named channels, and named
		// types over a primitive ("type MyInt int").
		//
		// The last one is a deliberate loss: the TAC has no way to say
		// "an int that is not an int", so MyInt and int become the same
		// class. A checker that cares about the distinction has to look at
		// the source, not at the TAC.
		return Class(qualifiedTypeName(typed))

	case *types.Map:
		// A map is not an array and not a class with fields, so it becomes a
		// CLASS under Go's own type text. The name is unique per map type,
		// so two different map types do not share a bucket.
		return Class(typed.String())

	case *types.Struct:
		// An anonymous struct. Go's own type text names it exactly.
		return Class(typed.String())

	case *types.Interface:
		// An anonymous interface, including the empty interface that `any`
		// is an alias for.
		return Class(typed.String())

	case *types.Chan:
		return Class(typed.String())

	case *types.Signature:
		// A function type. A value of this type is a function VALUE, which
		// the sidecar does not model -- see the translator's report on
		// unmodelled constructs.
		return Class(typed.String())

	case *types.TypeParam:
		// A generic parameter. The concrete type is not known here, exactly
		// as it is not known for a C# type parameter.
		return Simple(TypeUnknown)

	case *types.Tuple, *types.Union:
		return Simple(TypeUnknown)
	}

	return Simple(TypeUnknown)
}

// mapBasic maps a Go basic type onto a TAC type kind.
func mapBasic(basic *types.Basic) *TypeRef {
	switch basic.Kind() {
	case types.Bool, types.UntypedBool:
		return Simple(TypeBoolean)
	case types.Int8, types.Uint8:
		return Simple(TypeByte)
	case types.Int16, types.Uint16:
		return Simple(TypeShort)
	case types.Int32, types.Uint32:
		return Simple(TypeInt)
	case types.Int, types.Uint:
		// Go's `int` is the platform's default integer: 32 bits on some
		// targets, 64 on others. The TAC's INT is 32 bits.
		//
		// The cost is stated rather than hidden: a 64-bit int modelled as
		// INT can make an overflow check under-report, because the checker
		// sees a narrower range than the program has. The alternative --
		// modelling it as LONG -- would over-report on a 32-bit target.
		// The request carries the build tags, so the Java side can tell the
		// sidecar which is right if the engine needs it.
		return Simple(TypeInt)
	case types.Int64, types.Uint64, types.Uintptr:
		return Simple(TypeLong)
	case types.Float32:
		return Simple(TypeFloat)
	case types.Float64, types.UntypedFloat:
		return Simple(TypeDouble)
	case types.String, types.UntypedString:
		return Simple(TypeString)
	case types.UntypedInt:
		return Simple(TypeInt)
	case types.UntypedRune:
		return Simple(TypeChar)
	case types.UntypedNil:
		return Simple(TypeNull)
	case types.UnsafePointer:
		return Class("unsafe.Pointer")
	case types.Complex64, types.Complex128, types.UntypedComplex:
		// The TAC has no complex kind. UNKNOWN is honest: the value is
		// known to be a number of some sort, but the engine's arithmetic
		// model does not cover it.
		return Simple(TypeUnknown)
	}
	return Simple(TypeUnknown)
}

// className returns the name a Go type is known by inside a CLASS.
//
// A named type keeps its qualified name; anything else falls back to Go's
// own type text, which is unique per type.
func className(goType types.Type) string {
	if named, ok := goType.(*types.Named); ok {
		return qualifiedTypeName(named)
	}
	if goType == nil {
		return ""
	}
	return goType.String()
}

// qualifiedTypeName returns the fully qualified name of a named type.
//
// A universe type -- `error`, and nothing else that has a name and no
// package -- keeps its bare name. Everything else is "import/path.Name",
// which is the same shape the C# sidecar produces for a fully qualified
// .NET type name.
func qualifiedTypeName(named *types.Named) string {
	object := named.Obj()
	if object == nil {
		return named.String()
	}
	return qualifiedObjectName(object)
}

// qualifiedObjectName returns the fully qualified name of an object.
func qualifiedObjectName(object types.Object) string {
	if object.Pkg() == nil {
		return object.Name()
	}
	return object.Pkg().Path() + "." + object.Name()
}

// methodOwner returns the owner name for a method: the named type of its
// receiver, with the pointer stripped.
//
// The pointer is stripped on purpose. `(*os.File).Close` and `os.File.Close`
// are the same method, and the callee key has to be the same for both, or
// the call graph will not join a pointer call to a value call.
func methodOwner(receiver types.Type) string {
	if receiver == nil {
		return ""
	}
	if pointer, ok := receiver.(*types.Pointer); ok {
		receiver = pointer.Elem()
	}
	if named, ok := receiver.(*types.Named); ok {
		return qualifiedTypeName(named)
	}
	return receiver.String()
}

// calleeSignature builds the canonical callee key: owner.method/arity.
//
// This is the form the sink catalogue matches on, and the form every other
// Plumb frontend emits. The arity is the number of DECLARED PARAMETERS, and
// the receiver is NOT one of them: the TAC's call convention puts the
// receiver at args[0], and the engine adds the receiver offset itself when
// it looks a sink's argument up.
//
// So `func (f *os.File) Close() error` is "os.File.Close/0" with the file
// at args[0], and `func Println(a ...any) int` is "fmt.Println/1" with the
// argument at args[0].
func calleeSignature(owner string, name string, arity int) string {
	return owner + "." + name + "/" + strconv.Itoa(arity)
}

// declarationSignature builds a function's declaration signature, in Plumb's
// owner.name(Params):Return form.
//
// The parameter and return types are Go's own type text. The return type is
// the FIRST result: a Go function that returns two meaningful values only
// has its first one represented, which is exactly the loss the function's
// problems array reports.
func declarationSignature(owner string, name string, parameters []string, result string) string {
	if result == "" {
		result = "void"
	}
	return owner + "." + name + "(" + strings.Join(parameters, ",") + "):" + result
}

// isErrorType reports whether a type is Go's predeclared error interface.
//
// This one test decides the whole multi-value lowering: a call whose second
// result is an error is modelled as a call with an exceptional exit, and a
// call whose second result is something else is a genuine two-result call
// that the TAC cannot express.
func isErrorType(goType types.Type) bool {
	if goType == nil || errorType == nil {
		return false
	}
	return types.Identical(goType, errorType)
}

// signatureArity returns the number of declared parameters of a function.
//
// The receiver is not a parameter, and a variadic parameter counts as one.
func signatureArity(signature *types.Signature) int {
	if signature == nil {
		return 0
	}
	return signature.Params().Len()
}
