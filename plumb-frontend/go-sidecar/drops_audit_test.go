package main

// The audit of the drop vocabulary.
//
// A drop id is a promise: a coverage report groups by it, so an id that is
// registered but never produced is a group that can never appear.  Twelve of
// them were registered and never produced, and this file records what each
// one turned out to be:
//
//   - seven of them were REAL losses that nothing recorded, and each one now
//     has a producer;
//   - two of them were real losses that were recorded under the generic
//     `go.unmodelled` id, and they are now recorded under their own;
//   - three of them were dead: the loss they described does not exist, or
//     the premise they stated is false.  They are gone.
//
// THE META TEST IS THE POINT.  `TestEveryRegisteredDropIdHasAProducer` reads
// the vocabulary and checks that every id in it is produced somewhere.  It
// would have failed before this round, and it fails the next time an id is
// registered without a producer -- which is the only way that failure is
// ever going to be caught.

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// registeredDropIds returns the constants the vocabulary registers.
func registeredDropIds(t *testing.T) map[string]string {
	t.Helper()

	raw, err := os.ReadFile("drops.go")
	if err != nil {
		t.Fatalf("the vocabulary could not be read: %v", err)
	}
	pattern := regexp.MustCompile(`(?m)^\t(DropGo\w+)\s*=\s*"([^"]+)"`)
	registered := map[string]string{}
	for _, match := range pattern.FindAllStringSubmatch(string(raw), -1) {
		registered[match[1]] = match[2]
	}
	if len(registered) == 0 {
		t.Fatal("the vocabulary registers no id at all")
	}
	return registered
}

// TestEveryRegisteredDropIdHasAProducer checks that no id is registered
// without something that produces it.
//
// A comment that names a constant is not a producer, and a test is not one
// either: a test READS an id, it never produces one.  Both are excluded, so
// the check cannot be satisfied by writing the name down.
func TestEveryRegisteredDropIdHasAProducer(t *testing.T) {
	registered := registeredDropIds(t)

	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatalf("the package directory could not be read: %v", err)
	}
	produced := map[string]bool{}
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".go") {
			continue
		}
		if name == "drops.go" || strings.HasSuffix(name, "_test.go") {
			continue
		}
		body, err := os.ReadFile(name)
		if err != nil {
			t.Fatalf("the file %s could not be read: %v", name, err)
		}
		for _, line := range strings.Split(string(body), "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "//") {
				continue
			}
			for constant := range registered {
				if strings.Contains(line, constant) {
					produced[constant] = true
				}
			}
		}
	}

	missing := []string{}
	for constant, id := range registered {
		if !produced[constant] {
			missing = append(missing, constant+" = "+id)
		}
	}
	if len(missing) > 0 {
		t.Errorf("these ids are registered and never produced: %v", missing)
	}
}

// TestATypeSwitchRecordsTheLossOfTheNarrowing checks the narrowing loss.
//
// The bound variable is bound to the value the switch tested, so the
// concrete type inside a case is not known to the analysis.  That is a
// TYPE-level loss: the function keeps its precision.
func TestATypeSwitchRecordsTheLossOfTheNarrowing(t *testing.T) {
	functions := translateSource(t, `
package sample

func classify(value interface{}) string {
	switch typed := value.(type) {
	case string:
		return typed
	default:
		return ""
	}
}
`)

	classify := functionNamed(t, functions, "classify")

	drop, ok := dropOf(classify, DropGoTypeSwitchNarrowing)
	if !ok {
		t.Fatalf("the type switch recorded no loss of the narrowing: %v", dropIdsOf(classify))
	}
	if drop.Impact != ImpactType {
		t.Errorf("the loss's impact is %q, want %q", drop.Impact, ImpactType)
	}
}

// TestAClosureThatWritesToACapturedVariableRecordsTheLoss checks the capture
// loss.
//
// The capture travels as a leading parameter, so a write inside the closure
// lands on the closure's own copy and the enclosing function's variable
// keeps the value it had before the closure ran.  The loss belongs to the
// ENCLOSING function, because the enclosing function is the one whose
// variable is stale.
func TestAClosureThatWritesToACapturedVariableRecordsTheLoss(t *testing.T) {
	functions := translateSource(t, `
package sample

func run(command string) {
	held := ""
	assign := func() {
		held = command
	}
	assign()
	_ = held
}
`)

	run := functionNamed(t, functions, "run")

	if !hasDrop(run, DropGoClosureCapture) {
		t.Errorf("the write to the captured variable recorded no loss: %v", dropIdsOf(run))
	}
}

// TestAClosureThatOnlyReadsACapturedVariableRecordsNoLoss checks the other
// side of the same boundary.
//
// A closure that READS a captured variable gets the value the caller passed,
// and that is right: nothing is lost, so nothing is recorded.
func TestAClosureThatOnlyReadsACapturedVariableRecordsNoLoss(t *testing.T) {
	functions := translateSource(t, `
package sample

func run(command string) {
	held := ""
	read := func() string {
		return held
	}
	_ = read()
}
`)

	run := functionNamed(t, functions, "run")

	if hasDrop(run, DropGoClosureCapture) {
		t.Errorf("a read of a captured variable recorded a loss: %v", dropIdsOf(run))
	}
}

// TestAGoroutineRecordsTheOrderItRunsIn checks the concurrency loss.
//
// The call a `go` statement names is emitted where the statement is written,
// and Go runs it on another goroutine, at a time the analysis does not
// model.
func TestAGoroutineRecordsTheOrderItRunsIn(t *testing.T) {
	functions := translateSource(t, `
package sample

func work() {
}

func start() {
	go work()
}
`)

	start := functionNamed(t, functions, "start")

	drop, ok := dropOf(start, DropGoConcurrencyOrder)
	if !ok {
		t.Fatalf("the goroutine recorded no loss of the order: %v", dropIdsOf(start))
	}
	if drop.Impact != ImpactStructure {
		t.Errorf("the loss's impact is %q, want %q", drop.Impact, ImpactStructure)
	}
}

// TestAMapWriteRecordsThatTheContentsAreNotTracked checks the map loss.
//
// A value put into a map does not come back out of it.
func TestAMapWriteRecordsThatTheContentsAreNotTracked(t *testing.T) {
	functions := translateSource(t, `
package sample

func store(table map[string]string, value string) {
	table["key"] = value
}
`)

	store := functionNamed(t, functions, "store")

	drop, ok := dropOf(store, DropGoMapContents)
	if !ok {
		t.Fatalf("the map write recorded no loss of the contents: %v", dropIdsOf(store))
	}
	if drop.Impact != ImpactValue {
		t.Errorf("the loss's impact is %q, want %q", drop.Impact, ImpactValue)
	}
}

// TestAMapRangeIsRecordedUnderItsOwnId checks the re-labelling.
//
// The loss was already reported -- the map range says so in its own words --
// but it was reported under the generic `go.unmodelled` id, so a coverage
// report could not tell it apart from any other construct it did not
// understand.
func TestAMapRangeIsRecordedUnderItsOwnId(t *testing.T) {
	functions := translateSource(t, `
package sample

func walk(table map[string]string) {
	for key, value := range table {
		_, _ = key, value
	}
}
`)

	walk := functionNamed(t, functions, "walk")

	if !hasDrop(walk, DropGoMapContents) {
		t.Errorf("the map range recorded no loss of the contents: %v", dropIdsOf(walk))
	}
	if hasDrop(walk, DropGoUnmodelled) {
		t.Errorf("the map range was recorded under the generic id: %v", dropIdsOf(walk))
	}
}

// TestAFunctionValueIsRecordedUnderItsOwnId checks the other re-labelling.
func TestAFunctionValueIsRecordedUnderItsOwnId(t *testing.T) {
	functions := translateSource(t, `
package sample

func target() {
}

func use() {
	handler := target
	_ = handler
}
`)

	use := functionNamed(t, functions, "use")

	if !hasDrop(use, DropGoFuncValue) {
		t.Errorf("the function value recorded no loss: %v", dropIdsOf(use))
	}
	if hasDrop(use, DropGoUnmodelled) {
		t.Errorf("the function value was recorded under the generic id: %v", dropIdsOf(use))
	}
}

// TestAPositionalStructLiteralRecordsNoLoss checks a DEAD entry.
//
// A positional composite literal binds its elements to the fields in
// declaration order, and the sidecar reads the fields from the type: the
// element is stored under the field's own name, so nothing is lost.  The id
// that claimed otherwise was removed, and this test is what says it is gone.
func TestAPositionalStructLiteralRecordsNoLoss(t *testing.T) {
	functions := translateSource(t, `
package sample

type point struct {
	X int
	Y int
}

func build() point {
	return point{1, 2}
}
`)

	build := functionNamed(t, functions, "build")

	for _, id := range dropIdsOf(build) {
		if id == "go.struct.positional" {
			t.Errorf("the positional struct literal recorded the loss the removed id described: %v",
				dropIdsOf(build))
		}
	}
}

// TestTheLoopVariableScopeIsRecordedWhenTheVersionIsUnknown checks the
// version-dependent loss.
//
// The range variable is modelled as one slot shared by every iteration, and
// a closure that captures it and outlives the iteration sees the wrong
// value under that model.  Which rule applies depends on the version, and
// an unknown version is a loss too.
func TestTheLoopVariableScopeIsRecordedWhenTheVersionIsUnknown(t *testing.T) {
	functions := translateSource(t, `
package sample

func walk(items []string) {
	for _, item := range items {
		func() {
			_ = item
		}()
	}
}
`)

	walk := functionNamed(t, functions, "walk")

	if !hasDrop(walk, DropGoLoopVarScope) {
		t.Errorf("the captured range variable recorded no loss of the scope: %v", dropIdsOf(walk))
	}
}

// TestTheLoopVariableScopeIsNotRecordedOnAnOlderVersion checks the other
// side: on a version whose rule is the one the sidecar models, there is no
// loss and nothing is recorded.
func TestTheLoopVariableScopeIsNotRecordedOnAnOlderVersion(t *testing.T) {
	functions := translateSourceWithVersion(t, `
package sample

func walk(items []string) {
	for _, item := range items {
		func() {
			_ = item
		}()
	}
}
`, "go1.21")

	walk := functionNamed(t, functions, "walk")

	if hasDrop(walk, DropGoLoopVarScope) {
		t.Errorf("a version whose rule the sidecar models recorded a loss: %v", dropIdsOf(walk))
	}
}

// TestTheLoopVariableScopeIsRecordedOnANewerVersion checks the version the
// loss is really about.
func TestTheLoopVariableScopeIsRecordedOnANewerVersion(t *testing.T) {
	functions := translateSourceWithVersion(t, `
package sample

func walk(items []string) {
	for _, item := range items {
		func() {
			_ = item
		}()
	}
}
`, "go1.22")

	walk := functionNamed(t, functions, "walk")

	if !hasDrop(walk, DropGoLoopVarScope) {
		t.Errorf("the captured range variable recorded no loss of the scope: %v", dropIdsOf(walk))
	}
}

// openSessionWithSource opens a project with one source file.
//
// It goes through the session rather than the translator, because a source
// that imports the standard library needs the toolchain's own copy of it.
func openSessionWithSource(t *testing.T, source string) (*ProjectSession, string) {
	t.Helper()
	return openSessionWithVersion(t, source, "")
}

// openSessionWithVersion opens a project with one source file, built with a
// given Go version.
func openSessionWithVersion(t *testing.T, source string, version string) (*ProjectSession, string) {
	t.Helper()

	directory := t.TempDir()
	path := filepath.Join(directory, "sample.go")
	protocol := ProtocolVersionValue
	request := &Request{
		Op:              "openProject",
		ProtocolVersion: &protocol,
		ProjectID:       "audit",
		Sources:         []SourceFile{{Path: path, Text: source}},
		Packages: []PackageSpec{{
			ImportPath: "sample",
			Dir:        directory,
			Files:      []string{path},
			GoVersion:  version,
		}},
	}
	session, err := Open(request)
	if err != nil {
		t.Fatalf("the project could not be opened: %v", err)
	}
	return session, path
}

// TestTheRangeVariableIsOneSlotSharedByEveryIteration pins the model's shape.
//
// The sidecar declares the range variable ONCE and writes to it on every
// iteration, which is the rule of the versions before Go 1.22: one variable,
// shared by every iteration.  The losses that follow from that shape are
// recorded by the drops, and this test is what says which model they are
// about -- the ledger had the opposite one written down, and a wrong model
// in the ledger is a wrong model in the next round's plan.
func TestTheRangeVariableIsOneSlotSharedByEveryIteration(t *testing.T) {
	functions := translateSource(t, `
package sample

func walk(items []string) {
	for _, item := range items {
		_ = item
	}
}
`)

	walk := functionNamed(t, functions, "walk")

	declared := 0
	for _, local := range walk.Locals {
		if local.Name == "item" {
			declared++
		}
	}
	if declared != 1 {
		t.Errorf("the range variable is declared %d time(s), want the one shared slot", declared)
	}

	writes := 0
	for _, instruction := range walk.Instructions {
		if instruction.Kind == KindMove && instruction.Dest != nil && instruction.Dest.Name == "item" {
			writes++
		}
	}
	if writes == 0 {
		t.Errorf("the range variable is never written: %v", kindsOf(walk))
	}
}

// TestAnUnknownGoVersionIsRecordedAsAProgramDrop checks the version loss.
//
// The version decides the scope of a range variable, and it is a build fact:
// the caller is the one that knows it.  When the caller did not say, the
// sidecar does not know which rule applies -- and that is a program-level
// fact, because it is the whole program that was built without one.
func TestAnUnknownGoVersionIsRecordedAsAProgramDrop(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

func use() {
}
`)

	session.Translate(path)
	drops := takeProgramDrops()

	found := false
	for _, drop := range drops {
		if drop.Construct == DropGoVersion {
			found = true
			if drop.Scope != DropScopeProgram {
				t.Errorf("the unknown version's loss has the scope %q, want %q",
					drop.Scope, DropScopeProgram)
			}
		}
	}
	if !found {
		t.Errorf("the unknown Go version recorded no loss: %v", drops)
	}
}

// TestAKnownGoVersionRecordsNoVersionLoss checks the other side of the same
// fact: a version the caller DID say leaves nothing to record.
func TestAKnownGoVersionRecordsNoVersionLoss(t *testing.T) {
	session, path := openSessionWithVersion(t, `
package sample

func use() {
}
`, "go1.22")

	session.Translate(path)
	drops := takeProgramDrops()

	for _, drop := range drops {
		if drop.Construct == DropGoVersion {
			t.Errorf("a known Go version recorded the loss: %v", drops)
		}
	}
}

// TestACallIntoReflectIsRecordedAsABlackBox checks the reflect loss, on the
// path where the sidecar CALLS a function of the reflect package.
//
// The engine treats a call it has no body for as preserving its arguments'
// taint, and that covers the VALUE reflect carries.  It does not cover a
// field reflect digs out of a struct.
func TestACallIntoReflectIsRecordedAsABlackBox(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

import "reflect"

func dig(value interface{}) reflect.Value {
	return reflect.ValueOf(value)
}
`)

	functions, _ := session.Translate(path)
	dig := functionNamed(t, functions, "dig")

	drop, ok := dropOf(dig, DropGoReflectValue)
	if !ok {
		t.Fatalf("the call into reflect recorded no loss: %v", dropIdsOf(dig))
	}
	if drop.Impact != ImpactSemantics {
		t.Errorf("the loss's impact is %q, want %q", drop.Impact, ImpactSemantics)
	}
}

// TestAMethodOnAReflectValueIsRecordedAsABlackBox checks the OTHER path
// into reflect: a method called on a value of a reflect type.
//
// The two paths have their own producers, and a test that only pins one of
// them cannot see the other being switched off.  This one pins the method
// path, and the one above pins the package-function path.
func TestAMethodOnAReflectValueIsRecordedAsABlackBox(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

import "reflect"

func text(value reflect.Value) string {
	return value.String()
}
`)

	functions, _ := session.Translate(path)
	text := functionNamed(t, functions, "text")

	if !hasDrop(text, DropGoReflectValue) {
		t.Errorf("the method on a reflect value recorded no loss: %v", dropIdsOf(text))
	}
}

// TestAConversionIntoUnsafeIsRecordedAsABlackBox checks the unsafe loss.
func TestAConversionIntoUnsafeIsRecordedAsABlackBox(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

import "unsafe"

func raw(value *int) unsafe.Pointer {
	return unsafe.Pointer(value)
}
`)

	functions, _ := session.Translate(path)
	raw := functionNamed(t, functions, "raw")

	if !hasDrop(raw, DropGoUnsafePointer) {
		t.Errorf("the conversion into unsafe recorded no loss: %v", dropIdsOf(raw))
	}
}

// TestALockIsRecordedAsACriticalSectionTheAnalysisDoesNotOrder checks the
// lock loss.
//
// A lock is lowered as an ordinary call, and the analysis models no mutual
// exclusion.
func TestALockIsRecordedAsACriticalSectionTheAnalysisDoesNotOrder(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

import "sync"

func guard(mutex *sync.Mutex) {
	mutex.Lock()
	mutex.Unlock()
}
`)

	functions, _ := session.Translate(path)
	guard := functionNamed(t, functions, "guard")

	drop, ok := dropOf(guard, DropGoLockContention)
	if !ok {
		t.Fatalf("the lock recorded no loss of the ordering: %v", dropIdsOf(guard))
	}
	if drop.Impact != ImpactStructure {
		t.Errorf("the loss's impact is %q, want %q", drop.Impact, ImpactStructure)
	}
}

// TestAnEmbeddedResourceIsRecordedAsAProgramDrop checks the embed loss.
//
// The directive `//go:embed` names a file whose CONTENT becomes the
// variable's value, and the sidecar reads the source and not the build.  The
// loss belongs to the PROGRAM: no function of the file owns the resource.
func TestAnEmbeddedResourceIsRecordedAsAProgramDrop(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

import _ "embed"

//go:embed secret.txt
var secret []byte

func use() {
	_ = secret
}
`)

	session.Translate(path)
	drops := takeProgramDrops()

	found := false
	for _, drop := range drops {
		if drop.Construct == DropGoEmbedValue {
			found = true
			if drop.Scope != DropScopeProgram {
				t.Errorf("the embedded resource's loss has the scope %q, want %q",
					drop.Scope, DropScopeProgram)
			}
		}
	}
	if !found {
		t.Errorf("the embedded resource recorded no loss: %v", drops)
	}
}

// TestAFileWithoutAnEmbeddedResourceRecordsNoProgramDrop checks the other
// side of the embed one.
func TestAFileWithoutAnEmbeddedResourceRecordsNoProgramDrop(t *testing.T) {
	session, path := openSessionWithSource(t, `
package sample

var plain string

func use() {
	_ = plain
}
`)

	session.Translate(path)
	drops := takeProgramDrops()

	for _, drop := range drops {
		if drop.Construct == DropGoEmbedValue {
			t.Errorf("a file with no embedded resource recorded the loss: %v", drops)
		}
	}
}
