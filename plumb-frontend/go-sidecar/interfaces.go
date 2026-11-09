package main

// The program's named types, and the method sets they have.
//
// WHY THIS EXISTS.
//
// Go's interface satisfaction is IMPLICIT. Nothing in the source says
// "T implements I": a type satisfies an interface when it HAS the
// interface's methods, and it is the compiler that checks it, not the
// programmer. So an analysis that reads the source for implementation edges
// finds none, and a call through an interface reaches nothing at all -- the
// taint that should cross the call never crosses it.
//
// Three facts are needed to work the relation out, and each has one source:
//
//   - The interface's COMPLETE method set, including the methods of the
//     interfaces it embeds. Go's embedding rule makes an embedded
//     interface's methods part of the embedding interface's method set, and
//     go/types has already done that flattening: an interface's method set
//     is the union of its own methods and the method sets of the interfaces
//     it embeds, transitively.
//
//   - The concrete type's method set, WITH THE VALUE / POINTER
//     DISTINCTION. Go's rule is that a method declared on *T belongs to *T's
//     method set and not to T's, so a type that satisfies an interface
//     through a pointer receiver is not the same as one that satisfies it
//     through a value receiver. types.NewMethodSet answers for a type and
//     for a pointer to it, and the pointer's set is the superset of the two.
//
//   - The SIGNATURE of every method. Go compares signatures, not names: two
//     methods that share a name and differ in their parameters are different
//     methods, and a type that has only the second one does not satisfy the
//     interface. The signature is compared as TEXT, because each package is
//     type-checked on its own and two packages' types are not the same
//     objects -- but their text is the same.

import (
	"go/types"
	"strings"
)

// The maximum number of implementations one interface call may name.
//
// A call through an interface may reach every type in the program that
// satisfies the interface, and a program with a hundred error types would
// make every `err.Error()` a hundred calls. The cap keeps the instruction
// stream finite, and the loss is reported, so it is never silent.
const maxInterfaceImplementations = 64

// A method, as the index records it.
type indexedMethod struct {
	// The method's name.
	name string

	// The method's signature, as text.
	signature string
}

// A named type the analysed program declares.
type indexedType struct {
	// The type's qualified name: the package's import path and the type's
	// name. This is the OWNER the TAC's callee key uses, and it is the
	// same string the declaration of the type's methods carries.
	owner string

	// The type's method set, by method name.
	methods map[string]indexedMethod
}

// The program's type index.
//
// It holds the types the ANALYSED PROGRAM declares. A type from a dependency
// is not in it: the analysis has no body for that type, so an edge to it
// would lead nowhere.
type typeIndex struct {
	types []indexedType
}

// buildTypeIndex indexes the named types of the analysed packages.
func buildTypeIndex(packages []*loadedPackage) *typeIndex {
	index := &typeIndex{types: []indexedType{}}
	seen := map[string]bool{}
	for _, loaded := range packages {
		if loaded == nil || loaded.pkg == nil {
			continue
		}
		scope := loaded.pkg.Scope()
		for _, name := range scope.Names() {
			object, _ := scope.Lookup(name).(*types.TypeName)
			if object == nil {
				continue
			}
			named, _ := object.Type().(*types.Named)
			if named == nil {
				continue
			}
			// A generic type that is not instantiated has a method set
			// full of type parameters, and no instantiation of it is in
			// the program. It cannot be the dynamic type of a call, so
			// it is not a candidate.
			if named.TypeParams() != nil && named.TypeParams().Len() > 0 {
				continue
			}
			underlying := named.Underlying()
			if underlying == nil {
				continue
			}
			// An interface is not an implementation of itself.
			if _, isInterface := underlying.(*types.Interface); isInterface {
				continue
			}
			owner := qualifiedTypeName(named)
			if owner == "" || seen[owner] {
				continue
			}
			methods := methodSetOf(named)
			if len(methods) == 0 {
				continue
			}
			seen[owner] = true
			index.types = append(index.types, indexedType{owner: owner, methods: methods})
		}
	}
	return index
}

// methodSetOf returns the method set of the POINTER to a named type.
//
// The pointer's method set is the one that holds every method of the type:
// the ones declared on a value receiver AND the ones declared on a pointer
// receiver. Go's rule is that a method declared on *T belongs to *T's method
// set and not to T's -- and the pointer's set is the superset of the two, so
// matching against it never drops a type that satisfies an interface through
// a pointer receiver.
func methodSetOf(named *types.Named) map[string]indexedMethod {
	methods := map[string]indexedMethod{}
	set := types.NewMethodSet(types.NewPointer(named))
	for index := 0; index < set.Len(); index++ {
		function, ok := set.At(index).Obj().(*types.Func)
		if !ok {
			continue
		}
		methods[function.Name()] = indexedMethod{
			name:      function.Name(),
			signature: methodSignatureOf(function),
		}
	}
	return methods
}

// interfaceMethodSet returns an interface's COMPLETE method set.
//
// The set includes the methods of the interfaces the interface embeds: Go's
// embedding rule makes them part of the embedding interface's method set,
// and go/types has already flattened them.
func interfaceMethodSet(iface *types.Interface) map[string]indexedMethod {
	methods := map[string]indexedMethod{}
	if iface == nil {
		return methods
	}
	iface.Complete()
	for index := 0; index < iface.NumMethods(); index++ {
		function := iface.Method(index)
		if function == nil {
			continue
		}
		methods[function.Name()] = indexedMethod{
			name:      function.Name(),
			signature: methodSignatureOf(function),
		}
	}
	return methods
}

// methodSignatureOf returns a method's signature as text.
//
// The text is what the comparison is made on, so it has to be the same for
// the same method whichever package's type checker produced it: the package
// qualifier prints the package's IMPORT PATH, and the path is the same in
// every check.
func methodSignatureOf(function *types.Func) string {
	signature, ok := function.Type().(*types.Signature)
	if !ok {
		return function.Name() + "()"
	}
	parameters := []string{}
	for index := 0; index < signature.Params().Len(); index++ {
		parameters = append(parameters, types.TypeString(signature.Params().At(index).Type(), pathQualifier))
	}
	results := []string{}
	for index := 0; index < signature.Results().Len(); index++ {
		results = append(results, types.TypeString(signature.Results().At(index).Type(), pathQualifier))
	}
	return function.Name() + "(" + strings.Join(parameters, ",") + "):" + strings.Join(results, ",")
}

// pathQualifier names a package by its import path.
func pathQualifier(pkg *types.Package) string {
	if pkg == nil {
		return ""
	}
	return pkg.Path()
}

// interfaceOf returns the interface a receiver type IS, when it is one.
//
// The receiver's STATIC type is what decides: a call through an interface
// dispatches at run time to a concrete type, and the static type is the only
// thing the source says about it.
func interfaceOf(goType types.Type) *types.Interface {
	if goType == nil {
		return nil
	}
	if pointer, ok := goType.(*types.Pointer); ok {
		goType = pointer.Elem()
	}
	underlying := goType.Underlying()
	if underlying == nil {
		return nil
	}
	iface, _ := underlying.(*types.Interface)
	return iface
}

// implementationsOf returns the owners of the types that satisfy an
// interface.
//
// The relation is implicit in Go, so it is worked out here: a type satisfies
// the interface when its method set holds every method of the interface's
// complete method set, WITH THE SAME SIGNATURE.
func (index *typeIndex) implementationsOf(iface *types.Interface) []string {
	if index == nil || iface == nil {
		return nil
	}
	wanted := interfaceMethodSet(iface)
	// An interface with no methods -- `any` -- is satisfied by every type,
	// and a call through it dispatches to nothing: there is no method to
	// call. Naming every type would be noise.
	if len(wanted) == 0 {
		return nil
	}
	owners := []string{}
	for _, candidate := range index.types {
		if !satisfies(candidate.methods, wanted) {
			continue
		}
		owners = append(owners, candidate.owner)
	}
	return owners
}

// satisfies reports whether a method set holds every wanted method.
func satisfies(have map[string]indexedMethod, wanted map[string]indexedMethod) bool {
	for name, method := range wanted {
		other, ok := have[name]
		if !ok || other.signature != method.signature {
			return false
		}
	}
	return true
}
