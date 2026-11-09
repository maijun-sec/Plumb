package main

// The wire model of the TAC instruction stream.
//
// These types mirror Plumb's TAC IR one-for-one, but they are a WIRE MODEL,
// not the IR itself: they live on the Go side of a process boundary, so they
// cannot be the Java classes. The Java side maps them into
// org.plumb.tac.ir.TacInstruction and friends.
//
// See README.md for the contract. The field names are the JSON property
// names, so they are lower-camel-cased to match the wire format.

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// The TAC instruction kinds, by name.
//
// The list is the TAC's own: twenty-two kinds, no more and no fewer. A kind
// that this sidecar does not emit is still declared, because the Java side
// switches on the same enum and a typo in a string literal would otherwise
// surface as an "unknown instruction" at run time.
const (
	KindMove          = "MOVE"
	KindBinOp         = "BINOP"
	KindUnaryOp       = "UNARYOP"
	KindCall          = "CALL"
	KindNew           = "NEW"
	KindNewArray      = "NEWARRAY"
	KindArrayLoad     = "ARRAYLOAD"
	KindArrayStore    = "ARRAYSTORE"
	KindFieldLoad     = "FIELDLOAD"
	KindFieldStore    = "FIELDSTORE"
	KindInstanceOf    = "INSTANCEOF"
	KindBranch        = "BRANCH"
	KindJump          = "JUMP"
	KindLabel         = "LABEL"
	KindReturn        = "RETURN"
	KindThrow         = "THROW"
	KindNop           = "NOP"
	KindCatchHandler  = "CATCH_HANDLER"
	KindFinallyEnter  = "FINALLY_ENTER"
	KindNullCheck     = "NULL_CHECK"
	KindHavoc         = "HAVOC"
	KindUninterpreted = "UNINTERPRETED"
)

// The TAC type kinds, by name.
//
// The TAC has fourteen kinds plus the "unknown" sentinel. UNKNOWN is not one
// of the fourteen: it is what a sidecar emits when it cannot resolve a type
// at all, and the Java side maps it onto the IR's UNKNOWN, which is the same
// idea as the C# sidecar's default.
const (
	TypeBoolean = "BOOLEAN"
	TypeByte    = "BYTE"
	TypeShort   = "SHORT"
	TypeChar    = "CHAR"
	TypeInt     = "INT"
	TypeLong    = "LONG"
	TypeFloat   = "FLOAT"
	TypeDouble  = "DOUBLE"
	TypeDecimal = "DECIMAL"
	TypeString  = "STRING"
	TypeClass   = "CLASS"
	TypeArray   = "ARRAY"
	TypeNull    = "NULL"
	TypeVoid    = "VOID"
	TypeUnknown = "UNKNOWN"
)

// The precision levels a function can carry.
//
// SOURCE_UNKNOWN is deliberately absent. The C# sidecar emits exactly two
// values, SOURCE_FULL and SOURCE_LOWERED, and the TAC's own precision enum
// has no third source-level value. Inventing one here would produce a
// precision string the Java side cannot map, and an unmappable precision is
// indistinguishable from "the sidecar lost track of this function".
const (
	PrecisionFull    = "SOURCE_FULL"
	PrecisionLowered = "SOURCE_LOWERED"
)

// The versions this sidecar speaks.
//
// The Java side refuses a sidecar whose version it does not understand, and
// it refuses one that reports NO version at all. The two halves are built
// independently, and half-understanding each other is how a finding goes
// missing without a trace -- so the check is loud in both directions.
const (
	// ProtocolVersionValue is the wire protocol version.
	//
	// Bump it when the request or the response shape changes in a way an
	// older frontend would misinterpret. A change an older frontend can
	// still read correctly is NOT a version bump, because a field it does
	// not know is ignored.
	ProtocolVersionValue = 1

	// IRVersionValue is the IR version this sidecar was built against.
	//
	// It travels with the wire so that a sidecar built against a different
	// IR is refused rather than half-understood.
	IRVersionValue = 1
)

// The problem kinds a function or a project can report.
//
// PARSE_PROBLEM and TRANSLATION_ERROR mirror the C# sidecar's kinds.
// UNHANDLED_NODE is a construct that has no TAC lowering at all.
// SOURCE_LOWERED is a construct that HAS a lowering, but not a faithful one:
// the semantics survive, the structure does not.
const (
	ProblemParse          = "PARSE_PROBLEM"
	ProblemTranslation    = "TRANSLATION_ERROR"
	ProblemUnhandledNode  = "UNHANDLED_NODE"
	ProblemSourceLowered  = "SOURCE_LOWERED"
)

// A type in the wire model.
type TypeRef struct {
	// The TAC type kind: BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT,
	// DOUBLE, DECIMAL, STRING, CLASS, ARRAY, NULL, VOID or UNKNOWN.
	Kind string `json:"kind"`

	// The fully qualified name, for CLASS.
	Name string `json:"name,omitempty"`

	// The component type, for ARRAY.
	Component *TypeRef `json:"component,omitempty"`

	// Whether the reference may hold null.
	//
	// Go has no nullability annotations: a pointer type may always hold nil
	// and an interface value may always hold a nil interface. So this sidecar
	// NEVER sets it, and the field is absent on the wire. It is declared so
	// that a future Go dialect with nullability annotations has somewhere to
	// go; the C# sidecar makes the same choice for the same reason.
	Nullable *bool `json:"nullable,omitempty"`
}

// Simple builds a type reference of a primitive kind.
func Simple(kind string) *TypeRef {
	return &TypeRef{Kind: kind}
}

// Class builds a CLASS type reference.
func Class(name string) *TypeRef {
	return &TypeRef{Kind: TypeClass, Name: name}
}

// Array builds an ARRAY type reference.
func Array(component *TypeRef) *TypeRef {
	return &TypeRef{Kind: TypeArray, Component: component}
}

// An operand in the wire model.
type Operand struct {
	// VAR or CONST.
	Kind string `json:"kind"`

	// The variable's name, for VAR.
	Name string `json:"name,omitempty"`

	// The variable's type, for VAR. Absent means UNKNOWN.
	Type *TypeRef `json:"type,omitempty"`

	// Whether the variable is a synthetic temporary.
	IsTemp bool `json:"isTemp,omitempty"`

	// The constant's kind, for CONST: INT, LONG, FLOAT, DOUBLE, BOOL, CHAR,
	// STRING or NULL.
	ConstKind string `json:"constKind,omitempty"`

	// The constant's value, for CONST. A number's or a boolean's text form.
	Value string `json:"value,omitempty"`
}

// Var builds a VAR operand.
func Var(name string, typ *TypeRef) *Operand {
	return &Operand{Kind: "VAR", Name: name, Type: typ}
}

// Const builds a CONST operand.
func Const(constKind string, value string) *Operand {
	return &Operand{Kind: "CONST", ConstKind: constKind, Value: value}
}

// An instruction in the wire model.
type Instruction struct {
	// The TAC instruction kind, by name.
	Kind string `json:"kind"`

	// The destination operand.
	Dest *Operand `json:"dest,omitempty"`

	// The first source operand.
	Lhs1 *Operand `json:"lhs1,omitempty"`

	// The second source operand.
	Lhs2 *Operand `json:"lhs2,omitempty"`

	// The binary operator, for BINOP.
	BinOp string `json:"binop,omitempty"`

	// The unary operator, for UNARYOP.
	UnOp string `json:"unop,omitempty"`

	// The cast's target type, for UNARYOP with the CAST operator.
	CastType *TypeRef `json:"castType,omitempty"`

	// The callee signature, for CALL and UNINTERPRETED: owner.method/arity.
	CalleeSig string `json:"calleeSig,omitempty"`

	// The argument list, for CALL, NEW and UNINTERPRETED.
	Args []Operand `json:"args,omitempty"`

	// The constructed or caught type, for NEW, NEWARRAY, INSTANCEOF and
	// CATCH_HANDLER.
	CtorType *TypeRef `json:"ctorType,omitempty"`

	// The field name, for FIELDLOAD and FIELDSTORE.
	FieldName string `json:"fieldName,omitempty"`

	// The true-branch label, for BRANCH.
	TrueLabel string `json:"trueLabel,omitempty"`

	// The false-branch label, for BRANCH.
	FalseLabel string `json:"falseLabel,omitempty"`

	// The label, for JUMP and LABEL.
	Label string `json:"label,omitempty"`

	// The source line. -1 when unknown.
	Line int `json:"line"`

	// The source column. -1 when unknown.
	Column int `json:"column"`
}

// A structured problem: something the frontend could not lower.
type Problem struct {
	// PARSE_PROBLEM, TRANSLATION_ERROR, UNHANDLED_NODE or SOURCE_LOWERED.
	Kind string `json:"kind"`

	// A human-readable description.
	Message string `json:"message"`

	// The simple class name of the AST node that caused it.
	NodeClass string `json:"nodeClass,omitempty"`

	// The source line. -1 when unknown.
	Line int `json:"line"`

	// The source column. -1 when unknown.
	Column int `json:"column"`
}

// A deferred call: a call the function makes on its way out.
//
// This is a Go-specific field and has no counterpart in the C# protocol,
// because Go's `defer` has no counterpart in C#.
//
// WHY THE CALL IS ALSO AN INSTRUCTION. The deferred call is emitted INLINE
// at the `defer` statement, so the taint edge from the deferred call's
// arguments to the call is in the instruction stream where the engine can
// see it. A side table alone would be invisible to the engine: a
// `defer os.WriteFile(name, tainted, ...)` would leave no trace in the
// TAC, the finding would be absent, and the scan would still report
// success.
//
// WHAT THE INSTRUCTION GETS WRONG. Go runs the deferred call when the
// FUNCTION exits, not where the `defer` is written. The instruction puts it
// at the `defer` statement instead. The TAINT is still right -- Go
// evaluates the deferred call's arguments at the `defer` statement, so the
// arguments the instruction carries are the arguments the call will
// receive -- but the ORDER is not: the engine will see the call happen
// before the code that follows the `defer`.
//
// So the function's precision is lowered and a problem says so. The side
// table stays, because it is what a reader needs to know that the call
// really happens at the exit.
type DeferredCall struct {
	// The callee signature, in the owner.method/arity form.
	CalleeSig string `json:"calleeSig"`

	// The arguments, evaluated at the defer statement.
	Args []Operand `json:"args"`

	// The source line of the defer statement. -1 when unknown.
	Line int `json:"line"`

	// The source column of the defer statement. -1 when unknown.
	Column int `json:"column"`
}

// A function in the wire model.
type Function struct {
	// The function's name.
	Name string `json:"name"`

	// The function's signature, in Plumb's owner.name(Params):Return form.
	Signature string `json:"signature"`

	// The enclosing scope's name.
	//
	// For a package-level function it is the PACKAGE's import path; for a
	// method it is the receiver type's qualified name. Go has no classes, but
	// the TAC's owner convention needs an enclosing scope: a function without
	// one is dropped from the store's merged index, and the call graph over
	// that index then has no edge for the language at all.
	EnclosingClass string `json:"enclosingClass,omitempty"`

	// The declared return type.
	ReturnType *TypeRef `json:"returnType"`

	// SOURCE_FULL or SOURCE_LOWERED.
	Precision string `json:"precision"`

	// Whether the compiler generated this function.
	//
	// Always false for Go: every function in the stream is one the user
	// wrote. Go has no compiler-generated source-level methods, so the field
	// is carried only for shape compatibility with the C# protocol.
	Synthetic bool `json:"synthetic"`

	// The parameters, in declaration order.
	Params []Operand `json:"params"`

	// The locals.
	Locals []Operand `json:"locals"`

	// The instruction stream, in source order.
	Instructions []Instruction `json:"instructions"`

	// The problems encountered while lowering this function.
	Problems []Problem `json:"problems"`

	// The deferred calls, in the order they were written.
	//
	// Go-specific: the C# protocol has no counterpart, because C# has no
	// `defer`. Always present, possibly empty.
	DeferredCalls []DeferredCall `json:"deferredCalls"`

	// Whether the function starts a goroutine.
	//
	// Go-specific: the C# protocol has no counterpart. It is set when the
	// function contains a `go` statement. It says NOTHING about ordering,
	// happens-before, or synchronisation -- the sidecar does not model Go's
	// memory model, and a checker that reads this flag as "the goroutine
	// runs concurrently" would be inventing a guarantee the analysis does
	// not have.
	StartsGoroutine bool `json:"startsGoroutine"`
}

// A compiler diagnostic.
type Diagnostic struct {
	// error, warning, info or hidden.
	Severity string `json:"severity"`

	// The diagnostic's message.
	Message string `json:"message"`

	// The file the diagnostic is in.
	Path string `json:"path,omitempty"`

	// The source line. -1 when unknown.
	Line int `json:"line"`

	// The source column. -1 when unknown.
	Column int `json:"column"`
}

// One import of a package, with the data needed to resolve it.
type ImportSpec struct {
	// The import path, e.g. "fmt".
	ImportPath string `json:"importPath"`

	// The compile-time export data for the dependency.
	//
	// This is the PREFERRED source of the dependency's types: the export
	// data is what the build actually compiled, so the types the frontend
	// sees are the types the build saw. Reading the dependency's SOURCE
	// instead would mean re-type-checking it, and a re-check can disagree
	// with the build (different build tags, a different Go version, a
	// different set of files) -- and then the frontend would reason about
	// types the program was never compiled against.
	ExportData string `json:"exportData,omitempty"`

	// The dependency's source directory, for a dependency that has no export
	// data. Optional: when export data is present it wins.
	Dir string `json:"dir,omitempty"`
}

// One package of the project.
//
// A package is Go's compilation unit, not a file. A project is therefore a
// SET of packages, each with its own import path and its own file set.
type PackageSpec struct {
	// The package's import path, e.g. "github.com/example/proj/internal/store".
	ImportPath string `json:"importPath"`

	// The package's source directory.
	Dir string `json:"dir,omitempty"`

	// The package's files, as absolute paths.
	Files []string `json:"files,omitempty"`

	// The Go version the package was built with, e.g. "go1.23".
	GoVersion string `json:"goVersion,omitempty"`

	// The build tags in effect, e.g. ["linux", "amd64"].
	BuildTags []string `json:"buildTags,omitempty"`

	// The package's imports.
	Imports []ImportSpec `json:"imports,omitempty"`
}

// One source file with its text.
//
// The text is the CALLER'S copy: it may be an unsaved editor buffer, and it
// may differ from what is on disk. The analysis must see the caller's copy,
// because the caller's copy is the code the caller believes it is
// analysing.
type SourceFile struct {
	// The file's absolute path.
	Path string `json:"path"`

	// The file's contents.
	Text string `json:"text"`
}

// A request from the Java side.
type Request struct {
	// The operation: openProject, translate, closeProject, ping or shutdown.
	Op string `json:"op"`

	// The protocol version the Java side speaks.
	//
	// It is a POINTER so that a request that carries NO version can be
	// told apart from one that carries version 0. A missing version is
	// refused rather than accepted as "probably fine": the two halves are
	// built independently, and a request from a frontend too old to know
	// about the handshake is exactly the case the handshake exists to
	// catch.
	ProtocolVersion *int `json:"protocolVersion,omitempty"`

	// The IR version the Java side speaks, when it reports one.
	IRVersion *int `json:"irVersion,omitempty"`

	// The project id, for openProject.
	ProjectID string `json:"projectId,omitempty"`

	// The Go toolchain's root directory, for openProject.
	//
	// Go-specific and OPTIONAL.
	//
	// The toolchain's root is where the standard library's source lives, and
	// the sidecar has to find that source to type-check the project's
	// dependencies. The environment's GOROOT cannot answer this: it is a
	// leftover of whatever toolchain was installed last, it outlives the
	// toolchain it names, and a GOROOT that points at a directory which no
	// longer exists makes every standard library import fail SILENTLY --
	// the imports go unresolved, the calls into the standard library lose
	// their types, and the findings that depend on them disappear while the
	// scan reports success.
	//
	// The Java side derives this from the captured command, whose compiler
	// lives at <root>/pkg/tool/<goos>_<goarch>/compile. That is the
	// toolchain which BUILT the project, so it is the toolchain whose
	// standard library the project was compiled against.
	//
	// An absent value is not an error: the sidecar falls back to the
	// environment, and to its own toolchain, and reports it when neither
	// works. So this field does not change the protocol version -- an older
	// frontend that does not send it still works, and an older sidecar that
	// ignores it still works.
	Goroot string `json:"goroot,omitempty"`

	// The project's source files, with their TEXT, for openProject.
	//
	// Go-specific in its effect: the C# protocol carries `sources` too, but
	// the C# sidecar reads the files from disk and ignores the text. The Go
	// sidecar USES the text: a file whose path appears here is parsed and
	// type-checked from this text, not from the disk.
	Sources []SourceFile `json:"sources,omitempty"`

	// The project's packages, for openProject.
	//
	// Go-specific: the C# protocol carries `sources`, a flat list of files,
	// because a C# compilation is built from a flat list. A Go project is a
	// set of packages, and a package's files only type-check together.
	Packages []PackageSpec `json:"packages,omitempty"`

	// The file to translate, for translate.
	Path string `json:"path,omitempty"`
}

// A response to the Java side.
type Response struct {
	// The operation this answers.
	Op string `json:"op"`

	// The protocol version this sidecar speaks.
	//
	// EVERY response carries it, including a failed one. The Java side
	// refuses a sidecar that reports no version, so a response without
	// this field would make the whole sidecar unusable -- and the refusal
	// would look like a broken analysis rather than a broken handshake.
	ProtocolVersion int `json:"protocolVersion"`

	// The IR version this sidecar was built against.
	IRVersion int `json:"irVersion"`

	// Whether the operation succeeded.
	OK bool `json:"ok"`

	// The failure message, when the operation failed.
	//
	// A failure is NEVER reported as an empty result: an empty `functions`
	// array reads as "no findings", and a scan that reports success while
	// having analysed nothing is the one failure mode this whole design
	// exists to avoid.
	Error string `json:"error,omitempty"`

	// The compiler's diagnostics.
	Diagnostics []Diagnostic `json:"diagnostics,omitempty"`

	// The translated functions.
	Functions []Function `json:"functions,omitempty"`
}

// encodeResponse serialises a response into a frame payload.
//
// The version fields are stamped HERE, not at each construction site, so
// that no response can escape without them -- including the failure
// responses, which are exactly the ones a caller is most likely to be
// reading when something has gone wrong.
//
// HTML escaping is turned off so that the payload keeps the characters the
// C# sidecar would have written. The Java side's JSON reader handles both
// forms, so this is about the two halves producing the same bytes for the
// same content, not about correctness.
func encodeResponse(response *Response) ([]byte, error) {
	response.ProtocolVersion = ProtocolVersionValue
	response.IRVersion = IRVersionValue

	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(response); err != nil {
		return nil, err
	}
	// Encoder.Encode appends a newline. The framing counts payload bytes, so
	// the newline would be a byte of payload the Java side never asked for.
	return bytes.TrimRight(buffer.Bytes(), "\n"), nil
}

// decodeRequest deserialises a request from a frame payload.
func decodeRequest(payload []byte) (*Request, error) {
	if len(payload) == 0 {
		return nil, fmt.Errorf("the request frame carried no payload")
	}
	var request Request
	if err := json.Unmarshal(payload, &request); err != nil {
		return nil, err
	}
	return &request, nil
}
