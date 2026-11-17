package main

// The drop channel: what the translator could NOT lower faithfully.
//
// WHY THIS EXISTS. A construct that is not lowered is a construct the analysis
// does not understand. If that loss is silent, the scan reports success over a
// program it only half read, and the finding that was never raised cannot be
// distinguished from a finding that does not exist. So every loss is recorded
// here, with a stable machine-readable id, so that a coverage report can group
// the losses across languages and across runs.
//
// THE THREE THINGS A DROP SAYS.
//
//   - WHAT: a stable id, e.g. "go.select.readiness". The id names the
//     CONSTRUCT, never the variable part of it: no line numbers, no names, no
//     counts. A report groups by this id, so an id that carries a name would
//     split one construct into as many groups as it has occurrences.
//
//   - HOW BAD: the impact. It is what the engine derives the function's
//     precision from, so it must be exact:
//
//       SEMANTICS  the analysis does not know what the construct DOES. The
//                  function's precision becomes SOURCE_DEGRADED.
//       STRUCTURE  the construct's shape was lowered away but it still
//                  computes the same thing. SOURCE_LOWERED.
//       VALUE      exactly ONE value became unknown. The function keeps its
//                  precision.
//       TYPE       a type-level fact is unknown. The function keeps its
//                  precision.
//
//   - WHERE: the source position, and the scope the loss belongs to.
//
// A DROP IS NOT A FALLBACK. A construct that CAN be lowered must be lowered;
// recording a drop instead of doing the work would make the coverage report a
// measure of the translator's effort rather than of the program's shape.

import "strings"

// The drop impact levels.
const (
	// ImpactSemantics: the analysis does not know what the construct does.
	ImpactSemantics = "SEMANTICS"

	// ImpactStructure: the shape was lowered away, the meaning was not.
	ImpactStructure = "STRUCTURE"

	// ImpactValue: exactly one value became unknown.
	ImpactValue = "VALUE"

	// ImpactType: a type-level fact is unknown.
	ImpactType = "TYPE"
)

// The drop scopes.
const (
	// DropScopeFunction: the loss belongs to one function.
	DropScopeFunction = "FUNCTION"

	// DropScopeProgram: the loss belongs to the whole program: a member kind
	// that was never reached, a build fact, a package rule.
	DropScopeProgram = "PROGRAM"
)

// The Go construct ids.
//
// They are stable: a report groups by them, so renaming one silently splits a
// coverage series. The prefix is the language, because the id vocabulary is
// shared across sidecars and a Go loss must not be counted as a C# one.
const (
	// DropGoPackageVisibility: Go's package-level visibility rule (".../internal/...")
	// is not implemented. The engine treats an internal package as a public
	// one, which over-approximates.
	DropGoPackageVisibility = "go.pkg.visibility"

	// DropGoVendoring: the sidecar does not read a vendor directory. A
	// dependency has to be named by the caller, with export data or a source
	// directory. A vendored dependency that is not named is not in the TAC at
	// all.
	DropGoVendoring = "go.pkg.vendoring"

	// DropGoEmbedValue: the bytes of an embedded file are a build-time fact.
	DropGoEmbedValue = "go.embed.value"

	// DropGoVersion: the Go version the program was built with is unknown, so
	// the conservative side is taken for the one thing the version decides.
	DropGoVersion = "go.version"

	// DropGoSelectReadiness: which select case is ready is a run-time fact.
	DropGoSelectReadiness = "go.select.readiness"

	// DropGoSelectChain: a select is lowered into a chain of guarded
	// branches.
	DropGoSelectChain = "go.select.chain"

	// DropGoLockContention: which goroutine enters a critical section first
	// is a run-time fact.
	DropGoLockContention = "go.lock.contention"

	// DropGoConcurrencyOrder: how goroutines interleave is a run-time fact.
	DropGoConcurrencyOrder = "go.concurrency.order"

	// DropGoEvaluationOrder: Go leaves the evaluation order of a call's
	// arguments unspecified. One order is fixed here.
	DropGoEvaluationOrder = "go.evalOrder"

	// DropGoComplexLiteral: the TAC has no complex kind.
	DropGoComplexLiteral = "go.complex.literal"

	// DropGoReflectValue: a reflect read or write is a black box.
	DropGoReflectValue = "go.reflect.value"

	// DropGoUnsafePointer: an unsafe pointer's target is not modelled.
	DropGoUnsafePointer = "go.unsafe.pointer"

	// DropGoDeferOrder: a deferred call runs when the function exits, not
	// where it is written.
	DropGoDeferOrder = "go.defer.order"

	// DropGoClosureCapture: a closure's captures travel as leading
	// parameters, so a write back to the captured variable is not modelled
	// as a write to the original.
	DropGoClosureCapture = "go.closure.capture"

	// DropGoSwitchChain: a switch is lowered into a branch chain.
	DropGoSwitchChain = "go.switch.chain"

	// DropGoTypeSwitchNarrowing: a type switch does not narrow the bound
	// variable's type.
	DropGoTypeSwitchNarrowing = "go.typeSwitch.narrowing"

	// DropGoPointerCell: a pointer is modelled as a cell object, so a write
	// through a pointer and a write to the original are two shapes until the
	// engine joins them.
	DropGoPointerCell = "go.pointer.cell"

	// DropGoFuncValue: a function value is modelled as a synthetic closure.
	DropGoFuncValue = "go.funcValue"

	// DropGoStructPositional: a positional composite literal binds its
	// elements by field order.
	DropGoStructPositional = "go.struct.positional"

	// DropGoMapContents: a map's contents are modelled as calls on a
	// synthetic map type.
	DropGoMapContents = "go.map.contents"

	// DropGoUnmodelled: a construct the translator has no lowering for at
	// all. The detail says which one.
	DropGoUnmodelled = "go.unmodelled"

	// DropGoClearSemantics: `clear` removes the elements, and the TAC has
	// no operation for that yet.
	DropGoClearSemantics = "go.clear.semantics"
)

// The member kinds a function can be.
//
// The kind decides what a reader may conclude from the function: a method is
// called through a receiver, a closure is a value, an initializer runs before
// anything else in the package.
const (
	memberKindFunction          = "function"
	memberKindMethod            = "method"
	memberKindClosure           = "closure"
	memberKindStaticInitializer = "staticInitializer"
)

// cellTypeName is the name of the synthetic cell type.
//
// A cell is the object a Go pointer is modelled as: one field, holding the
// value the pointer points at. The name is fixed, because every cell is the
// SAME type: a write through one pointer and a read through another must
// land on the same field name, or the two would be unrelated values.
const cellTypeName = "go.Cell"

// domainOfType returns the numeric domain of a type, in the text form the
// wire carries.
//
// The domain is a property of the VALUES a type admits, and it is what a
// range check needs: a checker that knows a value is a 32-bit signed integer
// can compare it against a bound without asking the type system first.
//
// A type with no numeric domain says nothing about its values. That is not
// the same as saying the values are unconstrained: a string has no numeric
// domain, and a string is not unconstrained.
func domainOfType(typeRef *TypeRef) string {
	if typeRef == nil {
		return ""
	}
	switch typeRef.Kind {
	case TypeBoolean:
		return ""
	case TypeByte:
		return "INTEGRAL(8,signed)"
	case TypeShort:
		return "INTEGRAL(16,signed)"
	case TypeChar:
		return "INTEGRAL(16,signed)"
	case TypeInt:
		return "INTEGRAL(32,signed)"
	case TypeLong:
		return "INTEGRAL(64,signed)"
	case TypeFloat:
		return "FLOATING(24,8)"
	case TypeDouble:
		return "FLOATING(53,11)"
	case TypeDecimal:
		return "FIXEDPOINT(128,28)"
	}
	return ""
}

// A drop: a construct that was not lowered faithfully.
type Drop struct {
	// The stable machine-readable id of the construct.
	Construct string `json:"construct"`

	// A human-readable description of what was lost.
	Detail string `json:"detail"`

	// SEMANTICS, STRUCTURE, VALUE or TYPE.
	Impact string `json:"impact"`

	// The source line. -1 when unknown.
	Line int `json:"line"`

	// The source column. -1 when unknown.
	Column int `json:"column"`

	// FUNCTION or PROGRAM.
	Scope string `json:"scope"`
}

// A try region: a try/catch/finally shape inside a function.
//
// The shape is what lets the engine build the exceptional edges. Without it a
// catch body has to be treated as ordinary code, and the ordinary code is the
// part that does not run.
type TryRegion struct {
	// The region's id, unique within the function.
	ID string `json:"id"`

	// The index of the first instruction of the protected region, or -1.
	//
	// An INDEX, not a label. The engine's CFG builder asks which instruction RANGE a
	// region covers, and a label is not a range. A region written as labels arrives
	// empty, the builder wires no exceptional edge, and a catch body is unreachable on
	// the exception path.
	TryStart int `json:"tryStart"`

	// The index of the last instruction of the protected region, or -1.
	TryEnd int `json:"tryEnd"`

	// The index of the first instruction of the catch handlers, or -1.
	CatchStart int `json:"catchStart"`

	// The index of the last instruction of the catch handlers, or -1.
	CatchEnd int `json:"catchEnd"`

	// The labels of the catch handlers, in source order.
	CatchLabels []string `json:"catchLabels,omitempty"`

	// The exception types the handlers catch, in the same order as the
	// catch labels. An empty string catches everything.
	CatchTypes []string `json:"catchTypes,omitempty"`

	// The label of the finally block.
	FinallyLabel string `json:"finallyLabel,omitempty"`

	// The index of the first instruction of the finally block, or -1.
	FinallyStart int `json:"finallyStart,omitempty"`

	// The index of the last instruction of the finally block, or -1.
	FinallyEnd int `json:"finallyEnd,omitempty"`

	// The labels the finally block runs on the way out: the normal exit,
	// every catch's exit, and the rethrow's exit.
	FinallyExits []string `json:"finallyExits,omitempty"`

	// The variables a synthetic finally owns, for a `using` or a `defer`.
	ResourceVars []string `json:"resourceVars,omitempty"`

	// Whether the finally was synthesized rather than written.
	SyntheticFinally bool `json:"syntheticFinally,omitempty"`
}

// A scope binding: a local-scope name and the slot it lives in.
//
// The TAC's variables are slots, and a name is a source-level alias for one.
// The binding is what lets a reader trace a source name to the slot the
// engine reasons about.
type ScopeBinding struct {
	// The source-level name.
	Name string `json:"name"`

	// The slot the name is bound to.
	Slot string `json:"slot"`

	// The declared type of the binding, when known.
	Type *TypeRef `json:"type,omitempty"`

	// Whether the binding is a parameter rather than a local.
	Parameter bool `json:"parameter,omitempty"`
}

// A type declaration: a named type the program declares.
type TypeDecl struct {
	// The fully qualified name.
	FQN string `json:"fqn"`

	// Whether the type is an interface.
	IsInterface bool `json:"isInterface,omitempty"`

	// Whether the type is a map. A map's contents are modelled as calls on
	// a synthetic map type, so the fact that it IS a map has to be carried
	// somewhere.
	IsMap bool `json:"isMap,omitempty"`

	// Whether the type is a channel, and which way the channel goes.
	IsChan bool `json:"isChan,omitempty"`

	// The channel's direction: "both", "send" or "recv".
	ChanDir string `json:"chanDir,omitempty"`

	// The channel's capacity, when it is a constant.
	Capacity *int `json:"capacity,omitempty"`

	// The type's fields, in declaration order.
	Fields []FieldDecl `json:"fields,omitempty"`

	// The type's methods, by name.
	Members []string `json:"members,omitempty"`
}

// A field of a type declaration.
type FieldDecl struct {
	// The field's name.
	Name string `json:"name"`

	// The field's type.
	Type *TypeRef `json:"type,omitempty"`

	// The field's struct tag, verbatim.
	//
	// A struct tag is a SOURCE-LEVEL serialization contract: it says which
	// key the field is written under when the value is encoded. It is not
	// an annotation, and it does not belong in the annotation slots: the
	// two are different things and a report that merges them says
	// something that is not true of either.
	Tag string `json:"tag,omitempty"`
}

// programDrops accumulates the losses that belong to the whole program
// rather than to one function: a member kind that was never reached, a build
// fact the sidecar does not read.
//
// It is a package-level accumulator because the program-level facts are
// discovered while translating, and translating a file is not the same as
// translating a program: the same file can be translated twice.
var programDrops = []Drop{}

// recordProgramDrop records a loss that belongs to the whole program.
//
// The same loss is recorded once: the program is scanned file by file, and
// the same build fact is met again in every file.
func recordProgramDrop(construct string, detail string, impact string) {
	for _, existing := range programDrops {
		if existing.Construct == construct && existing.Detail == detail {
			return
		}
	}
	programDrops = append(programDrops, Drop{
		Construct: construct,
		Detail:    detail,
		Impact:    impact,
		Line:      -1,
		Column:    -1,
		Scope:     DropScopeProgram,
	})
}

// takeProgramDrops returns the program-level drops and clears the
// accumulator, so that the next file starts from an empty one.
func takeProgramDrops() []Drop {
	drops := programDrops
	programDrops = []Drop{}
	return drops
}

// dropIds returns the construct ids of a drop list, for reporting.
func dropIds(drops []Drop) string {
	ids := make([]string, 0, len(drops))
	for _, drop := range drops {
		ids = append(ids, drop.Construct)
	}
	return strings.Join(ids, ", ")
}
