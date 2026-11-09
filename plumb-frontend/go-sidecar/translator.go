package main

// Lowers a Go function body into Plumb's TAC.
//
// The translator's job is to produce an instruction stream in which the TAINT
// of the code survives. Where Go's semantics have no TAC shape, the shape is
// approximated and the approximation is REPORTED -- never silently applied.
//
// The rule that decides everything else: a construct that is not modelled
// costs one instruction's precision, not the whole function's. A function
// that mixes a modelled path with an unmodelled one keeps the modelled path
// intact and reports the rest.

import (
	"fmt"
	"go/ast"
	"go/constant"
	"go/token"
	"go/types"
	"reflect"
	"strconv"
)

// syntheticOwner is the owner of the synthetic callee signatures.
//
// The runtime operations the sidecar models -- the length of a slice, a map
// lookup, a channel receive -- are not calls to anything the user wrote.
// They are given a synthetic owner so that they can never be confused with
// a real package: a sink catalogue written against "fmt.Sprintf/1" will not
// match "<go>.len/1" by accident, and a checker that prints the callee can
// see at a glance that the call is a stand-in.
const syntheticOwner = "<go>"

// The synthetic callee names.
const (
	syntheticLen       = "len"
	syntheticCap       = "cap"
	syntheticAppend    = "append"
	syntheticCopy      = "copy"
	syntheticDelete    = "delete"
	syntheticMake      = "make"
	syntheticPrint     = "print"
	syntheticRecover   = "recover"
	syntheticMapIndex  = "mapIndex"
	syntheticMapStore  = "mapStore"
	syntheticMapIter   = "mapIter"
	syntheticMapNext   = "mapNext"
	syntheticChanSend  = "chanSend"
	syntheticChanRecv  = "chanRecv"
	syntheticSelect    = "selectReady"
	syntheticSlice     = "slice"
	syntheticComplex   = "complex"
	syntheticReal      = "real"
	syntheticImag      = "imag"
	syntheticMin       = "min"
	syntheticMax       = "max"
	syntheticClear     = "clear"
	syntheticNewObject = "<init>"
)

// syntheticSignature builds a synthetic callee key.
func syntheticSignature(name string, arity int) string {
	return calleeSignature(syntheticOwner, name, arity)
}

// A break/continue target.
//
// The innermost context wins, and the context is pushed and popped around
// every loop and switch, so that a nested one does not leak its labels into
// the enclosing one.
type breakContext struct {
	// The label a `break` jumps to.
	breakLabel string

	// The label a `continue` jumps to.
	continueLabel string
}

// A switch context, for `fallthrough`.
type switchContext struct {
	// The labels of the case bodies, in order.
	caseLabels []string

	// The label the switch ends at.
	endLabel string

	// The index of the case body being lowered. It is what makes
	// `fallthrough` find the NEXT case rather than the current one.
	current int
}

// The kinds of callee.
//
// The distinction matters because the builtins and the conversions are not
// calls: a conversion has no callee to match against a sink catalogue, and
// a panic is an exceptional exit rather than a call.
type calleeKind int

const (
	// An ordinary call to a function or a method.
	callRegular calleeKind = iota

	// A conversion: `T(x)`.
	callConversion

	// A call to a builtin.
	callBuiltin

	// A call to the `panic` builtin.
	callPanic

	// A call to the `new` builtin.
	callNew

	// A call to the `make` builtin.
	callMake
)

// The translator: one function's worth of state.
type translator struct {
	fset *token.FileSet
	info *types.Info
	pkg  *types.Package

	// The function's signature, for the decisions that depend on it (the
	// error result, the named results).
	signature *types.Signature

	instructions []Instruction
	problems     []Problem
	params       []Operand
	locals       []Operand

	// The deferred calls, in the order they were written.
	deferred []DeferredCall

	// Whether the function starts a goroutine.
	startsGoroutine bool

	// Whether any construct could not be lowered faithfully.
	degraded bool

	// The operands of the objects the translator has bound.
	bindings map[types.Object]*Operand

	// How many times each base name has been used, so that a shadowed
	// variable gets its own name in the TAC.
	usedNames map[string]int

	// The temporary counter.
	counter int

	// The label counter.
	labelCounter int

	// The break/continue contexts, innermost last.
	breaks []*breakContext

	// The switch contexts, innermost last.
	switches []*switchContext

	// The labelled statements, by label name.
	labelled map[string]*breakContext

	// The label of the statement being translated, when it is a labelled
	// loop or switch. A `break label` needs it.
	pendingLabel string
}

// translateFunction lowers one function declaration into the wire model.
func translateFunction(fset *token.FileSet, pkg *loadedPackage, declaration *ast.FuncDecl) Function {
	t := &translator{
		fset:       fset,
		info:       pkg.info,
		pkg:        pkg.pkg,
		instructions: []Instruction{},
		problems:     []Problem{},
		params:       []Operand{},
		locals:       []Operand{},
		deferred:     []DeferredCall{},
		bindings:     map[types.Object]*Operand{},
		usedNames:    map[string]int{},
		labelled:     map[string]*breakContext{},
	}

	object, _ := pkg.info.Defs[declaration.Name].(*types.Func)
	if object == nil {
		t.report(declaration, ProblemTranslation, "the function's declaration could not be resolved")
		return t.buildFunction(declaration, "", "")
	}
	t.signature = object.Type().(*types.Signature)

	owner := pkg.pkg.Path()
	if t.signature.Recv() != nil {
		owner = methodOwner(t.signature.Recv().Type())
	}
	// The enclosing scope is the PACKAGE for a package-level function, and the
	// RECEIVER TYPE for a method. Go has no classes, but the TAC's owner
	// convention needs one: a function without an enclosing scope is dropped
	// from the merged index, and a call graph over an index that holds no Go
	// function has no Go edges at all -- silently.
	//
	// It is the FULL path, not the short name, because the convention compares
	// owners by their LAST DOT-SEPARATED SEGMENT: the call site sees the
	// qualified owner, so the declaration has to carry the same shape.
	enclosing := owner

	// The receiver is a parameter. It is not one of the function's
	// DECLARED parameters -- the arity in the callee key counts those --
	// but it is one of the function's arguments in the TAC's sense, and it
	// has to be a bound variable for the receiver to be referable.
	if receiver := t.signature.Recv(); receiver != nil {
		t.params = append(t.params, t.declareVar(receiver, false))
	}
	for index := 0; index < t.signature.Params().Len(); index++ {
		t.params = append(t.params, t.declareVar(t.signature.Params().At(index), false))
	}

	// The named results are locals: `func f() (err error)` declares `err`
	// exactly like a local would.
	for index := 0; index < t.signature.Results().Len(); index++ {
		result := t.signature.Results().At(index)
		if result.Name() == "" {
			continue
		}
		t.declareVar(result, true)
	}

	t.translateBlock(declaration.Body)

	return t.buildFunction(declaration, owner, enclosing)
}

// buildFunction assembles the wire model of the translated function.
func (t *translator) buildFunction(declaration *ast.FuncDecl, owner string, enclosing string) Function {
	precision := PrecisionFull
	if t.degraded {
		precision = PrecisionLowered
	}

	parameters := []string{}
	if t.signature != nil {
		for index := 0; index < t.signature.Params().Len(); index++ {
			parameters = append(parameters, t.signature.Params().At(index).Type().String())
		}
	}
	result := ""
	if t.signature != nil && t.signature.Results().Len() > 0 {
		result = t.signature.Results().At(0).Type().String()
	}

	returnType := Simple(TypeVoid)
	if t.signature != nil && t.signature.Results().Len() > 0 {
		returnType = mapGoType(t.signature.Results().At(0).Type())
	}

	name := "<unknown>"
	if declaration != nil && declaration.Name != nil {
		name = declaration.Name.Name
	}

	return Function{
		Name:          name,
		Signature:     declarationSignature(owner, name, parameters, result),
		EnclosingClass: enclosing,
		ReturnType:    returnType,
		Precision:     precision,
		Synthetic:     false,
		Params:        t.params,
		Locals:        t.locals,
		Instructions:  t.instructions,
		Problems:      t.problems,
		DeferredCalls: t.deferred,
		StartsGoroutine: t.startsGoroutine,
	}
}

// ---------------------------------------------------------------------------
// Emitting
// ---------------------------------------------------------------------------

// emit appends an instruction, stamped with the source position.
func (t *translator) emit(instruction Instruction, position token.Pos) {
	if position.IsValid() {
		place := t.fset.Position(position)
		instruction.Line = place.Line
		instruction.Column = place.Column
	} else {
		instruction.Line = -1
		instruction.Column = -1
	}
	t.instructions = append(t.instructions, instruction)
}

// report records a problem.
//
// Every construct that could not be lowered faithfully produces one. A
// problem is not a diagnostic in the compiler's sense -- the code may
// compile perfectly -- it is the frontend saying "the analysis did not
// understand this part".
func (t *translator) report(node ast.Node, kind string, message string) {
	problem := Problem{
		Kind:    kind,
		Message: message,
		Line:    -1,
		Column:  -1,
	}
	if node != nil {
		problem.NodeClass = nodeClass(node)
		if place := t.fset.Position(node.Pos()); place.IsValid() {
			problem.Line = place.Line
			problem.Column = place.Column
		}
	}
	t.problems = append(t.problems, problem)

	// An unmodelled construct means the function is not fully understood.
	// A checker has to know that, so the precision drops.
	if kind == ProblemSourceLowered || kind == ProblemUnhandledNode {
		t.degraded = true
	}
}

// nodeClass returns the simple name of an AST node's type.
//
// It is the Go counterpart of the C# sidecar's "the simple class name of the
// AST node that caused it": the Java side stores it next to the problem, so
// that a report can be traced back to the construct that produced it.
func nodeClass(node ast.Node) string {
	if node == nil {
		return ""
	}
	reflected := reflect.TypeOf(node)
	for reflected != nil && reflected.Kind() == reflect.Ptr {
		reflected = reflected.Elem()
	}
	if reflected == nil {
		return ""
	}
	return reflected.Name()
}

// newTemp creates a temporary variable.
func (t *translator) newTemp(goType types.Type) *Operand {
	t.counter++
	operand := &Operand{
		Kind:  "VAR",
		Name:  "t" + strconv.Itoa(t.counter),
		Type:  mapGoType(goType),
		IsTemp: true,
	}
	return operand
}

// newLabel creates a fresh label name.
func (t *translator) newLabel(prefix string) string {
	t.labelCounter++
	return prefix + "_" + strconv.Itoa(t.labelCounter)
}

// havoc emits an unknown value.
//
// The value is neither tainted nor proven safe: the analysis does not know.
// The destination is deliberately not tainted, because a checker that
// treated it as tainted would report findings the code may not deserve --
// but the problem that accompanies it says the analysis lost track.
func (t *translator) havoc(expression ast.Expr) *Operand {
	operand := t.newTemp(t.info.TypeOf(expression))
	t.emit(Instruction{Kind: KindHavoc, Dest: operand}, expression.Pos())
	return operand
}

// declareVar binds an object to a TAC variable.
//
// The base name is the Go name. A shadowed variable gets a suffixed name,
// because the TAC identifies a variable by its name and two different Go
// variables that share a name would otherwise be merged -- and a merged
// variable carries the taint of one into the other.
func (t *translator) declareVar(object *types.Var, local bool) Operand {
	base := object.Name()
	if base == "" {
		base = "recv"
	}
	count := t.usedNames[base]
	t.usedNames[base] = count + 1
	name := base
	if count > 0 {
		name = base + "#" + strconv.Itoa(count+1)
	}
	operand := Var(name, mapGoType(object.Type()))
	t.bindings[object] = operand
	if local {
		t.locals = append(t.locals, *operand)
	}
	return *operand
}

// binding returns the operand an object is bound to.
//
// A package-level variable has no local slot, but it is still a variable the
// TAC can refer to, so it is bound under its qualified name.
func (t *translator) binding(object types.Object) *Operand {
	if operand, ok := t.bindings[object]; ok {
		return operand
	}
	operand := Var(qualifiedObjectName(object), mapGoType(object.Type()))
	t.bindings[object] = operand
	return operand
}

// ---------------------------------------------------------------------------
// Statements
// ---------------------------------------------------------------------------

// translateBlock lowers a block of statements.
func (t *translator) translateBlock(block *ast.BlockStmt) {
	if block == nil {
		return
	}
	for _, statement := range block.List {
		t.translateStatement(statement)
	}
}

// translateStatement lowers one statement.
func (t *translator) translateStatement(statement ast.Stmt) {
	switch typed := statement.(type) {
	case *ast.AssignStmt:
		t.translateAssign(typed)
	case *ast.DeclStmt:
		t.translateDecl(typed)
	case *ast.ExprStmt:
		t.valueOf(typed.X)
	case *ast.ReturnStmt:
		t.translateReturn(typed)
	case *ast.IfStmt:
		t.translateIf(typed)
	case *ast.ForStmt:
		t.translateFor(typed)
	case *ast.RangeStmt:
		t.translateRange(typed)
	case *ast.SwitchStmt:
		t.translateSwitch(typed)
	case *ast.TypeSwitchStmt:
		t.translateTypeSwitch(typed)
	case *ast.SelectStmt:
		t.translateSelect(typed)
	case *ast.BlockStmt:
		t.translateBlock(typed)
	case *ast.DeferStmt:
		t.translateDefer(typed)
	case *ast.GoStmt:
		t.translateGo(typed)
	case *ast.BranchStmt:
		t.translateBranch(typed)
	case *ast.LabeledStmt:
		t.translateLabeled(typed)
	case *ast.IncDecStmt:
		t.translateIncDec(typed)
	case *ast.SendStmt:
		t.translateSend(typed)
	case *ast.EmptyStmt:
		// Nothing to lower.
	default:
		t.report(statement, ProblemUnhandledNode,
			fmt.Sprintf("the statement %T was not lowered", statement))
	}
}

// translateAssign lowers an assignment.
//
// The assignment's target decides the instruction: a variable is a MOVE, a
// struct field is a FIELDSTORE, and an element is an ARRAYSTORE. The
// distinction comes from the resolved target, never from the spelling.
func (t *translator) translateAssign(statement *ast.AssignStmt) {
	if len(statement.Rhs) == 1 && len(statement.Lhs) > 1 {
		t.translateMultiAssign(statement)
		return
	}
	if len(statement.Rhs) != len(statement.Lhs) {
		t.report(statement, ProblemUnhandledNode,
			"the assignment's two sides have different numbers of expressions")
		return
	}

	for index, right := range statement.Rhs {
		left := statement.Lhs[index]
		switch target := left.(type) {
		case *ast.Ident:
			destination := t.assignIdent(target, statement.Tok == token.DEFINE)
			if destination == nil {
				continue
			}
			if call, ok := right.(*ast.CallExpr); ok {
				// The call writes straight into the destination. An extra
				// MOVE would be a copy the TAC does not need, and it would
				// make the call's result look like it came from somewhere
				// else.
				t.translateCall(call, destination)
				continue
			}
			value := t.valueOf(right)
			t.emit(Instruction{Kind: KindMove, Dest: destination, Lhs1: value}, statement.Pos())
		case *ast.SelectorExpr:
			t.translateFieldAssign(target, t.valueOf(right), statement)
		case *ast.IndexExpr:
			t.translateIndexAssign(target, t.valueOf(right), statement)
		default:
			t.report(statement, ProblemUnhandledNode,
				fmt.Sprintf("the assignment target %T was not lowered", left))
		}
	}
}

// translateMultiAssign lowers a multi-value assignment: `v, err := f()`.
//
// THIS IS WHERE GO'S MOST COMMON SHAPE IS DECIDED.
//
// A Go call can return several values, and the TAC's CALL has ONE
// destination. So one of the results has to be given up, and the choice
// matters:
//
//   - the FIRST result is the one that is kept, because that is the value
//     the code uses;
//   - a second result that is the ERROR is the call's EXCEPTIONAL exit, so
//     it is bound to an unknown value and the error check that follows
//     turns it into a THROW;
//   - the blank identifier discards a result on purpose, and nothing is
//     lost, so nothing is reported.
//
// The cost: the error's VALUE is not modelled. The engine sees that the
// function may exit exceptionally, but not what the error was, and not
// whether the error was tainted.
func (t *translator) translateMultiAssign(statement *ast.AssignStmt) {
	right := statement.Rhs[0]

	destinations := make([]*Operand, len(statement.Lhs))
	for index, left := range statement.Lhs {
		ident, ok := left.(*ast.Ident)
		if !ok {
			t.report(statement, ProblemUnhandledNode,
				"a multi-value assignment target was not an identifier")
			continue
		}
		destinations[index] = t.assignIdent(ident, statement.Tok == token.DEFINE)
	}

	switch typed := right.(type) {
	case *ast.CallExpr:
		t.translateCall(typed, destinations[0])
	case *ast.IndexExpr:
		// `v, ok := m[k]`: a map read.
		t.translateMapRead(typed, destinations[0])
	case *ast.UnaryExpr:
		if typed.Op == token.ARROW {
			t.translateChannelReceive(typed, destinations[0])
		} else {
			t.report(statement, ProblemUnhandledNode, "the multi-value expression was not lowered")
			return
		}
	case *ast.TypeAssertExpr:
		t.translateTypeAssert(typed, destinations[0])
	default:
		t.report(statement, ProblemUnhandledNode,
			fmt.Sprintf("the multi-value expression %T was not lowered", right))
		return
	}

	for index := 1; index < len(destinations); index++ {
		if destinations[index] == nil {
			// The blank identifier. The programmer asked for the result to
			// be discarded, so nothing is lost and nothing is reported.
			continue
		}
		t.emit(Instruction{Kind: KindHavoc, Dest: destinations[index]}, statement.Pos())
		t.report(statement, ProblemSourceLowered,
			"a result beyond the first could not be bound: the TAC's CALL has one destination")
	}
}

// assignIdent resolves an assignment target that is an identifier.
//
// It returns nil when the identifier is the blank one, or when it could not
// be resolved.
func (t *translator) assignIdent(ident *ast.Ident, define bool) *Operand {
	if ident.Name == "_" {
		return nil
	}
	if define {
		object, ok := t.info.Defs[ident].(*types.Var)
		if !ok {
			t.report(ident, ProblemUnhandledNode, "the declared variable could not be resolved")
			return nil
		}
		operand := t.declareVar(object, true)
		return &operand
	}
	object := t.info.ObjectOf(ident)
	variable, ok := object.(*types.Var)
	if !ok {
		t.report(ident, ProblemUnhandledNode, "the assigned variable could not be resolved")
		return nil
	}
	return t.binding(variable)
}

// translateFieldAssign lowers an assignment to a struct field.
//
// A field really is a field, so it is a FIELDSTORE and not a CALL. The
// distinction from a method call is decided by the resolved symbol.
func (t *translator) translateFieldAssign(target *ast.SelectorExpr, value *Operand, statement *ast.AssignStmt) {
	selection := t.info.Selections[target]
	if selection == nil {
		t.report(statement, ProblemUnhandledNode, "the field assignment's target could not be resolved")
		return
	}
	field, ok := selection.Obj().(*types.Var)
	if !ok {
		t.report(statement, ProblemUnhandledNode, "the field assignment's target is not a field")
		return
	}
	receiver := t.valueOf(target.X)
	t.emit(Instruction{
		Kind:      KindFieldStore,
		Dest:      value,
		Lhs1:      receiver,
		FieldName: field.Name(),
	}, statement.Pos())
}

// translateIndexAssign lowers an assignment to an element.
func (t *translator) translateIndexAssign(target *ast.IndexExpr, value *Operand, statement *ast.AssignStmt) {
	if t.isMapIndex(target) {
		// A map write is not an array store: the map has no elements to
		// index into, it has keys.
		mapOperand := t.valueOf(target.X)
		key := t.valueOf(target.Index)
		t.emit(Instruction{
			Kind:     KindCall,
			CalleeSig: syntheticSignature(syntheticMapStore, 3),
			Args:     []Operand{*mapOperand, *key, *value},
		}, statement.Pos())
		return
	}
	collection := t.valueOf(target.X)
	index := t.valueOf(target.Index)
	t.emit(Instruction{
		Kind: KindArrayStore,
		Dest: value,
		Lhs1: collection,
		Lhs2: index,
	}, statement.Pos())
}

// translateDecl lowers a declaration statement.
func (t *translator) translateDecl(statement *ast.DeclStmt) {
	declaration, ok := statement.Decl.(*ast.GenDecl)
	if !ok {
		t.report(statement, ProblemUnhandledNode, "the declaration was not lowered")
		return
	}
	for _, spec := range declaration.Specs {
		switch typed := spec.(type) {
		case *ast.ValueSpec:
			t.translateValueSpec(typed)
		case *ast.TypeSpec:
			// A type declaration has no run-time effect, and the type
			// itself is known to the type checker. There is nothing to
			// lower and nothing to report.
		default:
			t.report(statement, ProblemUnhandledNode,
				fmt.Sprintf("the declaration spec %T was not lowered", spec))
		}
	}
}

// translateValueSpec lowers a variable or constant declaration.
func (t *translator) translateValueSpec(spec *ast.ValueSpec) {
	for index, name := range spec.Names {
		if name.Name == "_" {
			continue
		}
		object := t.info.Defs[name]

		if constant, ok := object.(*types.Const); ok {
			// A constant is a value, not a variable: it is bound to the
			// constant itself, so a use of it carries the constant's
			// taint (which is none) instead of a variable's.
			if operand := t.constOperand(constant); operand != nil {
				t.bindings[constant] = operand
			}
			continue
		}

		variable, ok := object.(*types.Var)
		if !ok {
			t.report(spec, ProblemUnhandledNode, "the declared name could not be resolved")
			continue
		}
		operand := t.declareVar(variable, true)
		if index < len(spec.Values) {
			value := t.valueOf(spec.Values[index])
			t.emit(Instruction{Kind: KindMove, Dest: &operand, Lhs1: value}, spec.Pos())
		}
	}
}

// translateReturn lowers a return statement.
//
// A Go function that returns (T, error) has TWO exits: the normal one
// returns the value, and the error one is an EXCEPTIONAL exit. The TAC's
// RETURN and THROW are exactly those two exits, so the shape is faithful.
//
// A function that returns two MEANINGFUL values has no such correspondence:
// the TAC's RETURN carries one value. The first result is kept and the loss
// is reported, because a function that returns two meaningful values is a
// function whose second value is invisible to the analysis.
func (t *translator) translateReturn(statement *ast.ReturnStmt) {
	if len(statement.Results) == 0 {
		t.emit(Instruction{Kind: KindReturn}, statement.Pos())
		return
	}
	if len(statement.Results) == 1 {
		value := t.valueOf(statement.Results[0])
		t.emit(Instruction{Kind: KindReturn, Lhs1: value}, statement.Pos())
		return
	}

	first := t.valueOf(statement.Results[0])
	second := t.valueOf(statement.Results[1])

	if len(statement.Results) == 2 && t.declaredSecondResultIsError() {
		if t.isNilLiteral(statement.Results[1]) {
			t.emit(Instruction{Kind: KindReturn, Lhs1: first}, statement.Pos())
			return
		}
		// The error is the exceptional exit. Emitting a THROW is what makes
		// the error path visible: the engine can see that the function may
		// exit exceptionally, and with what.
		t.emit(Instruction{Kind: KindThrow, Lhs1: second}, statement.Pos())
		return
	}

	t.report(statement, ProblemSourceLowered,
		"a function with more than one meaningful result was lowered to its first result only")
	t.emit(Instruction{Kind: KindReturn, Lhs1: first}, statement.Pos())
}

// declaredSecondResultIsError reports whether the function's second declared
// result is the error type.
func (t *translator) declaredSecondResultIsError() bool {
	if t.signature == nil || t.signature.Results().Len() < 2 {
		return false
	}
	return isErrorType(t.signature.Results().At(1).Type())
}

// isNilLiteral reports whether an expression is the nil literal.
func (t *translator) isNilLiteral(expression ast.Expr) bool {
	ident, ok := expression.(*ast.Ident)
	return ok && ident.Name == "nil"
}

// translateIf lowers an if statement.
func (t *translator) translateIf(statement *ast.IfStmt) {
	if statement.Init != nil {
		t.translateStatement(statement.Init)
	}
	condition := t.valueOf(statement.Cond)
	trueLabel := t.newLabel("if_true")
	falseLabel := t.newLabel("if_false")
	endLabel := t.newLabel("if_end")

	t.emit(Instruction{
		Kind:      KindBranch,
		Lhs1:      condition,
		TrueLabel: trueLabel,
		FalseLabel: falseLabel,
	}, statement.Pos())

	t.emit(Instruction{Kind: KindLabel, Label: trueLabel}, statement.Pos())
	t.translateStatement(statement.Body)
	t.emit(Instruction{Kind: KindJump, Label: endLabel}, statement.Pos())

	t.emit(Instruction{Kind: KindLabel, Label: falseLabel}, statement.Pos())
	if statement.Else != nil {
		t.translateStatement(statement.Else)
	}
	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// translateFor lowers a for statement.
//
// The `continue` target is the POST statement, not the condition: a
// `continue` in a Go for loop runs the post statement and then tests the
// condition again. Jumping to the condition instead would skip the post
// statement, and the loop variable would never advance.
func (t *translator) translateFor(statement *ast.ForStmt) {
	if statement.Init != nil {
		t.translateStatement(statement.Init)
	}

	conditionLabel := t.newLabel("for_cond")
	bodyLabel := t.newLabel("for_body")
	postLabel := t.newLabel("for_post")
	endLabel := t.newLabel("for_end")

	t.emit(Instruction{Kind: KindJump, Label: conditionLabel}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: conditionLabel}, statement.Pos())
	if statement.Cond != nil {
		condition := t.valueOf(statement.Cond)
		t.emit(Instruction{
			Kind:      KindBranch,
			Lhs1:      condition,
			TrueLabel: bodyLabel,
			FalseLabel: endLabel,
		}, statement.Pos())
	}
	t.emit(Instruction{Kind: KindLabel, Label: bodyLabel}, statement.Pos())

	t.pushBreak(&breakContext{breakLabel: endLabel, continueLabel: postLabel})
	t.registerPendingLabel(endLabel, postLabel)
	t.translateStatement(statement.Body)
	t.popBreak()

	t.emit(Instruction{Kind: KindLabel, Label: postLabel}, statement.Pos())
	if statement.Post != nil {
		t.translateStatement(statement.Post)
	}
	t.emit(Instruction{Kind: KindJump, Label: conditionLabel}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// translateRange lowers a range statement.
//
// The three shapes of a range are lowered differently, because they are
// different operations:
//
//   - a slice, an array or a string becomes an INDEXED LOOP: the index
//     advances and the element is read at each index;
//   - a map becomes a call to the map ITERATOR: the map has no index, so
//     the iteration is a runtime operation;
//   - a channel becomes a single RECEIVE: ranging over a channel reads
//     one value per iteration, and the TAC has no channel semantics to
//     model the blocking in.
func (t *translator) translateRange(statement *ast.RangeStmt) {
	collection := t.valueOf(statement.X)
	collectionType := t.info.TypeOf(statement.X)
	if collectionType == nil {
		t.report(statement, ProblemUnhandledNode, "the range's collection type could not be resolved")
		return
	}

	key := t.rangeTarget(statement.Key)
	value := t.rangeTarget(statement.Value)

	switch underlying := collectionType.Underlying(); {
	case isMapType(underlying):
		t.rangeOverMap(statement, collection, key, value)
	case isChanType(underlying):
		t.rangeOverChannel(statement, collection, value)
	default:
		t.rangeOverIndexed(statement, collection, key, value)
	}
}

// rangeTarget binds a range loop's key or value variable.
func (t *translator) rangeTarget(expression ast.Expr) *Operand {
	if expression == nil {
		return nil
	}
	ident, ok := expression.(*ast.Ident)
	if !ok {
		t.report(expression, ProblemUnhandledNode, "the range variable was not an identifier")
		return nil
	}
	if ident.Name == "_" {
		return nil
	}
	object, ok := t.info.Defs[ident].(*types.Var)
	if !ok {
		t.report(expression, ProblemUnhandledNode, "the range variable could not be resolved")
		return nil
	}
	operand := t.declareVar(object, true)
	return &operand
}

// rangeOverIndexed lowers a range over a slice, an array or a string.
func (t *translator) rangeOverIndexed(statement *ast.RangeStmt, collection *Operand, key *Operand, value *Operand) {
	length := t.newTemp(nil)
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     length,
		CalleeSig: syntheticSignature(syntheticLen, 1),
		Args:     []Operand{*collection},
	}, statement.Pos())

	index := t.newTemp(nil)
	t.emit(Instruction{Kind: KindMove, Dest: index, Lhs1: Const("INT", "0")}, statement.Pos())

	conditionLabel := t.newLabel("range_cond")
	bodyLabel := t.newLabel("range_body")
	postLabel := t.newLabel("range_post")
	endLabel := t.newLabel("range_end")

	t.emit(Instruction{Kind: KindJump, Label: conditionLabel}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: conditionLabel}, statement.Pos())
	comparison := t.newTemp(nil)
	t.emit(Instruction{
		Kind: KindBinOp,
		Dest: comparison,
		Lhs1: index,
		Lhs2: length,
		BinOp: "LT",
	}, statement.Pos())
	t.emit(Instruction{
		Kind:      KindBranch,
		Lhs1:      comparison,
		TrueLabel: bodyLabel,
		FalseLabel: endLabel,
	}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: bodyLabel}, statement.Pos())

	if key != nil {
		t.emit(Instruction{Kind: KindMove, Dest: key, Lhs1: index}, statement.Pos())
	}
	if value != nil {
		element := t.newTemp(nil)
		t.emit(Instruction{
			Kind: KindArrayLoad,
			Dest: element,
			Lhs1: collection,
			Lhs2: index,
		}, statement.Pos())
		t.emit(Instruction{Kind: KindMove, Dest: value, Lhs1: element}, statement.Pos())
	}

	t.pushBreak(&breakContext{breakLabel: endLabel, continueLabel: postLabel})
	t.registerPendingLabel(endLabel, postLabel)
	t.translateStatement(statement.Body)
	t.popBreak()

	t.emit(Instruction{Kind: KindLabel, Label: postLabel}, statement.Pos())
	incremented := t.newTemp(nil)
	t.emit(Instruction{
		Kind: KindBinOp,
		Dest: incremented,
		Lhs1: index,
		Lhs2: Const("INT", "1"),
		BinOp: "ADD",
	}, statement.Pos())
	t.emit(Instruction{Kind: KindMove, Dest: index, Lhs1: incremented}, statement.Pos())
	t.emit(Instruction{Kind: KindJump, Label: conditionLabel}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// rangeOverMap lowers a range over a map.
//
// The map's iteration is a runtime operation, so it is a CALL to the map
// iterator. The key and the value are NOT modelled: the TAC has no way to
// destructure an iterator entry into two values, so both are bound to
// unknown values and the loss is reported.
//
// The cost is real and it is stated: a tainted value that a map carries is
// not seen by the analysis through this path.
func (t *translator) rangeOverMap(statement *ast.RangeStmt, collection *Operand, key *Operand, value *Operand) {
	iterator := t.newTemp(nil)
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     iterator,
		CalleeSig: syntheticSignature(syntheticMapIter, 1),
		Args:     []Operand{*collection},
	}, statement.Pos())

	conditionLabel := t.newLabel("range_cond")
	bodyLabel := t.newLabel("range_body")
	endLabel := t.newLabel("range_end")

	t.emit(Instruction{Kind: KindJump, Label: conditionLabel}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: conditionLabel}, statement.Pos())
	entry := t.newTemp(nil)
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     entry,
		CalleeSig: syntheticSignature(syntheticMapNext, 1),
		Args:     []Operand{*iterator},
	}, statement.Pos())
	t.emit(Instruction{
		Kind:      KindBranch,
		Lhs1:      entry,
		TrueLabel: bodyLabel,
		FalseLabel: endLabel,
	}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: bodyLabel}, statement.Pos())

	if key != nil {
		t.emit(Instruction{Kind: KindHavoc, Dest: key}, statement.Pos())
	}
	if value != nil {
		t.emit(Instruction{Kind: KindHavoc, Dest: value}, statement.Pos())
	}
	t.report(statement, ProblemSourceLowered,
		"a map range was lowered to a call on the map iterator: the key and the value are not modelled")

	t.pushBreak(&breakContext{breakLabel: endLabel, continueLabel: conditionLabel})
	t.registerPendingLabel(endLabel, conditionLabel)
	t.translateStatement(statement.Body)
	t.popBreak()

	t.emit(Instruction{Kind: KindJump, Label: conditionLabel}, statement.Pos())
	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// rangeOverChannel lowers a range over a channel.
//
// The task's rule is one receive: the TAC has no channel semantics, so the
// blocking read is not modelled and the loop becomes a single receive.
func (t *translator) rangeOverChannel(statement *ast.RangeStmt, collection *Operand, value *Operand) {
	received := t.newTemp(nil)
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     received,
		CalleeSig: syntheticSignature(syntheticChanRecv, 1),
		Args:     []Operand{*collection},
	}, statement.Pos())
	if value != nil {
		t.emit(Instruction{Kind: KindMove, Dest: value, Lhs1: received}, statement.Pos())
	}
	t.report(statement, ProblemSourceLowered,
		"a channel range was lowered to a single receive: the blocking read is not modelled")
}

// translateSwitch lowers an expression switch.
//
// A switch becomes a branch chain: the discriminant is compared against each
// case label in turn. The semantics are preserved -- the structural fact
// "this was a switch" is not, which is exactly what SOURCE_LOWERED says.
//
// The comparisons come FIRST and the bodies SECOND. The order is
// load-bearing: a branch that selects a case must land on the case BODY, so
// the label it targets has to be defined AFTER the comparison.
func (t *translator) translateSwitch(statement *ast.SwitchStmt) {
	t.report(statement, ProblemSourceLowered, "a switch was lowered into a branch chain")

	if statement.Init != nil {
		t.translateStatement(statement.Init)
	}
	discriminant := t.valueOf(statement.Tag)

	endLabel := t.newLabel("switch_end")
	caseLabels := make([]string, len(statement.Body.List))
	for index := range statement.Body.List {
		caseLabels[index] = t.newLabel("switch_case")
	}

	defaultIndex := -1
	for index, clause := range statement.Body.List {
		caseClause, ok := clause.(*ast.CaseClause)
		if ok && len(caseClause.List) == 0 {
			defaultIndex = index
		}
	}
	noMatchLabel := endLabel
	if defaultIndex >= 0 {
		noMatchLabel = caseLabels[defaultIndex]
	}

	for index, clause := range statement.Body.List {
		caseClause, ok := clause.(*ast.CaseClause)
		if !ok {
			continue
		}
		for _, expression := range caseClause.List {
			value := t.valueOf(expression)
			comparison := t.newTemp(nil)
			t.emit(Instruction{
				Kind: KindBinOp,
				Dest: comparison,
				Lhs1: discriminant,
				Lhs2: value,
				BinOp: "EQ",
			}, expression.Pos())
			t.emit(Instruction{
				Kind:      KindBranch,
				Lhs1:      comparison,
				TrueLabel: caseLabels[index],
				FalseLabel: t.nextCaseLabel(caseLabels, index, noMatchLabel),
			}, expression.Pos())
		}
	}

	t.emit(Instruction{Kind: KindJump, Label: noMatchLabel}, statement.Pos())

	t.pushSwitch(&switchContext{caseLabels: caseLabels, endLabel: endLabel})
	t.registerPendingLabel(endLabel, endLabel)
	for index, clause := range statement.Body.List {
		t.emit(Instruction{Kind: KindLabel, Label: caseLabels[index]}, clause.Pos())
		t.switches[len(t.switches)-1].current = index
		caseClause, ok := clause.(*ast.CaseClause)
		if !ok {
			continue
		}
		for _, inner := range caseClause.Body {
			t.translateStatement(inner)
		}
		t.emit(Instruction{Kind: KindJump, Label: endLabel}, clause.Pos())
	}
	t.popSwitch()

	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// nextCaseLabel returns the label the chain continues at.
func (t *translator) nextCaseLabel(caseLabels []string, index int, noMatchLabel string) string {
	if index+1 < len(caseLabels) {
		return caseLabels[index+1]
	}
	return noMatchLabel
}

// translateTypeSwitch lowers a type switch.
//
// The type switch becomes a chain of type tests. The variable bound by the
// switch is bound to the ORIGINAL value, not to the narrowed one, because
// the TAC has no way to say "this value is a T from here on". The loss is
// reported: a checker that relies on the narrowing will see the wider type.
func (t *translator) translateTypeSwitch(statement *ast.TypeSwitchStmt) {
	t.report(statement, ProblemSourceLowered, "a type switch was lowered into a chain of type tests")

	if statement.Init != nil {
		t.translateStatement(statement.Init)
	}

	var subject *Operand
	var bound types.Object
	switch assign := statement.Assign.(type) {
	case *ast.AssignStmt:
		if len(assign.Rhs) == 1 {
			if assertion, ok := assign.Rhs[0].(*ast.TypeAssertExpr); ok {
				subject = t.valueOf(assertion.X)
				if len(assign.Lhs) == 1 {
					if ident, ok := assign.Lhs[0].(*ast.Ident); ok {
						bound = t.info.Defs[ident]
					}
				}
			}
		}
	case *ast.ExprStmt:
		if assertion, ok := assign.X.(*ast.TypeAssertExpr); ok {
			subject = t.valueOf(assertion.X)
		}
	}
	if subject == nil {
		t.report(statement, ProblemUnhandledNode, "the type switch's subject could not be resolved")
		return
	}

	endLabel := t.newLabel("switch_end")
	caseLabels := make([]string, len(statement.Body.List))
	for index := range statement.Body.List {
		caseLabels[index] = t.newLabel("switch_case")
	}
	defaultIndex := -1
	for index, clause := range statement.Body.List {
		caseClause, ok := clause.(*ast.CaseClause)
		if ok && len(caseClause.List) == 0 {
			defaultIndex = index
		}
	}
	noMatchLabel := endLabel
	if defaultIndex >= 0 {
		noMatchLabel = caseLabels[defaultIndex]
	}

	for index, clause := range statement.Body.List {
		caseClause, ok := clause.(*ast.CaseClause)
		if !ok {
			continue
		}
		for _, expression := range caseClause.List {
			test := t.newTemp(nil)
			t.emit(Instruction{
				Kind:     KindInstanceOf,
				Dest:     test,
				Lhs1:     subject,
				CtorType: mapGoType(t.info.TypeOf(expression)),
			}, expression.Pos())
			t.emit(Instruction{
				Kind:      KindBranch,
				Lhs1:      test,
				TrueLabel: caseLabels[index],
				FalseLabel: t.nextCaseLabel(caseLabels, index, noMatchLabel),
			}, expression.Pos())
		}
	}

	t.emit(Instruction{Kind: KindJump, Label: noMatchLabel}, statement.Pos())

	t.pushSwitch(&switchContext{caseLabels: caseLabels, endLabel: endLabel})
	for index, clause := range statement.Body.List {
		t.emit(Instruction{Kind: KindLabel, Label: caseLabels[index]}, clause.Pos())
		t.switches[len(t.switches)-1].current = index
		caseClause, ok := clause.(*ast.CaseClause)
		if !ok {
			continue
		}
		if bound != nil {
			// The narrowed value. The TAC cannot carry the narrowing, so
			// the original value is bound and the loss is reported.
			t.bindings[bound] = subject
		}
		for _, inner := range caseClause.Body {
			t.translateStatement(inner)
		}
		t.emit(Instruction{Kind: KindJump, Label: endLabel}, clause.Pos())
	}
	t.popSwitch()

	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// translateSelect lowers a select statement.
//
// A select is a choice between several ready channels. The TAC has no
// channel semantics, so each case becomes a branch guarded by a synthetic
// readiness call, and the default case is the path where nothing was ready.
//
// The cost: the ORDER in which the cases are ready, and the fact that a
// ready case is chosen at random, are not modelled. The analysis sees that
// the code MAY take each branch.
func (t *translator) translateSelect(statement *ast.SelectStmt) {
	t.report(statement, ProblemSourceLowered, "a select was lowered into a chain of guarded branches")

	endLabel := t.newLabel("select_end")
	caseLabels := make([]string, len(statement.Body.List))
	for index := range statement.Body.List {
		caseLabels[index] = t.newLabel("select_case")
	}

	defaultIndex := -1
	for index, clause := range statement.Body.List {
		commClause, ok := clause.(*ast.CommClause)
		if ok && commClause.Comm == nil {
			defaultIndex = index
		}
	}
	noMatchLabel := endLabel
	if defaultIndex >= 0 {
		noMatchLabel = caseLabels[defaultIndex]
	}

	for index, clause := range statement.Body.List {
		commClause, ok := clause.(*ast.CommClause)
		if !ok || commClause.Comm == nil {
			continue
		}
		channel := t.selectChannel(commClause.Comm)
		if channel == nil {
			continue
		}
		ready := t.newTemp(nil)
		t.emit(Instruction{
			Kind:     KindCall,
			Dest:     ready,
			CalleeSig: syntheticSignature(syntheticSelect, 1),
			Args:     []Operand{*channel},
		}, commClause.Comm.Pos())
		t.emit(Instruction{
			Kind:      KindBranch,
			Lhs1:      ready,
			TrueLabel: caseLabels[index],
			FalseLabel: t.nextCaseLabel(caseLabels, index, noMatchLabel),
		}, commClause.Comm.Pos())
	}

	t.emit(Instruction{Kind: KindJump, Label: noMatchLabel}, statement.Pos())

	for index, clause := range statement.Body.List {
		t.emit(Instruction{Kind: KindLabel, Label: caseLabels[index]}, clause.Pos())
		commClause, ok := clause.(*ast.CommClause)
		if !ok {
			continue
		}
		for _, inner := range commClause.Body {
			t.translateStatement(inner)
		}
		t.emit(Instruction{Kind: KindJump, Label: endLabel}, clause.Pos())
	}

	t.emit(Instruction{Kind: KindLabel, Label: endLabel}, statement.Pos())
}

// selectChannel returns the channel a select case waits on.
func (t *translator) selectChannel(comm ast.Stmt) *Operand {
	switch typed := comm.(type) {
	case *ast.ExprStmt:
		if receive, ok := typed.X.(*ast.UnaryExpr); ok && receive.Op == token.ARROW {
			return t.valueOf(receive.X)
		}
	case *ast.SendStmt:
		return t.valueOf(typed.Chan)
	}
	t.report(comm, ProblemUnhandledNode, "the select case's channel could not be resolved")
	return nil
}

// translateDefer lowers a defer statement.
//
// A `defer` is NOT a finally block and it is NOT a control-flow region: it
// runs when the FUNCTION exits, and its arguments are evaluated at the point
// of the defer statement.
//
// THE CALL IS EMITTED INLINE, AT THE DEFER STATEMENT.
//
// That is a deliberate approximation, and it is the right one to make. Go
// evaluates a deferred call's arguments AT THE DEFER STATEMENT, so the
// arguments the inline CALL carries are exactly the arguments the deferred
// call will receive. The TAINT is therefore right: a tainted value passed
// to a deferred call reaches the call in the instruction stream, where the
// engine can see it.
//
// What is wrong is the ORDER: the instruction puts the call at the defer
// statement, while Go runs it at the function's exit. The call may in
// reality happen after code that the engine will now believe ran BEFORE
// it.
//
// The alternative -- keeping the call only in the deferredCalls side table
// -- is much worse. The side table is not part of the instruction stream,
// so a `defer os.WriteFile(name, tainted, ...)` would leave no trace in the
// TAC at all: the finding would be absent, and the scan would still report
// success. So the call is emitted AND the side table is kept, and the
// function's precision is lowered and a problem says why.
//
// The lowering does NOT suppress any finding: the analysis engine does not
// read the precision level, so the degradation is a record of the
// approximation rather than a filter on the results.
func (t *translator) translateDefer(statement *ast.DeferStmt) {
	target, ok := t.resolveCallee(statement.Call.Fun)
	if !ok {
		return
	}
	if target.kind != callRegular && target.kind != callBuiltin {
		// A deferred conversion, a deferred `new` or a deferred `panic` is
		// legal Go, and it is not a deferred CALL. There is no side table
		// entry for it, so the loss is reported.
		t.report(statement, ProblemUnhandledNode,
			"the deferred call was not a call to a function and was not recorded")
		return
	}

	// The receiver and the arguments are evaluated HERE, because that is
	// when Go evaluates them.
	args := []Operand{}
	if target.receiver != nil {
		args = append(args, *target.receiver)
	}
	for _, argument := range statement.Call.Args {
		args = append(args, *t.valueOf(argument))
	}
	signature := target.signature
	if target.kind == callBuiltin {
		signature = syntheticSignature(target.builtinName, len(args))
	}

	// The CALL goes into the instruction stream, right after the
	// arguments' own instructions.
	result := t.newTemp(nil)
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     result,
		CalleeSig: signature,
		Args:     args,
	}, statement.Pos())

	// And the side table records that the call really happens at the
	// function's exit.
	position := t.fset.Position(statement.Pos())
	t.deferred = append(t.deferred, DeferredCall{
		CalleeSig: signature,
		Args:      args,
		Line:      position.Line,
		Column:    position.Column,
	})

	t.report(statement, ProblemSourceLowered,
		"a deferred call runs when the function exits, and the TAC represents it at the defer statement")
}

// translateGo lowers a go statement.
//
// A `go` statement starts a goroutine. The TAC has no concurrency model, so
// the call is lowered as an ordinary CALL and the function is marked.
//
// The marking says ONE thing: this function starts a goroutine. It says
// NOTHING about happens-before, about ordering, or about synchronisation.
//
// WHY THE PRECISION IS LOWERED. The instruction stream says the call
// happens HERE, in the middle of the function, and the engine will read it
// as an ordinary call in the ordinary order. Go says the call happens on
// another goroutine, at a time the analysis does not model, with no
// happens-before relation to anything else. A checker that relies on the
// order of the instructions would conclude something the program does not
// guarantee.
//
// So the function's precision is lowered and a problem says why. As with
// every other degradation, this does NOT suppress any finding: the engine
// does not read the precision level. It is a record of the approximation,
// not a filter on the results.
func (t *translator) translateGo(statement *ast.GoStmt) {
	t.startsGoroutine = true
	t.translateCall(statement.Call, nil)
	t.report(statement, ProblemSourceLowered,
		"a goroutine was lowered to an ordinary call: the analysis models no happens-before between goroutines")
}

// translateBranch lowers a break, a continue, a goto or a fallthrough.
func (t *translator) translateBranch(statement *ast.BranchStmt) {
	switch statement.Tok {
	case token.BREAK:
		target := t.branchTarget(statement.Label)
		if target == nil || target.breakLabel == "" {
			t.report(statement, ProblemUnhandledNode, "the break had no enclosing loop to break out of")
			return
		}
		t.emit(Instruction{Kind: KindJump, Label: target.breakLabel}, statement.Pos())
	case token.CONTINUE:
		target := t.branchTarget(statement.Label)
		if target == nil || target.continueLabel == "" {
			t.report(statement, ProblemUnhandledNode, "the continue had no enclosing loop to continue")
			return
		}
		t.emit(Instruction{Kind: KindJump, Label: target.continueLabel}, statement.Pos())
	case token.FALLTHROUGH:
		label := t.fallthroughLabel()
		if label == "" {
			t.report(statement, ProblemUnhandledNode, "the fallthrough had no following case")
			return
		}
		t.emit(Instruction{Kind: KindJump, Label: label}, statement.Pos())
	case token.GOTO:
		// A goto jumps to an arbitrary label in the function, and the TAC's
		// branches carry no such target. The jump is not lowered, and the
		// function's control flow is therefore not the program's control
		// flow. That is a real loss, so it is reported rather than hidden.
		t.report(statement, ProblemUnhandledNode, "a goto was not lowered")
		t.emit(Instruction{Kind: KindNop}, statement.Pos())
	}
}

// branchTarget returns the target of a break or a continue.
//
// A LABELLED break or continue names its target; an unlabelled one takes the
// innermost enclosing loop or switch.
func (t *translator) branchTarget(label *ast.Ident) *breakContext {
	if label != nil {
		if target, ok := t.labelled[label.Name]; ok {
			return target
		}
		return nil
	}
	for index := len(t.breaks) - 1; index >= 0; index-- {
		context := t.breaks[index]
		if context.breakLabel != "" {
			return context
		}
	}
	return nil
}

// fallthroughLabel returns the label of the case that follows the current
// one.
func (t *translator) fallthroughLabel() string {
	if len(t.switches) == 0 {
		return ""
	}
	context := t.switches[len(t.switches)-1]
	next := context.current + 1
	if next < len(context.caseLabels) {
		return context.caseLabels[next]
	}
	return ""
}

// translateLabeled lowers a labelled statement.
func (t *translator) translateLabeled(statement *ast.LabeledStmt) {
	previous := t.pendingLabel
	t.pendingLabel = statement.Label.Name
	defer func() { t.pendingLabel = previous }()
	t.translateStatement(statement.Stmt)
}

// registerPendingLabel remembers the labels of a labelled loop or switch.
func (t *translator) registerPendingLabel(breakLabel string, continueLabel string) {
	if t.pendingLabel == "" {
		return
	}
	t.labelled[t.pendingLabel] = &breakContext{breakLabel: breakLabel, continueLabel: continueLabel}
	t.pendingLabel = ""
}

// translateIncDec lowers an increment or a decrement.
func (t *translator) translateIncDec(statement *ast.IncDecStmt) {
	current := t.valueOf(statement.X)
	operator := "ADD"
	amount := Const("INT", "1")
	if statement.Tok == token.DEC {
		operator = "SUB"
	}
	result := t.newTemp(nil)
	t.emit(Instruction{
		Kind: KindBinOp,
		Dest: result,
		Lhs1: current,
		Lhs2: amount,
		BinOp: operator,
	}, statement.Pos())
	t.emit(Instruction{Kind: KindMove, Dest: current, Lhs1: result}, statement.Pos())
}

// translateSend lowers a channel send.
func (t *translator) translateSend(statement *ast.SendStmt) {
	channel := t.valueOf(statement.Chan)
	value := t.valueOf(statement.Value)
	t.emit(Instruction{
		Kind:     KindCall,
		CalleeSig: syntheticSignature(syntheticChanSend, 2),
		Args:     []Operand{*channel, *value},
	}, statement.Pos())
}

// pushBreak pushes a break/continue context.
func (t *translator) pushBreak(context *breakContext) {
	t.breaks = append(t.breaks, context)
}

// popBreak pops the innermost break/continue context.
func (t *translator) popBreak() {
	if len(t.breaks) > 0 {
		t.breaks = t.breaks[:len(t.breaks)-1]
	}
}

// pushSwitch pushes a switch context.
func (t *translator) pushSwitch(context *switchContext) {
	t.switches = append(t.switches, context)
}

// popSwitch pops the innermost switch context.
func (t *translator) popSwitch() {
	if len(t.switches) > 0 {
		t.switches = t.switches[:len(t.switches)-1]
	}
}

// ---------------------------------------------------------------------------
// Expressions
// ---------------------------------------------------------------------------

// valueOf lowers an expression and always returns an operand.
//
// An expression that could not be lowered becomes an unknown value, so that
// the instruction that uses it still has something to refer to. The unknown
// value is not tainted and not proven safe -- the analysis simply does not
// know.
func (t *translator) valueOf(expression ast.Expr) *Operand {
	operand := t.translateExpr(expression)
	if operand != nil {
		return operand
	}
	return t.havoc(expression)
}

// translateExpr lowers an expression and returns the operand holding its
// value, or nil when the expression has no value or could not be lowered.
func (t *translator) translateExpr(expression ast.Expr) *Operand {
	switch typed := expression.(type) {
	case *ast.Ident:
		return t.translateIdent(typed)
	case *ast.BasicLit:
		return t.translateBasicLit(typed)
	case *ast.ParenExpr:
		return t.translateExpr(typed.X)
	case *ast.BinaryExpr:
		return t.translateBinary(typed)
	case *ast.UnaryExpr:
		return t.translateUnary(typed)
	case *ast.CallExpr:
		return t.translateCall(typed, nil)
	case *ast.SelectorExpr:
		return t.translateSelector(typed)
	case *ast.IndexExpr:
		return t.translateIndex(typed)
	case *ast.SliceExpr:
		return t.translateSlice(typed)
	case *ast.StarExpr:
		return t.translateStar(typed)
	case *ast.CompositeLit:
		return t.translateCompositeLit(typed)
	case *ast.TypeAssertExpr:
		return t.translateTypeAssert(typed, nil)
	case *ast.FuncLit:
		// A function literal is a closure: a code block with its own
		// captured environment. The TAC has no closure form, so a literal
		// is a function value the analysis cannot see into.
		t.report(expression, ProblemUnhandledNode, "a function literal was not lowered")
		return nil
	case *ast.ArrayType, *ast.MapType, *ast.StructType, *ast.InterfaceType,
		*ast.ChanType, *ast.FuncType:
		return t.translateTypeExpression(expression)
	default:
		t.report(expression, ProblemUnhandledNode,
			fmt.Sprintf("the expression %T was not lowered", expression))
		return nil
	}
}

// translateIdent lowers an identifier.
func (t *translator) translateIdent(ident *ast.Ident) *Operand {
	if ident.Name == "nil" {
		return Const("NULL", "")
	}
	object := t.info.ObjectOf(ident)
	if object == nil {
		t.report(ident, ProblemUnhandledNode, "the identifier could not be resolved")
		return nil
	}
	if constant, ok := object.(*types.Const); ok {
		operand := t.constOperand(constant)
		if operand == nil {
			return nil
		}
		t.bindings[constant] = operand
		return operand
	}
	if variable, ok := object.(*types.Var); ok {
		return t.binding(variable)
	}
	if _, ok := object.(*types.Func); ok {
		// A function used as a VALUE. Go's functions are first-class
		// values, and the TAC has no way to name one, so a call through
		// such a value is invisible to the analysis.
		t.report(ident, ProblemUnhandledNode, "a function value was not lowered")
		return nil
	}
	t.report(ident, ProblemUnhandledNode, "the identifier could not be resolved")
	return nil
}

// translateBasicLit lowers a literal.
func (t *translator) translateBasicLit(literal *ast.BasicLit) *Operand {
	switch literal.Kind {
	case token.INT:
		return Const("INT", literal.Value)
	case token.FLOAT:
		return Const("DOUBLE", literal.Value)
	case token.IMAG:
		// An imaginary literal. The TAC has no complex kind, so the value
		// cannot be represented; the loss is reported rather than hidden
		// behind a DOUBLE constant with a complex-looking text.
		t.report(literal, ProblemUnhandledNode, "an imaginary literal was not lowered")
		return nil
	case token.CHAR:
		return Const("CHAR", literal.Value)
	case token.STRING:
		value, err := strconv.Unquote(literal.Value)
		if err != nil {
			value = literal.Value
		}
		return Const("STRING", value)
	}
	t.report(literal, ProblemUnhandledNode, "the literal was not lowered")
	return nil
}

// constOperand builds the operand of a constant.
func (t *translator) constOperand(object *types.Const) *Operand {
	value := object.Val()
	if value == nil {
		return nil
	}
	kind := "INT"
	switch value.Kind() {
	case constant.Bool:
		kind = "BOOL"
	case constant.String:
		kind = "STRING"
	case constant.Float:
		kind = "DOUBLE"
	case constant.Int:
		kind = "INT"
	}
	if basic, ok := object.Type().Underlying().(*types.Basic); ok {
		switch basic.Kind() {
		case types.Float32:
			kind = "FLOAT"
		case types.Float64:
			kind = "DOUBLE"
		case types.Int64, types.Uint64:
			kind = "LONG"
		case types.String:
			kind = "STRING"
		case types.Bool:
			kind = "BOOL"
		}
	}
	text := value.String()
	if kind == "STRING" {
		// A string constant's own text is quoted; the operand carries the
		// text itself.
		text = constant.StringVal(value)
	}
	return Const(kind, text)
}

// translateBinary lowers a binary expression.
func (t *translator) translateBinary(expression *ast.BinaryExpr) *Operand {
	left := t.valueOf(expression.X)
	right := t.valueOf(expression.Y)
	result := t.newTemp(t.info.TypeOf(expression))
	t.emit(Instruction{
		Kind: KindBinOp,
		Dest: result,
		Lhs1: left,
		Lhs2: right,
		BinOp: binaryOperator(expression.Op),
	}, expression.Pos())
	return result
}

// binaryOperator maps a Go operator onto a TAC operator.
func binaryOperator(operator token.Token) string {
	switch operator {
	case token.ADD:
		return "ADD"
	case token.SUB:
		return "SUB"
	case token.MUL:
		return "MUL"
	case token.QUO:
		return "DIV"
	case token.REM:
		return "MOD"
	case token.AND:
		return "AND"
	case token.OR:
		return "OR"
	case token.XOR:
		return "XOR"
	case token.SHL:
		return "SHL"
	case token.SHR:
		return "SHR"
	case token.EQL:
		return "EQ"
	case token.NEQ:
		return "NE"
	case token.LSS:
		return "LT"
	case token.LEQ:
		return "LE"
	case token.GTR:
		return "GT"
	case token.GEQ:
		return "GE"
	case token.LAND:
		return "LAND"
	case token.LOR:
		return "LOR"
	}
	return "ADD"
}

// translateUnary lowers a unary expression.
func (t *translator) translateUnary(expression *ast.UnaryExpr) *Operand {
	switch expression.Op {
	case token.SUB:
		value := t.valueOf(expression.X)
		result := t.newTemp(t.info.TypeOf(expression))
		t.emit(Instruction{Kind: KindUnaryOp, Dest: result, Lhs1: value, UnOp: "NEG"}, expression.Pos())
		return result
	case token.NOT:
		value := t.valueOf(expression.X)
		result := t.newTemp(t.info.TypeOf(expression))
		t.emit(Instruction{Kind: KindUnaryOp, Dest: result, Lhs1: value, UnOp: "NOT"}, expression.Pos())
		return result
	case token.XOR:
		value := t.valueOf(expression.X)
		result := t.newTemp(t.info.TypeOf(expression))
		t.emit(Instruction{Kind: KindUnaryOp, Dest: result, Lhs1: value, UnOp: "BITNOT"}, expression.Pos())
		return result
	case token.ADD:
		return t.translateExpr(expression.X)
	case token.AND:
		// The address of a value. The TAC has no pointer type, so the
		// pointer and the value are the same thing here.
		//
		// The cost: the identity of the pointed-to value is lost, so a
		// write through the pointer is not tracked as a write to the
		// original. The taint is preserved, so the analysis stays on the
		// conservative side.
		t.report(expression, ProblemSourceLowered,
			"the address of a value was lowered to the value itself: the TAC has no pointer type")
		return t.valueOf(expression.X)
	case token.ARROW:
		return t.translateChannelReceive(expression, nil)
	}
	t.report(expression, ProblemUnhandledNode, "the unary expression was not lowered")
	return nil
}

// translateSelector lowers a selector expression.
func (t *translator) translateSelector(selector *ast.SelectorExpr) *Operand {
	if selection := t.info.Selections[selector]; selection != nil {
		switch selection.Kind() {
		case types.FieldVal:
			field, ok := selection.Obj().(*types.Var)
			if !ok {
				t.report(selector, ProblemUnhandledNode, "the selected member is not a field")
				return nil
			}
			receiver := t.valueOf(selector.X)
			result := t.newTemp(field.Type())
			t.emit(Instruction{
				Kind:      KindFieldLoad,
				Dest:      result,
				Lhs1:      receiver,
				FieldName: field.Name(),
			}, selector.Pos())
			return result
		case types.MethodVal:
			// A method used as a value. Go's methods are first-class
			// values, and the TAC has no way to name one.
			t.report(selector, ProblemUnhandledNode, "a method value was not lowered")
			return nil
		}
	}

	// A package-qualified name: a function, a constant or a variable.
	object := t.info.ObjectOf(selector.Sel)
	switch resolved := object.(type) {
	case *types.Const:
		operand := t.constOperand(resolved)
		if operand == nil {
			return nil
		}
		t.bindings[resolved] = operand
		return operand
	case *types.Var:
		return t.binding(resolved)
	case *types.Func:
		t.report(selector, ProblemUnhandledNode, "a function value was not lowered")
		return nil
	}
	t.report(selector, ProblemUnhandledNode, "the selector could not be resolved")
	return nil
}

// translateIndex lowers an index expression.
func (t *translator) translateIndex(expression *ast.IndexExpr) *Operand {
	if t.isMapIndex(expression) {
		return t.translateMapRead(expression, nil)
	}
	collection := t.valueOf(expression.X)
	index := t.valueOf(expression.Index)
	result := t.newTemp(t.info.TypeOf(expression))
	t.emit(Instruction{
		Kind: KindArrayLoad,
		Dest: result,
		Lhs1: collection,
		Lhs2: index,
	}, expression.Pos())
	return result
}

// isMapIndex reports whether an index expression indexes a map.
//
// The decision comes from the indexed expression's TYPE, not from the
// spelling: a map and a slice are indexed with the same syntax and mean
// completely different things.
func (t *translator) isMapIndex(expression *ast.IndexExpr) bool {
	collectionType := t.info.TypeOf(expression.X)
	if collectionType == nil {
		return false
	}
	return isMapType(collectionType.Underlying())
}

// translateMapRead lowers a map lookup.
//
// A map is not an array: it has no elements to index into, it has keys. So
// the lookup is a runtime operation and it is a CALL.
func (t *translator) translateMapRead(expression *ast.IndexExpr, destination *Operand) *Operand {
	collection := t.valueOf(expression.X)
	key := t.valueOf(expression.Index)
	if destination == nil {
		destination = t.newTemp(t.info.TypeOf(expression))
	}
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     destination,
		CalleeSig: syntheticSignature(syntheticMapIndex, 2),
		Args:     []Operand{*collection, *key},
	}, expression.Pos())
	return destination
}

// translateSlice lowers a slice expression.
func (t *translator) translateSlice(expression *ast.SliceExpr) *Operand {
	collection := t.valueOf(expression.X)
	args := []Operand{*collection}
	if expression.Low != nil {
		args = append(args, *t.valueOf(expression.Low))
	} else {
		args = append(args, *Const("INT", "0"))
	}
	if expression.High != nil {
		args = append(args, *t.valueOf(expression.High))
	} else {
		args = append(args, *Const("INT", "-1"))
	}
	result := t.newTemp(t.info.TypeOf(expression))
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     result,
		CalleeSig: syntheticSignature(syntheticSlice, len(args)),
		Args:     args,
	}, expression.Pos())
	return result
}

// translateStar lowers a pointer dereference.
//
// The TAC has no "load through a reference" opcode. The dereference is
// therefore modelled as a conversion of the pointer to the pointed-to type,
// which preserves the taint of the POINTER.
//
// The cost: the loaded value's own taint -- the taint of what the pointer
// points AT -- is not seen, because the TAC has no way to model the memory
// a pointer refers to. A checker that tracks taint through struct fields
// will see the pointer's taint instead of the field's. The loss is reported
// so that a reader of the TAC knows why.
func (t *translator) translateStar(expression *ast.StarExpr) *Operand {
	pointer := t.valueOf(expression.X)
	result := t.newTemp(t.info.TypeOf(expression))
	t.emit(Instruction{
		Kind:     KindUnaryOp,
		Dest:     result,
		Lhs1:     pointer,
		UnOp:     "CAST",
		CastType: mapGoType(t.info.TypeOf(expression)),
	}, expression.Pos())
	t.report(expression, ProblemSourceLowered,
		"a pointer dereference was lowered to a conversion: the TAC has no load through a reference")
	return result
}

// translateTypeAssert lowers a type assertion.
func (t *translator) translateTypeAssert(expression *ast.TypeAssertExpr, destination *Operand) *Operand {
	value := t.valueOf(expression.X)
	if destination == nil {
		destination = t.newTemp(t.info.TypeOf(expression))
	}
	t.emit(Instruction{
		Kind:     KindUnaryOp,
		Dest:     destination,
		Lhs1:     value,
		UnOp:     "CAST",
		CastType: mapGoType(t.info.TypeOf(expression.Type)),
	}, expression.Pos())
	return destination
}

// translateCompositeLit lowers a composite literal.
func (t *translator) translateCompositeLit(literal *ast.CompositeLit) *Operand {
	litType := t.info.TypeOf(literal)
	if litType == nil {
		t.report(literal, ProblemUnhandledNode, "the composite literal's type could not be resolved")
		return nil
	}
	underlying := litType.Underlying()

	switch {
	case isMapType(underlying):
		result := t.newTemp(litType)
		t.emit(Instruction{Kind: KindNew, Dest: result, CtorType: mapGoType(litType)}, literal.Pos())
		for _, element := range literal.Elts {
			pair, ok := element.(*ast.KeyValueExpr)
			if !ok {
				t.report(element, ProblemUnhandledNode, "the map literal's element was not lowered")
				continue
			}
			key := t.valueOf(pair.Key)
			value := t.valueOf(pair.Value)
			t.emit(Instruction{
				Kind:     KindCall,
				CalleeSig: syntheticSignature(syntheticMapStore, 3),
				Args:     []Operand{*result, *key, *value},
			}, element.Pos())
		}
		return result

	case isSliceType(underlying), isArrayType(underlying):
		component := mapGoType(elementType(underlying))
		result := t.newTemp(litType)
		t.emit(Instruction{
			Kind:     KindNewArray,
			Dest:     result,
			CtorType: component,
			Lhs1:     Const("INT", strconv.Itoa(len(literal.Elts))),
		}, literal.Pos())
		for index, element := range literal.Elts {
			value := t.valueOf(element)
			t.emit(Instruction{
				Kind: KindArrayStore,
				Dest: value,
				Lhs1: result,
				Lhs2: Const("INT", strconv.Itoa(index)),
			}, element.Pos())
		}
		return result

	default:
		result := t.newTemp(litType)
		t.emit(Instruction{Kind: KindNew, Dest: result, CtorType: mapGoType(litType)}, literal.Pos())
		for _, element := range literal.Elts {
			pair, ok := element.(*ast.KeyValueExpr)
			if !ok {
				t.report(element, ProblemUnhandledNode, "the struct literal's element was not lowered")
				continue
			}
			name, ok := pair.Key.(*ast.Ident)
			if !ok {
				t.report(element, ProblemUnhandledNode, "the struct literal's field name was not lowered")
				continue
			}
			value := t.valueOf(pair.Value)
			t.emit(Instruction{
				Kind:      KindFieldStore,
				Dest:      value,
				Lhs1:      result,
				FieldName: name.Name,
			}, element.Pos())
		}
		return result
	}
}

// translateTypeExpression lowers a type used in an expression.
//
// A type expression is a value only in a conversion or in a composite
// literal's type position. In any other position it names a type, and the
// type itself is known to the type checker.
func (t *translator) translateTypeExpression(expression ast.Expr) *Operand {
	t.report(expression, ProblemUnhandledNode, "a type used as a value was not lowered")
	return nil
}

// ---------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------

// A resolved callee.
type calleeTarget struct {
	// What kind of callee this is.
	kind calleeKind

	// The receiver, when the call has one. It is the TAC's args[0].
	receiver *Operand

	// The callee key, in the owner.method/arity form. It is empty for a
	// conversion, a panic and a `new`, because none of those is a call to
	// something the user wrote.
	signature string

	// The builtin's name, for a builtin call.
	builtinName string

	// The type an argument names, for a conversion, a `new` and a `make`.
	typeArg types.Type
}

// translateCall lowers a call expression.
//
// The RECEIVER IS args[0]. The TAC's call convention is "args[0] is the
// receiver when present, args[1..] are the actual arguments", and the engine
// reads a sink's argument index against that convention.
//
// This is NOT optional. A call whose argument list starts with the actual
// argument would put that argument where the catalogue expects the receiver,
// and the finding would never be raised -- while the scan still reported
// success.
func (t *translator) translateCall(call *ast.CallExpr, destination *Operand) *Operand {
	target, ok := t.resolveCallee(call.Fun)
	if !ok {
		// The callee could not be resolved. The value is unknown rather
		// than silently clean.
		return t.havoc(call)
	}

	switch target.kind {
	case callConversion:
		// A conversion is a CAST, not a call: there is no callee for a sink
		// catalogue to match against.
		argument := t.valueOf(call.Args[0])
		if destination == nil {
			destination = t.newTemp(target.typeArg)
		}
		t.emit(Instruction{
			Kind:     KindUnaryOp,
			Dest:     destination,
			Lhs1:     argument,
			UnOp:     "CAST",
			CastType: mapGoType(target.typeArg),
		}, call.Pos())
		return destination

	case callPanic:
		// A panic is the function's exceptional exit, so it is a THROW.
		argument := t.valueOf(call.Args[0])
		t.emit(Instruction{Kind: KindThrow, Lhs1: argument}, call.Pos())
		return nil

	case callNew:
		// `new(T)` allocates a zero value of T. The TAC's NEW carries the
		// constructed type, and the callee key uses the same "<init>"
		// convention the other frontends use, so a constructor-shaped sink
		// has a key to match against.
		if destination == nil {
			destination = t.newTemp(target.typeArg)
		}
		t.emit(Instruction{
			Kind:     KindNew,
			Dest:     destination,
			CtorType: mapGoType(target.typeArg),
			CalleeSig: calleeSignature(className(target.typeArg), syntheticNewObject, 0),
		}, call.Pos())
		return destination

	case callMake:
		return t.translateMake(call, target, destination)

	case callBuiltin:
		args := []Operand{}
		for _, argument := range call.Args {
			args = append(args, *t.valueOf(argument))
		}
		if destination == nil {
			destination = t.newTemp(t.info.TypeOf(call))
		}
		t.emit(Instruction{
			Kind:     KindCall,
			Dest:     destination,
			CalleeSig: syntheticSignature(target.builtinName, len(args)),
			Args:     args,
		}, call.Pos())
		return destination
	}

	args := []Operand{}
	if target.receiver != nil {
		args = append(args, *target.receiver)
	}
	for _, argument := range call.Args {
		args = append(args, *t.valueOf(argument))
	}

	if destination == nil {
		tuple, _ := t.info.TypeOf(call).(*types.Tuple)
		if tuple != nil && tuple.Len() > 0 {
			destination = t.newTemp(tuple.At(0).Type())
		} else {
			destination = t.newTemp(nil)
		}
	}

	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     destination,
		CalleeSig: target.signature,
		Args:     args,
	}, call.Pos())
	return destination
}

// translateMake lowers a call to the `make` builtin.
//
// `make([]T, n)` allocates a slice: that is the TAC's NEWARRAY, whose
// component type is T and whose length is n. The capacity, when it is
// given, is dropped -- the TAC's ARRAY carries a component type only.
//
// `make(map[K]V)` and `make(chan T)` have no TAC shape at all, so they
// become a synthetic call.
func (t *translator) translateMake(call *ast.CallExpr, target calleeTarget, destination *Operand) *Operand {
	if len(call.Args) == 0 {
		t.report(call, ProblemUnhandledNode, "the make builtin had no type argument")
		return nil
	}
	madeType := target.typeArg
	if madeType == nil {
		madeType = t.info.TypeOf(call.Args[0])
	}

	if madeType != nil && isSliceType(madeType.Underlying()) {
		if destination == nil {
			destination = t.newTemp(madeType)
		}
		length := Const("INT", "0")
		if len(call.Args) > 1 {
			length = t.valueOf(call.Args[1])
		}
		t.emit(Instruction{
			Kind:     KindNewArray,
			Dest:     destination,
			CtorType: mapGoType(elementType(madeType.Underlying())),
			Lhs1:     length,
		}, call.Pos())
		return destination
	}

	args := []Operand{}
	if destination == nil {
		destination = t.newTemp(madeType)
	}
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     destination,
		CalleeSig: syntheticSignature(syntheticMake, len(call.Args)),
		Args:     args,
	}, call.Pos())
	return destination
}

// resolveCallee works out what a call calls.
//
// The callee key comes from the resolved symbol, never from the spelling: a
// method call names the receiver's type and the method, and a package-level
// call names the package and the function.
func (t *translator) resolveCallee(expression ast.Expr) (calleeTarget, bool) {
	switch typed := expression.(type) {
	case *ast.Ident:
		object := t.info.ObjectOf(typed)
		switch resolved := object.(type) {
		case *types.Builtin:
			// A builtin: `len`, `cap`, `append` and the rest. They are
			// universe objects, so they have no package and no signature.
			return t.resolveBuiltin(expression, resolved)
		case *types.Func:
			signature, ok := resolved.Type().(*types.Signature)
			if !ok {
				t.report(expression, ProblemUnhandledNode, "the callee's signature could not be read")
				return calleeTarget{}, false
			}
			if signature.Recv() != nil {
				// A method value: the method without its receiver. Go's
				// method values are first-class functions, and the TAC has
				// no way to name one.
				t.report(expression, ProblemUnhandledNode, "a method value was not lowered")
				return calleeTarget{}, false
			}
			return calleeTarget{
				kind:      callRegular,
				signature: calleeSignature(resolved.Pkg().Path(), resolved.Name(), signatureArity(signature)),
			}, true
		case *types.TypeName:
			return t.resolveConversion(expression, resolved.Type())
		case *types.Var:
			t.report(expression, ProblemUnhandledNode, "a call of a function value was not lowered")
			return calleeTarget{}, false
		}
		t.report(expression, ProblemUnhandledNode, "the callee could not be resolved")
		return calleeTarget{}, false

	case *ast.SelectorExpr:
		if selection := t.info.Selections[typed]; selection != nil {
			switch selection.Kind() {
			case types.MethodVal:
				function, ok := selection.Obj().(*types.Func)
				if !ok {
					t.report(expression, ProblemUnhandledNode, "the called method could not be resolved")
					return calleeTarget{}, false
				}
				signature, ok := selection.Type().(*types.Signature)
				if !ok {
					t.report(expression, ProblemUnhandledNode, "the called method's signature could not be read")
					return calleeTarget{}, false
				}
				receiver := t.valueOf(typed.X)
				return calleeTarget{
					kind:      callRegular,
					receiver:  receiver,
					signature: calleeSignature(methodOwner(selection.Recv()), function.Name(), signatureArity(signature)),
				}, true
			case types.FieldVal:
				t.report(expression, ProblemUnhandledNode, "a call of a function-valued field was not lowered")
				return calleeTarget{}, false
			}
		}
		// A package-qualified function.
		function, ok := t.info.ObjectOf(typed.Sel).(*types.Func)
		if !ok {
			t.report(expression, ProblemUnhandledNode, "the callee could not be resolved")
			return calleeTarget{}, false
		}
		signature, ok := function.Type().(*types.Signature)
		if !ok {
			t.report(expression, ProblemUnhandledNode, "the callee's signature could not be read")
			return calleeTarget{}, false
		}
		return calleeTarget{
			kind:      callRegular,
			signature: calleeSignature(function.Pkg().Path(), function.Name(), signatureArity(signature)),
		}, true

	case *ast.ArrayType, *ast.MapType, *ast.StructType, *ast.InterfaceType,
		*ast.ChanType, *ast.FuncType:
		// A conversion whose target type is written out: `[]byte(s)`.
		return t.resolveConversion(expression, t.info.TypeOf(expression))

	case *ast.IndexExpr:
		// A generic function instantiation. The type arguments do not
		// change the callee key -- the TAC erases generics -- so the base
		// expression is resolved instead.
		return t.resolveCallee(typed.X)

	case *ast.IndexListExpr:
		return t.resolveCallee(typed.X)

	case *ast.FuncLit:
		t.report(expression, ProblemUnhandledNode, "a call of a function literal was not lowered")
		return calleeTarget{}, false
	}

	t.report(expression, ProblemUnhandledNode,
		fmt.Sprintf("the callee %T was not lowered", expression))
	return calleeTarget{}, false
}

// resolveConversion lowers a conversion: `T(x)`.
//
// A conversion is not a call: there is no callee to match against a sink
// catalogue. It is a CAST, which is what the TAC has for it.
func (t *translator) resolveConversion(expression ast.Expr, targetType types.Type) (calleeTarget, bool) {
	if targetType == nil {
		t.report(expression, ProblemUnhandledNode, "the conversion's target type could not be resolved")
		return calleeTarget{}, false
	}
	return calleeTarget{kind: callConversion, typeArg: targetType}, true
}

// resolveBuiltin lowers a call to a builtin.
//
// The builtins are not calls to anything the user wrote, so they get
// synthetic callee keys. `len` and `cap` are the most common by far, and
// they are calls to the runtime, so they are modelled as such.
//
// `panic` is the exceptional exit of the function, so it is a THROW rather
// than a call, and `new` and `make` allocate, so they are the TAC's NEW and
// NEWARRAY.
func (t *translator) resolveBuiltin(expression ast.Expr, function *types.Builtin) (calleeTarget, bool) {
	name := function.Name()
	switch name {
	case "panic":
		return calleeTarget{kind: callPanic}, true
	case "new":
		return calleeTarget{kind: callNew}, true
	case "make":
		return calleeTarget{kind: callMake}, true
	case "len", "cap", "append", "copy", "delete", "print", "println", "recover",
		"complex", "real", "imag", "min", "max", "clear":
		return calleeTarget{kind: callBuiltin, builtinName: name}, true
	}
	t.report(expression, ProblemUnhandledNode,
		fmt.Sprintf("the builtin %q was not lowered", name))
	return calleeTarget{}, false
}

// translateChannelReceive lowers a channel receive.
func (t *translator) translateChannelReceive(expression *ast.UnaryExpr, destination *Operand) *Operand {
	channel := t.valueOf(expression.X)
	if destination == nil {
		destination = t.newTemp(t.info.TypeOf(expression))
	}
	t.emit(Instruction{
		Kind:     KindCall,
		Dest:     destination,
		CalleeSig: syntheticSignature(syntheticChanRecv, 1),
		Args:     []Operand{*channel},
	}, expression.Pos())
	return destination
}

// ---------------------------------------------------------------------------
// Small helpers on Go types
// ---------------------------------------------------------------------------

// isMapType reports whether a type is a map.
func isMapType(goType types.Type) bool {
	_, ok := goType.(*types.Map)
	return ok
}

// isSliceType reports whether a type is a slice.
func isSliceType(goType types.Type) bool {
	_, ok := goType.(*types.Slice)
	return ok
}

// isArrayType reports whether a type is a fixed-size array.
func isArrayType(goType types.Type) bool {
	_, ok := goType.(*types.Array)
	return ok
}

// isChanType reports whether a type is a channel.
func isChanType(goType types.Type) bool {
	_, ok := goType.(*types.Chan)
	return ok
}

// elementType returns a slice's or an array's element type.
func elementType(goType types.Type) types.Type {
	switch typed := goType.(type) {
	case *types.Slice:
		return typed.Elem()
	case *types.Array:
		return typed.Elem()
	}
	return nil
}
