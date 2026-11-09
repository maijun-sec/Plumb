package main

// Tests for the interface dispatch.
//
// A call through an interface is dispatched at RUN TIME to a concrete type,
// and Go's interface satisfaction is implicit: nothing in the source says
// which type receives it. So the analysis has to work the relation out from
// the method sets. These tests pin the three things that decides:
//
//   - the relation is structural, and it reaches the implementation;
//   - an EMBEDDED interface's methods are part of the method set;
//   - a method that shares the name but not the signature is NOT the method,
//     so a type that has only the wrong one does not satisfy the interface.

import (
	"strings"
	"testing"
)

// The dispatch keys a function's calls carry.
func dispatchKeys(function Function) []string {
	keys := []string{}
	for _, instruction := range function.Instructions {
		if instruction.Kind != KindCall {
			continue
		}
		keys = append(keys, instruction.CalleeSig)
	}
	return keys
}

func hasKey(keys []string, wanted string) bool {
	for _, key := range keys {
		if key == wanted {
			return true
		}
	}
	return false
}

func TestACallThroughAnInterfaceReachesTheTypeThatSatisfiesIt(t *testing.T) {
	functions := translateSource(t, `
package sample

type Reader interface {
	Read(p []byte) (int, error)
}

type fileReader struct{}

func (f *fileReader) Read(p []byte) (int, error) {
	return len(p), nil
}

func use(r Reader) {
	buffer := make([]byte, 8)
	r.Read(buffer)
}
`)
	use := functionNamed(t, functions, "use")
	keys := dispatchKeys(use)
	if !hasKey(keys, "sample.fileReader.Read/1") {
		t.Errorf("the call through the interface did not reach the implementation: %v", keys)
	}
	// The interface's own key stays on the call: a call whose
	// implementation is not in the analysed program is still a call.
	if !hasKey(keys, "sample.Reader.Read/1") {
		t.Errorf("the call no longer names the interface: %v", keys)
	}
}

func TestAnEmbeddedInterfaceIsPartOfTheMethodSet(t *testing.T) {
	functions := translateSource(t, `
package sample

type Reader interface {
	Read(p []byte) (int, error)
}

type ReadWriter interface {
	Reader
	Write(p []byte) (int, error)
}

type both struct{}

func (b *both) Read(p []byte) (int, error)  { return len(p), nil }
func (b *both) Write(p []byte) (int, error) { return len(p), nil }

func use(rw ReadWriter) {
	buffer := make([]byte, 8)
	rw.Read(buffer)
}
`)
	use := functionNamed(t, functions, "use")
	keys := dispatchKeys(use)
	// The receiver's type is the EMBEDDING interface, so the
	// implementation has to hold the embedded interface's method too.
	if !hasKey(keys, "sample.both.Read/1") {
		t.Errorf("the call through the embedding interface did not reach the implementation: %v", keys)
	}
}

func TestAMethodWithTheSameNameAndADifferentSignatureIsNotTheMethod(t *testing.T) {
	functions := translateSource(t, `
package sample

type Reader interface {
	Read(p []byte) (int, error)
}

type wrong struct{}

func (w *wrong) Read(text string) (int, error) { return 0, nil }

func use(r Reader) {
	buffer := make([]byte, 8)
	r.Read(buffer)
}
`)
	use := functionNamed(t, functions, "use")
	keys := dispatchKeys(use)
	if hasKey(keys, "sample.wrong.Read/1") {
		t.Errorf("a type whose method has a different signature was taken for an implementation: %v", keys)
	}
}

func TestAMethodOnAPointerReceiverIsInThePointerMethodSet(t *testing.T) {
	functions := translateSource(t, `
package sample

type Reader interface {
	Read(p []byte) (int, error)
}

type pointerReader struct{}

func (p *pointerReader) Read(p2 []byte) (int, error) { return len(p2), nil }

func use(r Reader) {
	buffer := make([]byte, 8)
	r.Read(buffer)
}
`)
	use := functionNamed(t, functions, "use")
	keys := dispatchKeys(use)
	// A method declared on *T belongs to *T's method set and not to T's,
	// so only the POINTER satisfies the interface. The owner is the type
	// either way -- the pointer is stripped from the callee key, because
	// `(*T).M` and `T.M` are the same method.
	if !hasKey(keys, "sample.pointerReader.Read/1") {
		t.Errorf("the pointer receiver's method did not satisfy the interface: %v", keys)
	}
}

func TestACallOnAConcreteTypeNamesNoOtherType(t *testing.T) {
	functions := translateSource(t, `
package sample

type Reader interface {
	Read(p []byte) (int, error)
}

type fileReader struct{}

func (f *fileReader) Read(p []byte) (int, error) { return len(p), nil }

func use(f *fileReader) {
	buffer := make([]byte, 8)
	f.Read(buffer)
}
`)
	use := functionNamed(t, functions, "use")
	keys := dispatchKeys(use)
	// The receiver's type is CONCRETE, so the call names it and there is
	// nothing to dispatch: no other type may appear.
	if !hasKey(keys, "sample.fileReader.Read/1") {
		t.Errorf("the concrete call did not name its own type: %v", keys)
	}
	for _, key := range keys {
		if strings.HasPrefix(key, "sample.") && key != "sample.fileReader.Read/1" {
			t.Errorf("a concrete call named another type: %v", keys)
		}
	}
}

func TestACallThroughAnEmptyInterfaceDispatchesToNothing(t *testing.T) {
	functions := translateSource(t, `
package sample

type fileReader struct{}

func (f *fileReader) Read(p []byte) (int, error) { return len(p), nil }

func use(v any) {
	_ = v
}
`)
	use := functionNamed(t, functions, "use")
	keys := dispatchKeys(use)
	for _, key := range keys {
		if strings.HasPrefix(key, "sample.fileReader") {
			t.Errorf("a call through 'any' named a concrete type: %v", keys)
		}
	}
}
