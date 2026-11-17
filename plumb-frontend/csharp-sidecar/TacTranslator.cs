using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace Plumb.CSharpSidecar;

/// <summary>
/// Lowers a C# method body into Plumb's TAC.
/// </summary>
/// <remarks>
/// <para>
/// <b>The one rule that decides the whole design.</b> A C# property assignment is
/// a call to the property's setter, and it is lowered as a <c>CALL</c>:
/// </para>
/// <code>
/// cmd.CommandText = tainted;
/// </code>
/// <para>
/// becomes a <c>CALL</c> to <c>System.Data.SqlClient.SqlCommand.set_CommandText/1</c>
/// with the assigned value as argument 0. It is NOT a <c>FIELDSTORE</c>. The
/// reason is structural, not stylistic: Plumb's DFA engine queries the sink
/// catalogue only on <c>CALL</c> instructions, and <c>FIELDSTORE</c> carries no
/// callee signature at all. A property lowered as a field store would make every
/// property-shaped sink invisible — the finding would be absent while the scan
/// still reported success.
/// </para>
/// <para>
/// A genuine <b>field</b> assignment is still a <c>FIELDSTORE</c>, because a field
/// really is a field. The distinction comes from the resolved symbol
/// (<see cref="IPropertySymbol"/> versus <see cref="IFieldSymbol"/>), never from
/// the spelling.
/// </para>
/// </remarks>
internal sealed partial class TacTranslator
{
    private readonly SemanticModel _model;
    private readonly List<Instruction> _instructions = new();
    private readonly List<Problem> _problems = new();
    private readonly Dictionary<ISymbol, Operand> _operands = new();
    private readonly List<Operand> _params = new();
    private readonly List<Operand> _locals = new();
    private readonly HashSet<string> _labels = new();

    /// <summary>
    /// The losses of this function: the constructs that were not lowered
    /// faithfully.
    /// </summary>
    /// <remarks>
    /// They are kept apart from the problems because the two say different
    /// things. A problem says "the frontend could not do its job here". A
    /// drop says "the frontend DID its job, and here is what the job cost" --
    /// with an impact level that the engine turns into a precision.
    /// </remarks>
    private readonly List<Drop> _drops = new();

    /// <summary>The try/catch/finally regions of this function.</summary>
    private readonly List<TryRegion> _tryRegions = new();

    /// <summary>The local-scope bindings: a source name and the slot it lives in.</summary>
    private readonly List<ScopeBinding> _scopeBindings = new();

    /// <summary>The kind of member being translated.</summary>
    private string _memberKind = MemberKinds.Method;

    /// <summary>The number of try regions declared so far, for their ids.</summary>
    private int _tryRegionCounter;

    /// <summary>
    /// The labels a `break` and a `continue` in the CURRENT context jump to.
    /// </summary>
    /// <remarks>
    /// <para><b>Why they have to be tracked.</b> A `break` and a `continue` are
    /// JUMPS, and a jump needs a target. Dropping them is not a simplification:
    /// a dropped `break` inside a switch lets the case body fall through into
    /// the next one, and a dropped `continue` inside a loop runs the rest of
    /// the body instead of restarting the iteration. Both invent a control
    /// flow path the program does not have, and the dataflow engine walks it.
    /// </para>
    /// <para>The innermost context wins, and the context is saved and restored
    /// around every loop and switch so that a nested one does not leak its
    /// labels into the enclosing one.</para>
    /// </remarks>
    private readonly Stack<BreakContext> _breakContexts = new();

    /// <summary>Whether the .NET toolchain is available.</summary>
    private sealed class BreakContext
    {
        /// <summary>The label a `break` jumps to, or null when there is none.</summary>
        public string? BreakLabel { get; init; }

        /// <summary>The label a `continue` jumps to, or null when there is none.</summary>
        public string? ContinueLabel { get; init; }
    }

    private int _counter;
    private bool _degraded;

    /// <summary>
    /// The type that encloses the member being translated.
    /// </summary>
    /// <remarks>
    /// It is the owner of the closures the member declares, and the owner the
    /// callee keys are built from.
    /// </remarks>
    private INamedTypeSymbol? _ownerType;

    /// <summary>
    /// The name of the member being translated.
    /// </summary>
    /// <remarks>
    /// A closure's name carries it, so a report says which member the closure
    /// came from without having to look at the signature.
    /// </remarks>
    private string _memberName = "";

    /// <summary>
    /// Build a translator.
    /// </summary>
    /// <param name="model">the semantic model of the file</param>
    /// <param name="closureCounter">
    /// the counter that names the closures of the project, or null for a
    /// counter of its own
    /// </param>
    public TacTranslator(SemanticModel model, ClosureCounter? closureCounter = null)
    {
        _model = model;
        _closureCounter = closureCounter ?? new ClosureCounter();
    }

    /// <summary>
    /// The type that encloses the member being translated.
    /// </summary>
    /// <returns>the type, or null when it cannot be resolved</returns>
    private INamedTypeSymbol? OwnerType() => _ownerType;

    /// <summary>Whether any construct could not be lowered faithfully.</summary>
    public bool Degraded => _degraded;

    /// <summary>The problems encountered while lowering.</summary>
    public IReadOnlyList<Problem> Problems => _problems;

    /// <summary>The losses of this function.</summary>
    public IReadOnlyList<Drop> Drops => _drops;

    /// <summary>The try/catch/finally regions of this function.</summary>
    public IReadOnlyList<TryRegion> TryRegions => _tryRegions;

    /// <summary>The local-scope bindings.</summary>
    public IReadOnlyList<ScopeBinding> ScopeBindings => _scopeBindings;

    /// <summary>The kind of member being translated.</summary>
    public string MemberKind => _memberKind;

    /// <summary>
    /// The precision this function's drops imply.
    /// </summary>
    /// <remarks>
    /// It is DERIVED, never asserted, and it never looks at the number of
    /// problems: a function with one lost value is still fully understood,
    /// and a function with one lost SEMANTICS is not. Deriving it from a
    /// count is what makes a silently dropped construct look like a fully
    /// analysed one.
    /// </remarks>
    public string PrecisionFromDrops()
    {
        var precision = "SOURCE_FULL";
        foreach (var drop in _drops)
        {
            switch (drop.Impact)
            {
                case DropImpact.Semantics:
                    return "SOURCE_DEGRADED";
                case DropImpact.Structure:
                    precision = "SOURCE_LOWERED";
                    break;
            }
        }
        return precision;
    }

    /// <summary>The parameters, in declaration order.</summary>
    public IReadOnlyList<Operand> Params => _params;

    /// <summary>The locals.</summary>
    public IReadOnlyList<Operand> Locals => _locals;

    /// <summary>The instruction stream, in source order.</summary>
    public IReadOnlyList<Instruction> Instructions => _instructions;

    /// <summary>Lower a method body.</summary>
    /// <param name="body">the method's body, or null for an abstract method</param>
    /// <param name="method">the method's symbol</param>
    public void Translate(SyntaxNode? body, IMethodSymbol? method)
    {
        _ownerType = method?.ContainingType;
        _memberName = method?.Name ?? "";

        if (method is not null)
        {
            foreach (var parameter in method.Parameters)
            {
                _params.Add(VariableOperand(parameter, isTemp: false));
            }
        }

        if (body is null)
        {
            // An abstract or extern method has no body. There is nothing to lower,
            // and saying so is not a degradation: the method's contract is its
            // signature, which the caller already has.
            return;
        }

        switch (body)
        {
            case MethodDeclarationSyntax methodDeclaration:
                TranslateBlock(methodDeclaration.Body);
                return;
            case ConstructorDeclarationSyntax constructor:
                TranslateBlock(constructor.Body);
                return;
            case AccessorDeclarationSyntax accessor:
                TranslateBlock(accessor.Body);
                return;
            case LocalFunctionStatementSyntax localFunction:
                TranslateBlock(localFunction.Body);
                return;
            default:
                Report(body, "UNHANDLED_NODE",
                    $"the body of a {body.Kind()} was not lowered", body.GetType().Name);
                return;
        }
    }

    /// <summary>
    /// Lower the TOP-LEVEL STATEMENTS of a file.
    /// </summary>
    /// <param name="statements">the statements</param>
    /// <param name="method">the synthesized entry point's symbol, or null</param>
    /// <remarks>
    /// <para>
    /// A file with top-level statements has no method of its own: the
    /// compiler wraps the statements in a synthesized entry point. Without
    /// this the whole file would be invisible to the analysis -- and a
    /// modern console application is exactly a file of top-level
    /// statements.
    /// </para>
    /// <para>
    /// The statements are lowered in source order, which is the order they
    /// run in.
    /// </para>
    /// </remarks>
    public void TranslateTopLevel(IEnumerable<GlobalStatementSyntax> statements, IMethodSymbol? method)
    {
        _ownerType = method?.ContainingType;
        _memberName = method?.Name ?? "";

        if (method is not null)
        {
            // The entry point's parameters -- `args` -- are the function's
            // parameters, exactly as a written method's are.
            foreach (var parameter in method.Parameters)
            {
                _params.Add(VariableOperand(parameter, isTemp: false));
            }
        }
        foreach (var statement in statements)
        {
            TranslateStatement(statement.Statement);
        }
    }

    /// <summary>Lower an expression-bodied member's arrow clause.</summary>
    /// <param name="clause">the arrow clause</param>
    /// <param name="owner">
    /// the member the arrow belongs to, when the translator has not already
    /// been told about it
    /// </param>
    public void TranslateArrow(ArrowExpressionClauseSyntax clause, IMethodSymbol? owner = null)
    {
        if (_ownerType is null && owner is not null)
        {
            _ownerType = owner.ContainingType;
            _memberName = owner.Name;
        }
        var value = TranslateExpression(clause.Expression, store: false);
        Emit(InstructionKind.RETURN, lhs1: value, node: clause);
    }

    private void TranslateBlock(BlockSyntax? block)
    {
        if (block is null)
        {
            return;
        }
        foreach (var statement in block.Statements)
        {
            TranslateStatement(statement);
        }
    }

    private void TranslateStatement(StatementSyntax statement)
    {
        switch (statement)
        {
            case ExpressionStatementSyntax expression:
                TranslateExpressionStatement(expression);
                return;

            case LocalDeclarationStatementSyntax local:
                TranslateVariableDeclaration(local.Declaration);
                return;

            case IfStatementSyntax ifStatement:
                TranslateIf(ifStatement);
                return;

            case ReturnStatementSyntax ret:
                TranslateReturn(ret);
                return;

            case BlockSyntax block:
                TranslateBlock(block);
                return;

            case ThrowStatementSyntax thrown:
                TranslateThrow(thrown);
                return;

            case TryStatementSyntax tryStatement:
                TranslateTry(tryStatement);
                return;

            case WhileStatementSyntax whileStatement:
                TranslateWhile(whileStatement);
                return;

            case ForStatementSyntax forStatement:
                TranslateFor(forStatement);
                return;

            case ForEachStatementSyntax foreachStatement:
                TranslateForEach(foreachStatement);
                return;

            case SwitchStatementSyntax switchStatement:
                TranslateSwitch(switchStatement);
                return;

            case BreakStatementSyntax breakStatement:
                TranslateBreak(breakStatement);
                return;

            case ContinueStatementSyntax continueStatement:
                TranslateContinue(continueStatement);
                return;

            case EmptyStatementSyntax:
                return;

            case UsingStatementSyntax usingStatement:
                TranslateUsing(usingStatement);
                return;

            case LockStatementSyntax lockStatement:
                // A lock is a monitor acquire/release around the body. The body is
                // still lowered; the lock itself is a concurrency detail the dataflow
                // engine does not model.
                var monitor = TranslateExpression(lockStatement.Expression, store: false);
                if (monitor is not null)
                {
                    Emit(InstructionKind.CALL, lhs1: monitor,
                        calleeSig: "System.Threading.Monitor.Enter/1", node: lockStatement);
                }
                TranslateStatement(lockStatement.Statement);
                if (monitor is not null)
                {
                    Emit(InstructionKind.CALL, lhs1: monitor,
                        calleeSig: "System.Threading.Monitor.Exit/1", node: lockStatement);
                }
                Drop(lockStatement, DropIds.LockContention,
                    "which thread enters the critical section first is a run-time fact, so the " +
                    "critical section is over-approximated",
                    DropImpact.Semantics);
                return;

            case DoStatementSyntax doStatement:
                TranslateDo(doStatement);
                return;

            case CheckedStatementSyntax checkedStatement:
                // `checked` and `unchecked` are the SAME node type: the
                // difference is whether the UNCHECKED keyword is present.
                //
                // The block's body is lowered normally either way. What
                // `checked` adds is that an arithmetic overflow THROWS, and
                // that is a property of the domain of the operands rather
                // than of the block's shape.
                TranslateStatement(checkedStatement.Block);
                if (checkedStatement.Keyword.IsKind(SyntaxKind.UncheckedKeyword))
                {
                    // `unchecked`: the arithmetic WRAPS, which is what C#
                    // does by default anyway. Nothing is lost.
                    return;
                }
                Drop(checkedStatement, DropIds.CheckedOverflow,
                    "an arithmetic overflow inside a checked block throws, and the throw is not " +
                    "modelled: the operands' domains are not carried on the instructions",
                    DropImpact.Semantics);
                return;

            case UnsafeStatementSyntax unsafeStatement:
                // An unsafe block is a block. The pointer semantics inside it
                // are a separate matter.
                TranslateStatement(unsafeStatement.Block);
                return;

            case FixedStatementSyntax fixedStatement:
                // A `fixed` block pins its variables for the duration. The
                // body is lowered normally.
                foreach (var declaration in fixedStatement.Declaration.Variables)
                {
                    var symbol = _model.GetDeclaredSymbol(declaration) as ILocalSymbol;
                    if (symbol is null)
                    {
                        continue;
                    }
                    _locals.Add(VariableOperand(symbol, isTemp: false));
                }
                TranslateStatement(fixedStatement.Statement);
                return;

            case GotoStatementSyntax gotoStatement:
                // A goto jumps to a label in the same function. The TAC has a
                // LABEL and a JUMP, so the jump is expressible: it is a JUMP
                // to the label's own name.
                //
                // It used to fall into the catch-all below, which DROPPED
                // THE WHOLE FUNCTION BODY that followed the goto -- the
                // statements after it were never lowered at all.
                if (gotoStatement.Expression is IdentifierNameSyntax gotoIdentifier)
                {
                    Emit(InstructionKind.JUMP, label: GotoLabelName(gotoIdentifier.Identifier.Text),
                        node: gotoStatement);
                    return;
                }
                if (gotoStatement.CaseOrDefaultKeyword.RawKind != 0)
                {
                    // `goto case X;` and `goto default;` jump into the
                    // enclosing switch. The switch's case labels are
                    // generated, so the source's name cannot be resolved to
                    // one of them here.
                    var target = gotoStatement.Expression is IdentifierNameSyntax caseIdentifier
                        ? caseIdentifier.Identifier.Text
                        : gotoStatement.CaseOrDefaultKeyword.Text;
                    Drop(gotoStatement, DropIds.GotoCaseTarget,
                        "a 'goto case' jumps to a case of the enclosing switch, whose label is " +
                        "generated, so the jump's target could not be resolved",
                        DropImpact.Structure);
                    Emit(InstructionKind.JUMP, label: GotoLabelName(target), node: gotoStatement);
                    return;
                }
                Report(gotoStatement, "UNHANDLED_NODE",
                    "a goto had no label", gotoStatement.GetType().Name);
                return;

            case LabeledStatementSyntax labeledStatement:
                // The label is a jump target, so the labelled statement is
                // preceded by a LABEL instruction.
                Emit(InstructionKind.LABEL, label: GotoLabelName(labeledStatement.Identifier.Text),
                    node: labeledStatement);
                TranslateStatement(labeledStatement.Statement);
                return;

            case YieldStatementSyntax yieldStatement:
                // A `yield return` is a suspension point of an iterator: the
                // value is produced, the consumer is given it, and the
                // iterator resumes later. The TAC has no state machine, so
                // the value is moved out and the suspension is recorded.
                if (yieldStatement.IsKind(SyntaxKind.YieldReturnStatement) &&
                    yieldStatement.Expression is ExpressionSyntax yieldExpression)
                {
                    var yielded = TranslateExpression(yieldExpression, store: false);
                    if (yielded is not null)
                    {
                        Emit(InstructionKind.MOVE, dest: NewTemp(), lhs1: yielded, node: yieldStatement);
                    }
                }
                else
                {
                    // `yield break` leaves the iterator.
                    Emit(InstructionKind.RETURN, node: yieldStatement);
                }
                Drop(yieldStatement, DropIds.IteratorStateMachine,
                    "an iterator's state machine is flattened: the consumer's resumption points are " +
                    "not modelled, so the order in which the iterator's states are re-entered is unknown",
                    DropImpact.Semantics);
                return;

            default:
                Report(statement, "UNHANDLED_NODE",
                    $"the statement {statement.Kind()} was not lowered", statement.GetType().Name);
                return;
        }
    }

    /// <summary>
    /// Lower a <c>do { } while (cond);</c> statement.
    /// </summary>
    /// <remarks>
    /// It used to fall into the catch-all, which dropped the WHOLE BODY: the
    /// statements inside the loop were never lowered at all, so a secret that
    /// reached a sink inside a do-while was invisible.
    /// </remarks>
    private void TranslateDo(DoStatementSyntax doStatement)
    {
        var headLabel = NewLabel("do_head");
        var bodyLabel = NewLabel("do_body");
        var endLabel = NewLabel("do_end");

        Emit(InstructionKind.LABEL, label: headLabel, node: doStatement);
        Emit(InstructionKind.LABEL, label: bodyLabel, node: doStatement);

        _breakContexts.Push(new BreakContext { BreakLabel = endLabel, ContinueLabel = headLabel });
        try
        {
            TranslateStatement(doStatement.Statement);
        }
        finally
        {
            _breakContexts.Pop();
        }

        var condition = TranslateExpression(doStatement.Condition, store: false);
        if (condition is not null)
        {
            // The condition is tested AFTER the body: a do-while runs its body
            // at least once.
            Emit(InstructionKind.BRANCH, lhs1: condition, trueLabel: headLabel,
                falseLabel: endLabel, node: doStatement);
        }
        else
        {
            Emit(InstructionKind.JUMP, label: headLabel, node: doStatement);
        }
        Emit(InstructionKind.LABEL, label: endLabel, node: doStatement);
    }

    /// <summary>
    /// The TAC label name of a C# label.
    /// </summary>
    /// <remarks>
    /// The names are prefixed so that a C# label cannot collide with one of
    /// the translator's own labels: the translator's labels are generated,
    /// and a generated name and a source name that happen to be equal would
    /// be one label.
    /// </remarks>
    private static string GotoLabelName(string name) => "goto_" + name;

    private void TranslateExpressionStatement(ExpressionStatementSyntax statement)
    {
        var expression = statement.Expression;

        if (expression is AssignmentExpressionSyntax assignment)
        {
            TranslateAssignment(assignment);
            return;
        }

        // A bare invocation: the result is discarded, but the SIDE EFFECT is the
        // point, so the CALL is still emitted. TranslateExpression with
        // store:false emits it.
        TranslateExpression(expression, store: false);
    }

    private void TranslateVariableDeclaration(VariableDeclarationSyntax declaration)
    {
        var typeSymbol = _model.GetTypeInfo(declaration.Type).Type;

        foreach (var declarator in declaration.Variables)
        {
            var symbol = _model.GetDeclaredSymbol(declarator) as ILocalSymbol;
            if (symbol is null)
            {
                continue;
            }
            var operand = VariableOperand(symbol, isTemp: false);
            _locals.Add(operand);

            if (declarator.Initializer is not null)
            {
                var value = TranslateExpression(declarator.Initializer.Value, store: false);
                if (value is not null)
                {
                    Emit(InstructionKind.MOVE, dest: operand, lhs1: value, node: declarator);
                }
                // A variable that HOLDS a lambda: `Action handler = x => ...`.
                // The registration is what makes `handler(y)` reach the
                // lambda's body.
                RegisterClosureHolder(symbol, declarator.Initializer.Value);
            }
        }
        _ = typeSymbol;
    }

    private void TranslateAssignment(AssignmentExpressionSyntax assignment)
    {
        var left = assignment.Left;
        var right = assignment.Right;

        // The value being assigned.
        var value = TranslateExpression(right, store: false);

        // Compound assignments (a += b) read the left side first.
        if (IsCompoundAssignment(assignment.Kind()))
        {
            var current = TranslateExpression(left, store: false);
            if (current is not null && value is not null)
            {
                var binaryOperator = BinaryOperatorOf(assignment.Kind());
                var temp = NewTemp();
                Emit(InstructionKind.BINOP, dest: temp, lhs1: current, lhs2: value,
                    binOp: binaryOperator, node: assignment);
                value = temp;
            }
        }

        switch (left)
        {
            case MemberAccessExpressionSyntax memberAccess:
                TranslateMemberAssignment(memberAccess, value, assignment);
                return;

            case IdentifierNameSyntax identifier:
            {
                var symbol = _model.GetSymbolInfo(identifier).Symbol;
                if (symbol is ILocalSymbol localSymbol)
                {
                    var operand = VariableOperand(localSymbol, isTemp: false);
                    if (value is not null)
                    {
                        Emit(InstructionKind.MOVE, dest: operand, lhs1: value, node: assignment);
                    }
                    RegisterClosureHolder(localSymbol, right);
                    return;
                }
                if (symbol is IParameterSymbol parameterSymbol)
                {
                    var operand = VariableOperand(parameterSymbol, isTemp: false);
                    if (value is not null)
                    {
                        Emit(InstructionKind.MOVE, dest: operand, lhs1: value, node: assignment);
                    }
                    RegisterClosureHolder(parameterSymbol, right);
                    return;
                }
                if (symbol is IFieldSymbol fieldSymbol)
                {
                    // A write to a FIELD through its bare name: `X = value;`.
                    //
                    // This is where a secret lands. In a constructor or in a
                    // setter, the field's name is the whole target -- there
                    // is no receiver to write through -- and the write used
                    // to be reported as unhandled. So the field was never
                    // written in the TAC, and a value that reached the field
                    // never reached anything that read it.
                    //
                    // The `field` keyword (a property's own backing field)
                    // resolves to an IFieldSymbol too, so the SAME branch
                    // lowers it -- and it writes to the same storage the
                    // property's accessors read and write.
                    var receiver = FieldReceiver(fieldSymbol, assignment);
                    if (receiver is not null && value is not null)
                    {
                        Emit(InstructionKind.FIELDSTORE, dest: value, lhs1: receiver,
                            fieldName: fieldSymbol.Name,
                            ownerType: TypeMapper.FullName(fieldSymbol.ContainingType),
                            node: assignment);
                    }
                    return;
                }
                Report(assignment, "UNHANDLED_NODE",
                    "the assignment's target could not be resolved", identifier.GetType().Name);
                return;
            }

            case ElementAccessExpressionSyntax elementAccess:
            {
                TranslateIndexerAssignment(elementAccess, value, assignment);
                return;
            }

            case ThisExpressionSyntax:
                Report(assignment, "UNHANDLED_NODE",
                    "an assignment to 'this' was not lowered", assignment.GetType().Name);
                return;

            default:
                Report(assignment, "UNHANDLED_NODE",
                    $"the assignment target {left.Kind()} was not lowered", left.GetType().Name);
                return;
        }
    }

    private void TranslateMemberAssignment(
        MemberAccessExpressionSyntax memberAccess,
        Operand? value,
        AssignmentExpressionSyntax assignment)
    {
        var symbol = _model.GetSymbolInfo(memberAccess).Symbol;

        // THE DECISIVE BRANCH. A PROPERTY is a method in disguise: its setter is
        // `set_X`, and the assignment is an invocation of it. A FIELD is a field,
        // and stays a field store.
        if (symbol is IPropertySymbol property)
        {
            var setter = property.SetMethod;
            if (setter is null)
            {
                // A read-only property cannot be assigned here. An init-only
                // property is assigned in an object initializer, which is a
                // different path. Either way there is nothing to lower.
                Report(assignment, "UNHANDLED_NODE",
                    $"the property '{property.Name}' has no setter", memberAccess.GetType().Name);
                return;
            }

            // The receiver is evaluated and becomes args[0], following the TAC's
            // call convention: "args[0] is the receiver, args[1..] are the
            // actual args".
            //
            // This is NOT optional. The engine reads the sink's argument index
            // against that convention, so a setter call whose argument list
            // starts with the assigned value would put the value at index 0
            // where the catalogue expects the receiver -- and the finding would
            // never be raised.
            //
            // A STATIC property has no receiver object, so the slot carries
            // the declaring type instead. The slot is still there: the engine
            // adds one to the catalogue's argument index unconditionally.
            var arguments = new List<Operand>();
            if (setter.IsStatic)
            {
                arguments.Add(TypePlaceholder(setter.ContainingType));
            }
            else
            {
                var receiver = TranslateExpression(memberAccess.Expression, store: false);
                if (receiver is not null)
                {
                    arguments.Add(receiver);
                }
            }
            if (value is not null)
            {
                arguments.Add(value);
            }
            Emit(InstructionKind.CALL, calleeSig: Signature.For(setter), args: arguments,
                node: assignment);
            return;
        }

        if (symbol is IFieldSymbol field)
        {
            var receiver = TranslateExpression(memberAccess.Expression, store: false);
            if (receiver is not null && value is not null)
            {
                Emit(InstructionKind.FIELDSTORE, dest: value, lhs1: receiver,
                    fieldName: field.Name, node: assignment);
            }
            return;
        }

        // An EVENT assignment is a delegate combine/remove, not a field store.
        if (symbol is IEventSymbol eventSymbol)
        {
            var receiver = TranslateExpression(memberAccess.Expression, store: false);
            if (receiver is not null && value is not null)
            {
                var arguments = new List<Operand> { receiver, value };
                Emit(InstructionKind.CALL,
                    calleeSig: $"{TypeMapper.FullName(eventSymbol.ContainingType)}.set_{eventSymbol.Name}/2",
                    args: arguments, node: assignment);
            }
            return;
        }

        Report(assignment, "UNHANDLED_NODE",
            "the assignment's member could not be resolved to a property or a field",
            memberAccess.GetType().Name);
    }

    /// <summary>
    /// The receiver slot of an implicit field write.
    /// </summary>
    /// <param name="field">the field being written</param>
    /// <param name="node">the node the write is at</param>
    /// <returns>
    /// the operand that stands for the object the field belongs to, or null
    /// when there is none
    /// </returns>
    /// <remarks>
    /// <para>
    /// An instance field belongs to <c>this</c>, so the receiver is the
    /// enclosing object. A STATIC field belongs to no object, so the
    /// receiver slot carries the declaring type -- the same convention the
    /// static call convention uses.
    /// </para>
    /// <para>
    /// The receiver is not optional. The engine's field tracker keys a field
    /// by the RECEIVER and the field's name, so a field store without a
    /// receiver is a field store that nothing can read.
    /// </para>
    /// </remarks>
    private Operand? FieldReceiver(IFieldSymbol field, SyntaxNode node)
    {
        if (field.IsStatic)
        {
            return TypePlaceholder(field.ContainingType);
        }
        var method = _model.GetEnclosingSymbol(node.SpanStart) as IMethodSymbol;
        return method is null ? null : OperandFor(method.ContainingType);
    }

    /// <summary>
    /// Lower an assignment through an INDEXER.
    /// </summary>
    /// <param name="elementAccess">the element access being assigned to</param>
    /// <param name="value">the assigned value</param>
    /// <param name="assignment">the assignment, for the source position</param>
    /// <remarks>
    /// <para>
    /// <b>The same spelling, two different things.</b> <c>holder["key"] = value</c>
    /// and <c>array[0] = value</c> are written the same way, and they are not
    /// the same thing. The first is a call to an indexer's setter -- a method
    /// named <c>set_Item</c> -- and the second is a store into an array.
    /// </para>
    /// <para>
    /// <b>Why the distinction decides whether findings exist.</b> The sink
    /// catalogue is queried on <c>CALL</c> instructions. An <c>ARRAYSTORE</c>
    /// carries no callee signature at all, so a sink reached through an indexer
    /// is not "less precisely matched" -- it is <i>structurally unreachable</i>,
    /// and the scan reports success while the finding is absent. The catalogue
    /// already carries the C# symbols: the <c>credential.sinks</c> family names
    /// <c>System.Data.Common.DbConnectionStringBuilder.set_Item</c>. The
    /// catalogue was right; the TAC was not.
    /// </para>
    /// <para>
    /// <b>What decides it.</b> The resolved symbol, and nothing else. An indexer
    /// is an <see cref="IPropertySymbol"/> whose <c>IsIndexer</c> is true, with
    /// a <c>set_Item</c> accessor; an array resolves to no symbol at all. The
    /// spelling of the expression is identical in both cases, so the spelling
    /// cannot decide it.
    /// </para>
    /// <para>
    /// <b>Why the receiver still comes first.</b> The TAC's call convention is
    /// "args[0] is the receiver, args[1..] are the actual arguments". An
    /// indexer's setter takes the key and then the value, so the value is the
    /// SECOND user argument and lands at args[2]. The catalogue names user
    /// arguments and the engine applies the receiver offset itself, so the
    /// catalogue writes <c>arg_index: 1</c> for the value of a
    /// <c>set_Item</c> and the TAC puts it at args[2].
    /// </para>
    /// </remarks>
    private void TranslateIndexerAssignment(
        ElementAccessExpressionSyntax elementAccess,
        Operand? value,
        AssignmentExpressionSyntax assignment)
    {
        var indexer = IndexerSymbolOf(elementAccess);

        if (indexer is null)
        {
            // Not an indexer: it is an array (or a type this frontend does not
            // understand). A genuine array stays an ARRAYSTORE, because an
            // array really is an array.
            var array = TranslateExpression(elementAccess.Expression, store: false);
            var index = elementAccess.ArgumentList.Arguments.Count > 0
                ? TranslateExpression(elementAccess.ArgumentList.Arguments[0].Expression, store: false)
                : null;
            if (array is not null && index is not null && value is not null)
            {
                Emit(InstructionKind.ARRAYSTORE, dest: value, lhs1: array, lhs2: index,
                    node: assignment);
            }
            return;
        }

        var setter = indexer.SetMethod;
        if (setter is null)
        {
            // A get-only indexer cannot be assigned. There is nothing to lower.
            Report(assignment, "UNHANDLED_NODE",
                $"the indexer '{indexer.Name}' has no setter", elementAccess.GetType().Name);
            return;
        }

        var arguments = new List<Operand>();
        var receiver = TranslateExpression(elementAccess.Expression, store: false);
        if (receiver is not null)
        {
            arguments.Add(receiver);
        }
        // The indexer's KEY comes before the value: the setter's signature is
        // set_Item(key, value).
        foreach (var argument in elementAccess.ArgumentList.Arguments)
        {
            var operand = TranslateExpression(argument.Expression, store: false);
            if (operand is not null)
            {
                arguments.Add(operand);
            }
        }
        if (value is not null)
        {
            arguments.Add(value);
        }

        Emit(InstructionKind.CALL, calleeSig: Signature.For(setter), args: arguments,
            node: assignment);
    }

    /// <summary>
    /// Lower a READ through an INDEXER.
    /// </summary>
    /// <param name="elementAccess">the element access being read</param>
    /// <returns>the operand holding the read value</returns>
    /// <remarks>
    /// <para>
    /// The read side matters for the same reason the write side does. A value
    /// read through an indexer and then handed to a sink is a finding, and an
    /// <c>ARRAYLOAD</c> carries no callee signature for the catalogue to match
    /// -- so the finding would be absent while the scan reported success.
    /// </para>
    /// <para>
    /// The distinction is the resolved symbol again: an indexer becomes a call
    /// to its getter, and an array stays an <c>ARRAYLOAD</c>.
    /// </para>
    /// </remarks>
    private Operand? TranslateIndexerRead(ElementAccessExpressionSyntax elementAccess)
    {
        var indexer = IndexerSymbolOf(elementAccess);

        if (indexer is null)
        {
            var array = TranslateExpression(elementAccess.Expression, store: false);
            var index = elementAccess.ArgumentList.Arguments.Count > 0
                ? TranslateExpression(elementAccess.ArgumentList.Arguments[0].Expression, store: false)
                : null;
            var loadTemp = NewTemp();
            if (array is not null && index is not null)
            {
                Emit(InstructionKind.ARRAYLOAD, dest: loadTemp, lhs1: array, lhs2: index,
                    node: elementAccess);
            }
            return loadTemp;
        }

        var getter = indexer.GetMethod;
        if (getter is null)
        {
            Report(elementAccess, "UNHANDLED_NODE",
                $"the indexer '{indexer.Name}' has no getter", elementAccess.GetType().Name);
            return null;
        }

        var arguments = new List<Operand>();
        var receiver = TranslateExpression(elementAccess.Expression, store: false);
        if (receiver is not null)
        {
            arguments.Add(receiver);
        }
        foreach (var argument in elementAccess.ArgumentList.Arguments)
        {
            var operand = TranslateExpression(argument.Expression, store: false);
            if (operand is not null)
            {
                arguments.Add(operand);
            }
        }

        var readTemp = NewTemp();
        Emit(InstructionKind.CALL, dest: readTemp, calleeSig: Signature.For(getter),
            args: arguments, node: elementAccess);
        return readTemp;
    }

    /// <summary>
    /// The INDEXER that an element access resolves to, or null when it is not
    /// an indexer at all.
    /// </summary>
    /// <param name="elementAccess">the element access</param>
    /// <returns>the indexer symbol, or null</returns>
    /// <remarks>
    /// <para>
    /// <b>Why an indexer is a PROPERTY.</b> Roslyn models a C# indexer as an
    /// <see cref="IPropertySymbol"/> whose <see cref="IPropertySymbol.IsIndexer"/>
    /// is true. Its accessors are named <c>get_Item</c> and <c>set_Item</c>,
    /// which is why the sink catalogue names
    /// <c>System.Data.Common.DbConnectionStringBuilder.set_Item</c> for the
    /// indexer of that type: the catalogue's symbol IS the accessor's symbol.
    /// </para>
    /// <para>
    /// <b>Why the symbol decides it.</b> An array access resolves to no symbol
    /// at all, and an indexer access resolves to this property. The two are
    /// written identically, so the spelling cannot tell them apart.
    /// </para>
    /// </remarks>
    private IPropertySymbol? IndexerSymbolOf(ElementAccessExpressionSyntax elementAccess)
    {
        if (_model.GetSymbolInfo(elementAccess).Symbol is IPropertySymbol property
            && property.IsIndexer)
        {
            return property;
        }
        return null;
    }

    private void TranslateIf(IfStatementSyntax ifStatement)
    {
        var condition = TranslateExpression(ifStatement.Condition, store: false);
        var trueLabel = NewLabel("if_true");
        var falseLabel = NewLabel("if_false");
        var endLabel = NewLabel("if_end");

        if (condition is not null)
        {
            Emit(InstructionKind.BRANCH, lhs1: condition, trueLabel: trueLabel,
                falseLabel: falseLabel, node: ifStatement);
        }

        Emit(InstructionKind.LABEL, label: trueLabel, node: ifStatement);
        TranslateStatement(ifStatement.Statement);
        Emit(InstructionKind.JUMP, label: endLabel, node: ifStatement);

        Emit(InstructionKind.LABEL, label: falseLabel, node: ifStatement);
        if (ifStatement.Else is not null)
        {
            TranslateStatement(ifStatement.Else.Statement);
        }

        Emit(InstructionKind.LABEL, label: endLabel, node: ifStatement);
    }

    private void TranslateReturn(ReturnStatementSyntax ret)
    {
        if (ret.Expression is null)
        {
            Emit(InstructionKind.RETURN, node: ret);
            return;
        }
        var value = TranslateExpression(ret.Expression, store: false);
        Emit(InstructionKind.RETURN, lhs1: value, node: ret);
    }

    private void TranslateThrow(ThrowStatementSyntax thrown)
    {
        if (thrown.Expression is null)
        {
            // A rethrow: `throw;`.
            Emit(InstructionKind.THROW, node: thrown);
            return;
        }
        var value = TranslateExpression(thrown.Expression, store: false);
        Emit(InstructionKind.THROW, lhs1: value, node: thrown);
    }

    /// <summary>
    /// Lower a <c>try</c> statement.
    /// </summary>
    /// <remarks>
    /// <para>
    /// THE CATCH BODY IS NOT ON THE NORMAL PATH.
    /// </para>
    /// <para>
    /// It used to be. The try body was lowered, and then the catch bodies
    /// were lowered right after it, and the control flow fell from one into
    /// the other. So the analysis believed that the catch handler ran on
    /// every call, and that the code after the catch ran again afterwards.
    /// </para>
    /// <para>
    /// The shape now is the one the source has: the try body, a jump over
    /// the handlers, the handlers themselves, the finally block, and the
    /// code after the whole statement. Every catch ends with a jump to the
    /// finally block, and the finally block ends with a jump past itself.
    /// </para>
    /// <para>
    /// A catch FILTER is a condition on the handler: the handler runs only
    /// when the filter is true. The filter was never read, so a handler with
    /// a filter caught everything its type names.
    /// </para>
    /// </remarks>
    private void TranslateTry(TryStatementSyntax tryStatement)
    {
        var tryStart = NewLabel("try_start");
        var afterTry = NewLabel("try_after");
        var finallyLabel = tryStatement.Finally is null ? null : NewLabel("finally");
        var endLabel = NewLabel("try_end");

        Emit(InstructionKind.LABEL, label: tryStart, node: tryStatement);

        // The region's instruction range. The engine asks WHICH INSTRUCTIONS the region
        // covers, so the range has to be an index range: a label says where a place is,
        // not how far the region reaches.
        var tryStartIndex = _instructions.Count;
        TranslateBlock(tryStatement.Block);
        var tryEndIndex = _instructions.Count - 1;
        Emit(InstructionKind.JUMP, label: finallyLabel ?? afterTry, node: tryStatement);

        var catchLabels = new List<string>();
        var catchTypes = new List<string>();
        var finallyExits = new List<string> { afterTry };
        var catchStartIndex = -1;
        var catchEndIndex = -1;
        var finallyStartIndex = -1;
        var finallyEndIndex = -1;

        for (var index = 0; index < tryStatement.Catches.Count; index++)
        {
            var clause = tryStatement.Catches[index];
            var label = NewLabel("catch");
            catchLabels.Add(label);

            var caughtType = clause.Declaration is null
                ? null
                : _model.GetTypeInfo(clause.Declaration.Type).Type;
            catchTypes.Add(caughtType is null ? "" : TypeMapper.FullName(caughtType));

            Emit(InstructionKind.LABEL, label: label, node: clause);
            if (catchStartIndex < 0)
            {
                catchStartIndex = _instructions.Count - 1;
            }

            // A FILTER decides whether this handler runs at all. When it is
            // false, the exception goes to the NEXT handler.
            if (clause.Filter is not null)
            {
                var filter = TranslateExpression(clause.Filter.FilterExpression, store: false);
                var nextLabel = index + 1 < tryStatement.Catches.Count
                    ? NewLabel("catch_next")
                    : (finallyLabel ?? afterTry);
                if (filter is not null)
                {
                    Emit(InstructionKind.BRANCH, lhs1: filter, trueLabel: label,
                        falseLabel: nextLabel, node: clause);
                }
                Drop(clause.Filter, DropIds.CatchFilter,
                    "a catch clause's filter is a condition on the handler, and the handler's " +
                    "exception type is the whole of what the exception edge says",
                    DropImpact.Structure);
            }

            Operand? parameterOperand = null;
            if (clause.Declaration is not null)
            {
                var parameter = _model.GetDeclaredSymbol(clause.Declaration) as IParameterSymbol;
                if (parameter is not null)
                {
                    parameterOperand = VariableOperand(parameter, isTemp: false);
                    _locals.Add(parameterOperand);
                }
            }

            Emit(InstructionKind.CATCH_HANDLER, dest: parameterOperand,
                ctorType: TypeMapper.Map(caughtType), node: clause);

            TranslateBlock(clause.Block);
            finallyExits.Add(label);
            Emit(InstructionKind.JUMP, label: finallyLabel ?? afterTry, node: clause);
            catchEndIndex = _instructions.Count - 1;
        }

        if (tryStatement.Finally is not null && finallyLabel is not null)
        {
            Emit(InstructionKind.FINALLY_ENTER, label: finallyLabel, node: tryStatement.Finally);
            Emit(InstructionKind.LABEL, label: finallyLabel, node: tryStatement.Finally);
            finallyStartIndex = _instructions.Count;
            TranslateBlock(tryStatement.Finally.Block);
            finallyEndIndex = _instructions.Count - 1;
            Emit(InstructionKind.JUMP, label: endLabel, node: tryStatement.Finally);
        }

        Emit(InstructionKind.LABEL, label: afterTry, node: tryStatement);
        if (finallyLabel is not null)
        {
            // The code after the statement is reached from the try body's
            // normal exit and from the finally block.
            Emit(InstructionKind.LABEL, label: endLabel, node: tryStatement);
        }

        _tryRegionCounter++;
        _tryRegions.Add(new TryRegion
        {
            Id = "R" + _tryRegionCounter,
            TryStart = tryStartIndex,
            TryEnd = tryEndIndex,
            CatchStart = catchStartIndex,
            CatchEnd = catchEndIndex,
            CatchLabels = catchLabels,
            CatchTypes = catchTypes,
            FinallyLabel = finallyLabel,
            FinallyStart = finallyStartIndex,
            FinallyEnd = finallyEndIndex,
            FinallyExits = finallyExits,
        });
    }

    private void TranslateBreak(BreakStatementSyntax breakStatement)
    {
        // The innermost context that has a break label wins: a `break` inside a
        // switch inside a loop breaks the SWITCH, and a `break` inside a loop
        // breaks the LOOP.
        foreach (var context in _breakContexts)
        {
            if (context.BreakLabel is null)
            {
                continue;
            }
            Emit(InstructionKind.JUMP, label: context.BreakLabel, node: breakStatement);
            return;
        }

        // A `break` with no target is a `break` outside every loop and switch,
        // which does not compile. The problem is reported rather than the
        // statement being silently dropped.
        Report(breakStatement, "UNHANDLED_NODE",
            "a `break` had no enclosing loop or switch to break out of",
            breakStatement.GetType().Name);
    }

    private void TranslateContinue(ContinueStatementSyntax continueStatement)
    {
        // Only a loop has a `continue` target: a switch has none, because C#'s
        // `continue` inside a switch continues the enclosing LOOP.
        foreach (var context in _breakContexts)
        {
            if (context.ContinueLabel is null)
            {
                continue;
            }
            Emit(InstructionKind.JUMP, label: context.ContinueLabel, node: continueStatement);
            return;
        }

        Report(continueStatement, "UNHANDLED_NODE",
            "a `continue` had no enclosing loop to continue",
            continueStatement.GetType().Name);
    }

    private void TranslateWhile(WhileStatementSyntax whileStatement)
    {
        var startLabel = NewLabel("while_start");
        var endLabel = NewLabel("while_end");
        var conditionLabel = NewLabel("while_cond");

        Emit(InstructionKind.JUMP, label: conditionLabel, node: whileStatement);
        Emit(InstructionKind.LABEL, label: startLabel, node: whileStatement);
        // A `continue` in a `while` jumps to the CONDITION, which is where the
        // next iteration is decided -- not to the body, which would run the body
        // again without re-testing it.
        _breakContexts.Push(new BreakContext { BreakLabel = endLabel, ContinueLabel = conditionLabel });
        try
        {
            TranslateStatement(whileStatement.Statement);
        }
        finally
        {
            _breakContexts.Pop();
        }
        Emit(InstructionKind.JUMP, label: conditionLabel, node: whileStatement);

        Emit(InstructionKind.LABEL, label: conditionLabel, node: whileStatement);
        var condition = TranslateExpression(whileStatement.Condition, store: false);
        if (condition is not null)
        {
            Emit(InstructionKind.BRANCH, lhs1: condition, trueLabel: startLabel,
                falseLabel: endLabel, node: whileStatement);
        }

        Emit(InstructionKind.LABEL, label: endLabel, node: whileStatement);
    }

    private void TranslateFor(ForStatementSyntax forStatement)
    {
        // The initialiser.
        if (forStatement.Declaration is not null)
        {
            TranslateVariableDeclaration(forStatement.Declaration);
        }
        foreach (var initializer in forStatement.Initializers)
        {
            TranslateExpression(initializer, store: false);
        }

        var startLabel = NewLabel("for_start");
        var bodyLabel = NewLabel("for_body");
        var incrementLabel = NewLabel("for_inc");
        var endLabel = NewLabel("for_end");

        Emit(InstructionKind.JUMP, label: startLabel, node: forStatement);
        Emit(InstructionKind.LABEL, label: startLabel, node: forStatement);

        if (forStatement.Condition is not null)
        {
            var condition = TranslateExpression(forStatement.Condition, store: false);
            if (condition is not null)
            {
                // The condition's true edge goes to the BODY, not to the
                // increment. Routing it to the increment would run the
                // incrementor on the first iteration too, and a `continue`
                // that jumps to the increment would then run it twice.
                Emit(InstructionKind.BRANCH, lhs1: condition, trueLabel: bodyLabel,
                    falseLabel: endLabel, node: forStatement);
            }
        }

        Emit(InstructionKind.LABEL, label: bodyLabel, node: forStatement);

        // A `continue` in a `for` jumps to the INCREMENT: the incrementor runs
        // and the condition is tested again. Jumping to the condition instead
        // would skip the incrementor, and the loop would never advance.
        _breakContexts.Push(new BreakContext { BreakLabel = endLabel, ContinueLabel = incrementLabel });
        try
        {
            TranslateStatement(forStatement.Statement);
        }
        finally
        {
            _breakContexts.Pop();
        }

        Emit(InstructionKind.LABEL, label: incrementLabel, node: forStatement);
        foreach (var incrementor in forStatement.Incrementors)
        {
            TranslateExpression(incrementor, store: false);
        }
        Emit(InstructionKind.JUMP, label: startLabel, node: forStatement);

        Emit(InstructionKind.LABEL, label: endLabel, node: forStatement);
    }

    private void TranslateForEach(ForEachStatementSyntax foreachStatement)
    {
        var collection = TranslateExpression(foreachStatement.Expression, store: false);
        var symbol = _model.GetDeclaredSymbol(foreachStatement) as ILocalSymbol;
        if (symbol is null)
        {
            Report(foreachStatement, "UNHANDLED_NODE",
                "the foreach's variable could not be resolved", foreachStatement.GetType().Name);
            return;
        }
        var operand = VariableOperand(symbol, isTemp: false);
        _locals.Add(operand);

        if (collection is not null)
        {
            Emit(InstructionKind.MOVE, dest: operand, lhs1: collection, node: foreachStatement);
        }

        // The iteration itself is not modelled as an array walk: the TAC has no
        // iterator protocol. The element is bound to the loop variable, which is
        // what the dataflow engine needs -- the collection's taint reaches the
        // variable.
        //
        // The loop still has its SHAPE, though: a body, an increment and an
        // end. Without them a `break` inside the body has no target, and the
        // body would run exactly once -- which is the opposite of what the
        // program does.
        var bodyLabel = NewLabel("foreach_body");
        var incrementLabel = NewLabel("foreach_inc");
        var endLabel = NewLabel("foreach_end");

        Emit(InstructionKind.LABEL, label: bodyLabel, node: foreachStatement);
        Emit(InstructionKind.NOP, node: foreachStatement);

        _breakContexts.Push(new BreakContext { BreakLabel = endLabel, ContinueLabel = incrementLabel });
        try
        {
            TranslateStatement(foreachStatement.Statement);
        }
        finally
        {
            _breakContexts.Pop();
        }

        Emit(InstructionKind.LABEL, label: incrementLabel, node: foreachStatement);
        Emit(InstructionKind.JUMP, label: bodyLabel, node: foreachStatement);
        Emit(InstructionKind.LABEL, label: endLabel, node: foreachStatement);
    }

    private void TranslateSwitch(SwitchStatementSyntax switchStatement)
    {
        // A switch becomes a branch chain: the discriminant is compared against
        // each case label in turn. The semantics are preserved; the structural
        // fact "this was a switch" is not -- which is exactly what
        // IrPrecision.SOURCE_LOWERED says.
        _degraded = true;

        var discriminant = TranslateExpression(switchStatement.Expression, store: false);
        var endLabel = NewLabel("switch_end");
        var caseLabels = new List<string>();
        var defaultIndex = -1;
        for (var index = 0; index < switchStatement.Sections.Count; index++)
        {
            caseLabels.Add(NewLabel("case"));
            // The default section is the one that runs when no case matched. It
            // is reachable in two ways: the LAST comparison's false edge, and
            // the jump that follows the whole chain. Without both, the default
            // body is unreachable and the switch silently loses a branch.
            foreach (var clause in switchStatement.Sections[index].Labels)
            {
                if (clause is DefaultSwitchLabelSyntax)
                {
                    defaultIndex = index;
                }
            }
        }
        // Where "no case matched" goes: the default body when there is one, the
        // end of the switch otherwise.
        var noMatchLabel = defaultIndex >= 0 ? caseLabels[defaultIndex] : endLabel;

        // The comparison chain comes FIRST, and the case bodies come SECOND.
        //
        // The order is load-bearing. A branch that selects a case must land on
        // the case BODY, so the label it targets has to be defined AFTER the
        // comparison. When the two are interleaved -- label, comparison, body,
        // label, comparison, body -- the "true" edge of the first comparison
        // points BACK at its own label: the branch re-runs the comparison
        // instead of entering the body, and the body is only ever reached by
        // falling into it from the previous case.
        //
        // The result of the interleaved form is still a well-formed CFG: every
        // label resolves and the builder accepts it. It is just semantically
        // wrong, and wrong in a way a scan cannot see -- it reports success.
        for (var index = 0; index < switchStatement.Sections.Count; index++)
        {
            var section = switchStatement.Sections[index];
            var label = caseLabels[index];
            foreach (var clause in section.Labels)
            {
                if (clause is CaseSwitchLabelSyntax caseLabel)
                {
                    var caseValue = TranslateExpression(caseLabel.Value, store: false);
                    if (discriminant is not null && caseValue is not null)
                    {
                        var comparison = NewTemp();
                        Emit(InstructionKind.BINOP, dest: comparison, lhs1: discriminant,
                            lhs2: caseValue, binOp: "EQ", node: caseLabel);
                        Emit(InstructionKind.BRANCH, lhs1: comparison, trueLabel: label,
                            falseLabel: NextCaseLabel(caseLabels, label, noMatchLabel), node: caseLabel);
                    }
                }
            }
        }

        // No case matched: the default body, or the end of the switch.
        Emit(InstructionKind.JUMP, label: noMatchLabel, node: switchStatement);

        // Now the bodies, each under its own label.
        //
        // A `break` inside a case jumps to the end of the switch. The switch
        // has NO continue target: in C#, a `continue` inside a switch continues
        // the enclosing LOOP, not the switch.
        _breakContexts.Push(new BreakContext { BreakLabel = endLabel, ContinueLabel = null });
        try
        {
            for (var index = 0; index < switchStatement.Sections.Count; index++)
            {
                var section = switchStatement.Sections[index];
                Emit(InstructionKind.LABEL, label: caseLabels[index], node: section);
                foreach (var statement in section.Statements)
                {
                    TranslateStatement(statement);
                }
                Emit(InstructionKind.JUMP, label: endLabel, node: section);
            }
        }
        finally
        {
            _breakContexts.Pop();
        }

        Emit(InstructionKind.LABEL, label: endLabel, node: switchStatement);
    }

    private string NextCaseLabel(List<string> caseLabels, string current, string endLabel)
    {
        int index = caseLabels.IndexOf(current);
        if (index >= 0 && index + 1 < caseLabels.Count)
        {
            return caseLabels[index + 1];
        }
        return endLabel;
    }

    /// <summary>
    /// Lower a <c>using</c> statement.
    /// </summary>
    /// <remarks>
    /// <para>
    /// A <c>using</c> statement is a <c>try</c>/<c>finally</c> in disguise:
    /// the resource is acquired, the body runs, and the resource is disposed
    /// on EVERY way out of the body -- including the exceptional one.
    /// </para>
    /// <para>
    /// It used to be lowered as the resource's acquisition and the body, and
    /// nothing else: <c>Dispose()</c> was never called, so the resource's
    /// lifetime was invisible, and a rule about a resource that is not
    /// disposed had nothing to find.
    /// </para>
    /// </remarks>
    private void TranslateUsing(UsingStatementSyntax usingStatement)
    {
        var resources = new List<(string Name, Operand Operand)>();
        if (usingStatement.Declaration is not null)
        {
            TranslateVariableDeclaration(usingStatement.Declaration);
            foreach (var declarator in usingStatement.Declaration.Variables)
            {
                var symbol = _model.GetDeclaredSymbol(declarator) as ILocalSymbol;
                if (symbol is not null)
                {
                    resources.Add((symbol.Name, VariableOperand(symbol, isTemp: false)));
                }
            }
        }
        if (usingStatement.Expression is not null)
        {
            // `using (expr) { }` -- the EXPRESSION is the resource. It used to
            // be translated and then forgotten: no disposal was emitted for
            // it, so the resource's lifetime was invisible in the
            // instruction stream, and a rule about an undisposed resource had
            // nothing to find.
            var operand = TranslateExpression(usingStatement.Expression, store: false);
            if (operand is not null)
            {
                var name = usingStatement.Expression is IdentifierNameSyntax identifier
                    ? identifier.Identifier.Text
                    : "<expression>";
                resources.Add((name, operand));
            }
        }

        TranslateResourceScope(usingStatement.Statement, resources,
            disposeAsync: usingStatement.AwaitKeyword.IsKind(SyntaxKind.AwaitKeyword),
            node: usingStatement);
    }

    /// <summary>
    /// Lower the body of a resource scope, and the disposal that follows it.
    /// </summary>
    /// <param name="body">the body</param>
    /// <param name="resourceNames">the names of the resources the scope owns</param>
    /// <param name="disposeAsync">whether the disposal is asynchronous</param>
    /// <param name="node">the node the scope came from</param>
    private void TranslateResourceScope(StatementSyntax body, List<(string Name, Operand Operand)> resources,
        bool disposeAsync, SyntaxNode node)
    {
        var finallyLabel = NewLabel("using_finally");
        var endLabel = NewLabel("using_end");
        var tryStart = NewLabel("using_try");

        Emit(InstructionKind.LABEL, label: tryStart, node: node);
        var tryStartIndex = _instructions.Count;
        TranslateStatement(body);
        var tryEndIndex = _instructions.Count - 1;
        Emit(InstructionKind.JUMP, label: finallyLabel, node: node);

        Emit(InstructionKind.FINALLY_ENTER, label: finallyLabel, node: node);
        Emit(InstructionKind.LABEL, label: finallyLabel, node: node);
        var finallyStartIndex = _instructions.Count;
        foreach (var resource in resources)
        {
            // The disposal is a CALL on the resource. The receiver is the
            // resource itself; the callee's OWNER is the enclosing type,
            // because the resource's own type is not statically known from
            // the shape of the `using` statement.
            Emit(InstructionKind.CALL, lhs1: resource.Operand,
                calleeSig: disposeAsync ? $"{_ownerTypeName()}.DisposeAsync/0" : $"{_ownerTypeName()}.Dispose/0",
                node: node);
        }
        var finallyEndIndex = _instructions.Count - 1;
        Emit(InstructionKind.JUMP, label: endLabel, node: node);
        Emit(InstructionKind.LABEL, label: endLabel, node: node);

        _tryRegionCounter++;
        _tryRegions.Add(new TryRegion
        {
            Id = "R" + _tryRegionCounter,
            TryStart = tryStartIndex,
            TryEnd = tryEndIndex,
            CatchStart = -1,
            CatchEnd = -1,
            CatchLabels = new List<string>(),
            CatchTypes = new List<string>(),
            FinallyLabel = finallyLabel,
            FinallyStart = finallyStartIndex,
            FinallyEnd = finallyEndIndex,
            FinallyExits = new List<string> { endLabel },
            ResourceVars = resources.Select(resource => resource.Name).ToList(),
            SyntheticFinally = true,
        });

        Drop(node, DropIds.ResourceClosePath,
            "the resource is disposed on the normal path out of the body, but the exceptional path " +
            "does not reach the disposal in the instruction stream",
            DropImpact.Structure);
    }

    /// <summary>
    /// The name of the type that owns the member being translated.
    /// </summary>
    /// <remarks>
    /// It is empty when the member has no enclosing type, and the callee key
    /// then names the method without an owner.
    /// </remarks>
    private string _ownerTypeName() => _ownerType is null ? "" : TypeMapper.FullName(_ownerType);

    /// <summary>
    /// Lower a pattern into a test: an operand holding a boolean.
    /// </summary>
    /// <param name="value">the value the pattern is tested against</param>
    /// <param name="pattern">the pattern</param>
    /// <param name="node">the node the test belongs to</param>
    /// <returns>the operand holding the test's result, or null</returns>
    /// <remarks>
    /// <para>
    /// A TYPE pattern is an INSTANCEOF. A CONSTANT pattern is a comparison.
    /// A RELATIONAL pattern is a comparison.
    /// </para>
    /// <para>
    /// The patterns that combine other patterns -- <c>and</c>, <c>or</c> --
    /// are lowered as comparisons of the two sides' results, so the whole
    /// match is a boolean expression.
    /// </para>
    /// </remarks>
    private Operand? TranslatePatternTest(Operand? value, PatternSyntax pattern, SyntaxNode node)
    {
        switch (pattern)
        {
            case ConstantPatternSyntax constant:
            {
                var expected = TranslateExpression(constant.Expression, store: false);
                if (value is null || expected is null)
                {
                    return null;
                }
                var comparison = NewTemp();
                Emit(InstructionKind.BINOP, dest: comparison, lhs1: value, lhs2: expected,
                    binOp: "EQ", node: node);
                return comparison;
            }

            case RelationalPatternSyntax relational:
            {
                var bound = TranslateExpression(relational.Expression, store: false);
                if (value is null || bound is null)
                {
                    return null;
                }
                var comparison = NewTemp();
                Emit(InstructionKind.BINOP, dest: comparison, lhs1: value, lhs2: bound,
                    binOp: RelationalOperatorOf(relational.OperatorToken), node: node);
                return comparison;
            }

            case BinaryPatternSyntax binary:
            {
                var left = TranslatePatternTest(value, binary.Left, node);
                var right = TranslatePatternTest(value, binary.Right, node);
                if (left is null || right is null)
                {
                    return null;
                }
                var combined = NewTemp();
                Emit(InstructionKind.BINOP, dest: combined, lhs1: left, lhs2: right,
                    binOp: binary.OperatorToken.IsKind(SyntaxKind.OrKeyword) ? "LOR" : "LAND",
                    node: node);
                return combined;
            }

            case UnaryPatternSyntax unary:
            {
                var inner = TranslatePatternTest(value, unary.Pattern, node);
                if (inner is null)
                {
                    return null;
                }
                var negated = NewTemp();
                Emit(InstructionKind.UNARYOP, dest: negated, lhs1: inner, unOp: "NOT", node: node);
                return negated;
            }

            default:
            {
                // A type pattern, and everything else, is a type test.
                var type = TypeMapper.Map(_model.GetTypeInfo(pattern).Type);
                var test = NewTemp();
                Emit(InstructionKind.INSTANCEOF, dest: test, lhs1: value, ctorType: type, node: node);
                return test;
            }
        }
    }

    /// <summary>
    /// The TAC binary operator of a relational pattern's operator.
    /// </summary>
    /// <param name="operatorToken">the pattern's operator</param>
    /// <returns>the operator's name</returns>
    private static string RelationalOperatorOf(SyntaxToken operatorToken) => operatorToken.Kind() switch
    {
        SyntaxKind.LessThanExpression => "LT",
        SyntaxKind.LessThanOrEqualExpression => "LE",
        SyntaxKind.GreaterThanExpression => "GT",
        SyntaxKind.GreaterThanOrEqualExpression => "GE",
        _ => "EQ",
    };

    /// <summary>
    /// Lower <c>default(T)</c> and the <c>default</c> literal.
    /// </summary>
    /// <param name="defaultExpression">the expression</param>
    /// <returns>the operand holding the default value</returns>
    /// <remarks>
    /// The default value of a VALUE type is a known constant -- zero, or
    /// false -- and the default value of a REFERENCE type is null. It used to
    /// be null for both, so <c>default(int)</c> was a null where the
    /// program had a zero.
    /// </remarks>
    private Operand? TranslateDefault(DefaultExpressionSyntax defaultExpression)
    {
        var type = _model.GetTypeInfo(defaultExpression).Type;
        if (type is null || type.IsReferenceType)
        {
            return new Operand { Kind = "CONST", ConstKind = "NULL" };
        }
        if (type.SpecialType == SpecialType.System_Boolean)
        {
            return new Operand { Kind = "CONST", ConstKind = "BOOL", Value = "false" };
        }
        if (type.IsValueType)
        {
            return new Operand { Kind = "CONST", ConstKind = "INT", Value = "0" };
        }
        return new Operand { Kind = "CONST", ConstKind = "NULL" };
    }

    /// <summary>
    /// Lowers an expression.
    /// </summary>
    /// <param name="expression">the expression to lower</param>
    /// <param name="store">whether the result is stored into a destination</param>
    /// <returns>
    /// the operand holding the result, or null when the expression has no value
    /// or could not be lowered
    /// </returns>
    public Operand? TranslateExpression(ExpressionSyntax expression, bool store)
    {
        switch (expression)
        {
            case LiteralExpressionSyntax literal:
                return TranslateLiteral(literal);

            case IdentifierNameSyntax identifier:
                return TranslateIdentifier(identifier);

            case MemberAccessExpressionSyntax memberAccess:
                return TranslateMemberAccess(memberAccess, store);

            case InvocationExpressionSyntax invocation:
                return TranslateInvocation(invocation, store);

            case ObjectCreationExpressionSyntax creation:
                return TranslateObjectCreation(creation, store);

            case AssignmentExpressionSyntax assignment:
                TranslateAssignment(assignment);
                return null;

            case BinaryExpressionSyntax binary:
                return TranslateBinary(binary, store);

            case PrefixUnaryExpressionSyntax prefix:
                return TranslatePrefixUnary(prefix, store);

            case PostfixUnaryExpressionSyntax postfix:
                return TranslatePostfixUnary(postfix, store);

            case ParenthesizedExpressionSyntax parenthesized:
                return TranslateExpression(parenthesized.Expression, store);

            case CastExpressionSyntax cast:
            {
                var value = TranslateExpression(cast.Expression, store: false);
                var type = TypeMapper.Map(_model.GetTypeInfo(cast.Type).Type);
                var castTemp = NewTemp();
                Emit(InstructionKind.UNARYOP, dest: castTemp, lhs1: value, unOp: "CAST",
                    castType: type, node: cast);
                return castTemp;
            }

            case AwaitExpressionSyntax awaitExpression:
            {
                // `await` is a suspension point: the awaited value is
                // produced, the continuation runs later, and the function
                // may resume at any of its suspension points.
                //
                // The awaited expression is still lowered, so a tainted value
                // that is awaited stays tainted. What the instruction stream
                // does NOT say is where the function resumes: that is a
                // run-time fact, and it is recorded.
                var awaited = TranslateExpression(awaitExpression.Expression, store: false);
                if (awaited is not null)
                {
                    var awaitedTemp = NewTemp();
                    Emit(InstructionKind.CALL, dest: awaitedTemp, lhs1: awaited,
                        calleeSig: "<await>", effect: "SUSPEND", node: awaitExpression);
                    Drop(awaitExpression, DropIds.AwaitSuspend,
                        "an await suspends the function and resumes it later, and the resumption " +
                        "point is not carried on the instruction",
                        DropImpact.Semantics);
                    Drop(awaitExpression, DropIds.ConcurrencyOrder,
                        "the order in which an asynchronous continuation resumes is a run-time fact",
                        DropImpact.Semantics);
                    return awaitedTemp;
                }
                return awaited;
            }

            case ConditionalExpressionSyntax conditional:
            {
                // `a ? b : c` becomes a branch chain that selects.
                var condition = TranslateExpression(conditional.Condition, store: false);
                var trueLabel = NewLabel("cond_true");
                var falseLabel = NewLabel("cond_false");
                var endLabel = NewLabel("cond_end");
                var selectTemp = NewTemp();

                if (condition is not null)
                {
                    Emit(InstructionKind.BRANCH, lhs1: condition, trueLabel: trueLabel,
                        falseLabel: falseLabel, node: conditional);
                }
                Emit(InstructionKind.LABEL, label: trueLabel, node: conditional);
                var trueValue = TranslateExpression(conditional.WhenTrue, store: false);
                if (trueValue is not null)
                {
                    Emit(InstructionKind.MOVE, dest: selectTemp, lhs1: trueValue, node: conditional);
                }
                Emit(InstructionKind.JUMP, label: endLabel, node: conditional);

                Emit(InstructionKind.LABEL, label: falseLabel, node: conditional);
                var falseValue = TranslateExpression(conditional.WhenFalse, store: false);
                if (falseValue is not null)
                {
                    Emit(InstructionKind.MOVE, dest: selectTemp, lhs1: falseValue, node: conditional);
                }

                Emit(InstructionKind.LABEL, label: endLabel, node: conditional);
                return selectTemp;
            }

            case IsPatternExpressionSyntax isPattern:
            {
                // `x is T` -- a type test. The value is a boolean.
                //
                // `x is not T` is the NEGATION of the test. It used to be
                // lowered as `x is object`, which is true for every value:
                // so `is not` was always false, and a branch on it was dead.
                var value = TranslateExpression(isPattern.Expression, store: false);
                var pattern = isPattern.Pattern;

                if (pattern is UnaryPatternSyntax unary
                    && unary.OperatorToken.IsKind(SyntaxKind.NotKeyword))
                {
                    var inner = TranslatePatternTest(value, unary.Pattern, isPattern);
                    if (inner is null)
                    {
                        return null;
                    }
                    var negated = NewTemp();
                    Emit(InstructionKind.UNARYOP, dest: negated, lhs1: inner, unOp: "NOT",
                        node: isPattern);
                    return negated;
                }

                return TranslatePatternTest(value, pattern, isPattern);
            }
            case ElementAccessExpressionSyntax elementAccess:
            {
                return TranslateIndexerRead(elementAccess);
            }

            case ThisExpressionSyntax thisExpression:
            {
                // The enclosing symbol is read at the `this` TOKEN's position,
                // not at position 0. Position 0 is the start of the FILE, so
                // the enclosing symbol there is the compilation unit's, never
                // the member's -- and the receiver of every instance call on
                // `this` came back empty. Inside a lambda it is worse: the
                // lambda's OWN method symbol is the one that encloses the
                // token, and its containing type is the type the closure
                // belongs to.
                var method = _model.GetEnclosingSymbol(thisExpression.SpanStart) as IMethodSymbol;
                return method is null ? null : OperandFor(method.ContainingType);
            }

            case DefaultExpressionSyntax defaultExpression:
                return TranslateDefault(defaultExpression);

            case TypeOfExpressionSyntax typeOf:
            {
                // `typeof(X)` is a TYPE, not an instance of one. It used to
                // be a NEW -- a phantom allocation of a type that is never
                // allocated -- and the engine's points-to analysis believed
                // a value of that type existed.
                //
                // It is a CONSTANT: the type's name is a compile-time fact.
                var typeofType = _model.GetTypeInfo(typeOf.Type).Type;
                return new Operand
                {
                    Kind = "CONST",
                    ConstKind = "STRING",
                    Value = typeofType is null ? "<unknown type>" : TypeMapper.FullName(typeofType),
                };
            }

            case InterpolatedStringExpressionSyntax interpolated:
            {
                // An interpolated string is a concatenation of its parts. The
                // string's taint is the union of its parts' taints, so each part
                // is lowered and the result is treated as derived from all of
                // them.
                Operand? result = null;
                foreach (var part in interpolated.Contents)
                {
                    Operand? partOperand = part switch
                    {
                        InterpolatedStringTextSyntax text => new Operand
                        {
                            Kind = "CONST",
                            ConstKind = "STRING",
                            Value = text.TextToken.ValueText,
                        },
                        InterpolationSyntax interpolation =>
                            TranslateExpression(interpolation.Expression, store: false),
                        _ => null,
                    };
                    if (partOperand is null)
                    {
                        continue;
                    }
                    result = result is null
                        ? partOperand
                        : Concat(result, partOperand, interpolated);
                }
                return result;
            }

            case AnonymousFunctionExpressionSyntax lambda:
                return TranslateLambda(lambda);

            case AnonymousObjectCreationExpressionSyntax anonymous:
                return TranslateAnonymousObject(anonymous);

            case ConditionalAccessExpressionSyntax conditionalAccess:
                return TranslateConditionalAccess(conditionalAccess);

            default:
                Report(expression, "UNHANDLED_NODE",
                    $"the expression {expression.Kind()} was not lowered", expression.GetType().Name);
                return null;
        }
    }

    /// <summary>
    /// Handle the null-forgiving operator, <c>x!</c>.
    /// </summary>
    /// <remarks>
    /// The operator tells the COMPILER that the value is not null. It inserts NO
    /// runtime check: there is no <c>NullReferenceException</c> to catch, because
    /// the check does not exist at run time. So it is NOT a <c>NULL_CHECK</c>:
    /// emitting one would claim a check that does not exist, and the
    /// null-dereference checker would then suppress a finding that is entirely
    /// real.
    /// </remarks>
    /// <param name="postfix">the postfix expression, whose kind is the suppress operator</param>
    /// <returns>the operand holding the value</returns>
    public Operand? TranslateSuppressNullableWarning(PostfixUnaryExpressionSyntax postfix)
    {
        return TranslateExpression(postfix.Operand, store: false);
    }

    private Operand? Concat(Operand left, Operand right, SyntaxNode node)
    {
        var concatTemp = NewTemp();
        Emit(InstructionKind.BINOP, dest: concatTemp, lhs1: left, lhs2: right, binOp: "ADD",
            node: node);
        return concatTemp;
    }

    private Operand? TranslateLiteral(LiteralExpressionSyntax literal)
    {
        // The discriminator is the SYNTAX NODE's kind, not the TOKEN's.
        //
        // A literal's token kind is `NumericLiteralToken`, `TrueKeyword`,
        // `StringLiteralToken` and so on; the node kinds are
        // `NumericLiteralExpression`, `TrueLiteralExpression`,
        // `StringLiteralExpression`. Switching on the token kind matches
        // NOTHING, and every literal -- every number, every boolean, every
        // character -- silently fell through to the STRING default.
        //
        // The failure is invisible: the constant still carries its right VALUE,
        // so the TAC looks plausible, and the type checkers that care about the
        // constant's kind (an integer range check, a boolean test) all read a
        // string.
        var token = literal.Token;
        return literal.Kind() switch
        {
            SyntaxKind.StringLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "STRING",
                Value = token.ValueText,
            },
            SyntaxKind.Utf8StringLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "STRING",
                Value = token.ValueText,
            },
            SyntaxKind.CharacterLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "CHAR",
                Value = token.ValueText,
            },
            SyntaxKind.TrueLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "BOOL",
                Value = "true",
            },
            SyntaxKind.FalseLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "BOOL",
                Value = "false",
            },
            SyntaxKind.NullLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "NULL",
            },
            SyntaxKind.NumericLiteralExpression => NumericLiteral(literal),
            SyntaxKind.DefaultLiteralExpression => new Operand
            {
                Kind = "CONST",
                ConstKind = "NULL",
            },
            _ => new Operand
            {
                Kind = "CONST",
                ConstKind = "STRING",
                Value = token.ValueText,
            },
        };
    }

    private Operand NumericLiteral(LiteralExpressionSyntax literal)
    {
        var token = literal.Token;
        var text = token.ValueText;

        // The type comes from the literal EXPRESSION, not from its token's
        // parent. The parent of a numeric literal token is the equals-value
        // clause, and asking that node for its type yields the ASSIGNED type
        // -- which for `var i = 0` is the type INFERRED from the literal, and
        // for a boxed or converted literal is not the literal's own type.
        // The expression's type is the literal's type.
        //
        // The CONVERTED type is preferred when there is one: an integer
        // literal's own type is always `int`, and `long k = 0` is a conversion
        // to `long`. The converted type is what the literal is used as.
        var typeInfo = _model.GetTypeInfo(literal);
        var type = typeInfo.ConvertedType ?? typeInfo.Type;

        if (type?.SpecialType == SpecialType.System_Decimal)
        {
            return new Operand { Kind = "CONST", ConstKind = "DECIMAL", Value = text };
        }
        if (type?.SpecialType == SpecialType.System_Double)
        {
            return new Operand { Kind = "CONST", ConstKind = "DOUBLE", Value = text };
        }
        if (type?.SpecialType == SpecialType.System_Single)
        {
            return new Operand { Kind = "CONST", ConstKind = "FLOAT", Value = text };
        }
        if (type?.SpecialType == SpecialType.System_Int64
            || type?.SpecialType == SpecialType.System_UInt64)
        {
            return new Operand { Kind = "CONST", ConstKind = "LONG", Value = text };
        }
        return new Operand { Kind = "CONST", ConstKind = "INT", Value = text };
    }

    private Operand? TranslateIdentifier(IdentifierNameSyntax identifier)
    {
        var symbol = _model.GetSymbolInfo(identifier).Symbol;
        if (symbol is null)
        {
            // An unbound identifier: the source does not compile, or the symbol is
            // in a construct the model could not bind. The caller's diagnostic
            // already says so; here the value is simply unknown.
            var unknown = NewTemp();
            Emit(InstructionKind.HAVOC, dest: unknown, node: identifier);
            return unknown;
        }
        // A `const` field and an enum member are compile-time constants. Their
        // value is known, so the TAC holds the value -- not a variable that
        // nothing ever assigns, which would carry no value at all.
        if (symbol is IFieldSymbol constant
            && (constant.IsConst || constant.ContainingType.TypeKind == TypeKind.Enum))
        {
            return ConstantFieldValue(constant);
        }
        return OperandFor(symbol);
    }

    private Operand? TranslateMemberAccess(MemberAccessExpressionSyntax memberAccess, bool store)
    {
        var symbol = _model.GetSymbolInfo(memberAccess).Symbol;

        // A DYNAMIC member is bound at RUN TIME: which member is called, and
        // with what arguments, depends on the runtime type of the receiver.
        //
        // The compiler knows no member, so the access resolves to no symbol
        // at all -- and the call became a black box. That is the honest
        // answer, and it is recorded: the analysis does not know what the
        // call does.
        if (_model.GetTypeInfo(memberAccess.Expression).Type?.TypeKind == TypeKind.Dynamic)
        {
            var dynamicValue = NewTemp();
            Emit(InstructionKind.UNINTERPRETED, dest: dynamicValue, node: memberAccess);
            Drop(memberAccess, DropIds.DynamicBinding,
                "a member of a dynamic value is bound at run time, so the member that is called " +
                "is not known",
                DropImpact.Semantics);
            return dynamicValue;
        }

        // A member access that names a TYPE is a QUALIFIER, not a value.
        // `Environment.SpecialFolder.UserProfile` is the shape that matters:
        // the qualifier of the enum member is the nested enum TYPE, and
        // reading a type as a value failed -- the translator reported the
        // node as unhandled and emitted HAVOC, so the expression became
        // unknown and stopped carrying a value at all.
        //
        // The thing it names is known exactly, so it is a constant naming
        // the type -- the same shape the receiver slot of a static call uses.
        if (symbol is INamedTypeSymbol namedType)
        {
            return TypePlaceholder(namedType);
        }

        // An ENUM MEMBER is a compile-time constant, not a field read. Its
        // value is known, so it is a constant -- and a constant is exactly
        // what cannot carry taint, which is the right answer for a value
        // that came from the program itself.
        if (symbol is IFieldSymbol enumMember
            && enumMember.ContainingType.TypeKind == TypeKind.Enum)
        {
            return ConstantFieldValue(enumMember);
        }

        // A PROPERTY GETTER is a call, for the same reason the setter is: a
        // property accessor is a method. Reading `cmd.CommandText` is calling
        // `get_CommandText`.
        if (symbol is IPropertySymbol property)
        {
            var getter = property.GetMethod;
            // The receiver is args[0], following the TAC's call convention.
            var receiver = TranslateExpression(memberAccess.Expression, store: false);
            var getterTemp = NewTemp();
            if (getter is not null)
            {
                var arguments = new List<Operand>();
                if (receiver is not null)
                {
                    arguments.Add(receiver);
                }
                Emit(InstructionKind.CALL, dest: getterTemp, calleeSig: Signature.For(getter),
                    args: arguments, node: memberAccess);
            }
            else
            {
                Emit(InstructionKind.HAVOC, dest: getterTemp, node: memberAccess);
            }
            return getterTemp;
        }

        if (symbol is IFieldSymbol field)
        {
            var receiver = TranslateExpression(memberAccess.Expression, store: false);
            var fieldTemp = NewTemp();
            if (receiver is not null)
            {
                Emit(InstructionKind.FIELDLOAD, dest: fieldTemp, lhs1: receiver,
                    fieldName: field.Name, node: memberAccess);
            }
            else
            {
                Emit(InstructionKind.HAVOC, dest: fieldTemp, node: memberAccess);
            }
            return fieldTemp;
        }

        if (symbol is IEventSymbol eventSymbol)
        {
            var eventTemp = NewTemp();
            Emit(InstructionKind.UNINTERPRETED, dest: eventTemp,
                calleeSig: $"{TypeMapper.FullName(eventSymbol.ContainingType)}.{eventSymbol.Name}/0",
                args: new List<Operand>(), node: memberAccess);
            return eventTemp;
        }

        // A static member access has no receiver VALUE; the symbol is the member.
        if (symbol is not null && symbol.IsStatic)
        {
            return TranslateStaticMemberAccess(memberAccess, symbol);
        }

        Report(memberAccess, "UNHANDLED_NODE",
            "the member access could not be resolved", memberAccess.GetType().Name);
        var unknown = NewTemp();
        Emit(InstructionKind.HAVOC, dest: unknown, node: memberAccess);
        return unknown;
    }

    private Operand? TranslateStaticMemberAccess(MemberAccessExpressionSyntax memberAccess, ISymbol symbol)
    {
        if (symbol is IMethodSymbol method)
        {
            var staticTemp = NewTemp();
            // The receiver slot is filled with the declaring type, for the same
            // reason a static CALL's slot is: the engine reads every argument
            // index against the convention "args[0] is the receiver".
            Emit(InstructionKind.CALL, dest: staticTemp, calleeSig: Signature.For(method),
                args: new List<Operand> { TypePlaceholder(method.ContainingType) }, node: memberAccess);
            return staticTemp;
        }
        if (symbol is IFieldSymbol field)
        {
            var staticTemp = NewTemp();
            Emit(InstructionKind.FIELDLOAD, dest: staticTemp, fieldName: field.Name,
                node: memberAccess);
            return staticTemp;
        }
        Report(memberAccess, "UNHANDLED_NODE",
            "the static member access could not be resolved", memberAccess.GetType().Name);
        return null;
    }

    private Operand? TranslateInvocation(InvocationExpressionSyntax invocation, bool store)
    {
        // A call of a LAMBDA, or of a variable that holds one. The closure's
        // body is a function of its own, and this is the call that reaches it.
        if (TryTranslateClosureCall(invocation, out var closureResult))
        {
            return closureResult;
        }

        var method = _model.GetSymbolInfo(invocation).Symbol as IMethodSymbol;
        if (method is null)
        {
            // An unresolved call. The source does not compile; the value is
            // unknown rather than silently clean.
            Report(invocation, "UNHANDLED_NODE",
                "the invoked method could not be resolved", invocation.GetType().Name);
            var unknown = NewTemp();
            Emit(InstructionKind.HAVOC, dest: unknown, node: invocation);
            return unknown;
        }

        var arguments = new List<Operand>();

        // THE RECEIVER SLOT IS ALWAYS args[0]. The TAC's call convention is
        // "args[0] is the receiver, args[1..] are the actual args", and the
        // engine reads BOTH the sink's argument index and the callee
        // summary's formal binding against that convention.
        //
        // A STATIC call has no receiver OBJECT, but the SLOT is still there:
        // it carries a constant naming the type that declares the method.
        // That is what the Java frontend does for a static call
        // (`String.valueOf(x)` -> args = [const("java.lang.String"), x]),
        // and the engine relies on it:
        //
        //   - the sink check adds ONE to the catalogue's argument index
        //     unconditionally, so a static call whose first argument sat at
        //     args[0] would be read at args[1] -- one argument too far;
        //   - the callee summary binds formal i to args[i+1], and falls back
        //     to args[i] only when args[i+1] is out of range. A static call
        //     with ONE argument has args.size() == 1, so the fallback never
        //     applies and the summary binds NOTHING: the taint a callee
        //     returns from its parameter never reaches the caller.
        //
        // So the slot is filled with the declaring type's name. The loss is
        // that the slot is not a VALUE -- a static call has no receiver
        // value to carry taint -- and that is exactly what a constant says.
        Operand? receiver = null;
        bool reducedExtension = method.IsExtensionMethod && method.ReducedFrom is not null;
        if (invocation.Expression is MemberAccessExpressionSyntax memberAccess
            && !method.IsStatic)
        {
            receiver = TranslateExpression(memberAccess.Expression, store: false);
            if (receiver is not null)
            {
                arguments.Add(receiver);
            }
        }
        else if (method.IsStatic && !reducedExtension)
        {
            arguments.Add(TypePlaceholder(method.ContainingType));
        }

        foreach (var argument in invocation.ArgumentList.Arguments)
        {
            var operand = TranslateExpression(argument.Expression, store: false);
            if (operand is null)
            {
                var unknown = NewTemp();
                Emit(InstructionKind.HAVOC, dest: unknown, node: argument);
                operand = unknown;
            }
            arguments.Add(operand);
        }

        // An EXTENSION method's receiver is its first argument, and the reduced
        // form's parameter count is one less. The reduced signature is what the
        // callee key carries, and the receiver is already the first argument.
        if (method.IsExtensionMethod && method.ReducedFrom is not null)
        {
            var reduced = method.ReducedFrom;
            var extensionTemp = NewTemp();
            Emit(InstructionKind.CALL, dest: extensionTemp, calleeSig: Signature.For(reduced),
                args: arguments, node: invocation);
            return extensionTemp;
        }

        var calleeSignature = Signature.For(method);
        var result = NewTemp();
        Emit(InstructionKind.CALL, dest: result, calleeSig: calleeSignature, args: arguments,
            node: invocation);
        return result;
    }

    private Operand? TranslateObjectCreation(ObjectCreationExpressionSyntax creation, bool store)
    {
        var type = TypeMapper.Map(_model.GetTypeInfo(creation).Type);
        var arguments = new List<Operand>();
        if (creation.ArgumentList is not null)
        {
            foreach (var argument in creation.ArgumentList.Arguments)
            {
                var operand = TranslateExpression(argument.Expression, store: false);
                if (operand is not null)
                {
                    arguments.Add(operand);
                }
            }
        }

        // The object INITIALIZER (`new Foo { X = 1 }`) is a sequence of property
        // assignments after construction. Each one is a setter CALL, by the same
        // rule as any other property assignment.
        //
        // The DEST carries the constructed TYPE, not `UNKNOWN`. The resource-leak
        // rule decides whether an allocation is a resource from the destination
        // variable's type -- "a `new X(...)` is a resource only when X can be
        // closed" -- so an untyped destination makes the rule blind to the type
        // it needs. The Kotlin frontend sets the same thing, for the same
        // reason.
        var newTemp = NewTemp(type);
        Emit(InstructionKind.NEW, dest: newTemp, ctorType: type,
            calleeSig: ConstructorSignature(creation), args: arguments, node: creation);

        if (creation.Initializer is not null)
        {
            foreach (var initializer in creation.Initializer.Expressions)
            {
                if (initializer is AssignmentExpressionSyntax assignment)
                {
                    TranslateAssignment(assignment);
                }
            }
        }
        return newTemp;
    }

    /// <summary>
    /// Lower a NULL-CONDITIONAL access: <c>a?.B()</c> or <c>a?.Prop</c>.
    /// </summary>
    /// <param name="conditionalAccess">the conditional access</param>
    /// <returns>the operand holding the value</returns>
    /// <remarks>
    /// <para>
    /// The access happens ONLY when the receiver is not null, so it is a
    /// branch: the receiver is tested, and the access is on one side of it.
    /// Dropping the test would claim a path the program does not have -- the
    /// program says "and then only if it is there" -- and the dataflow
    /// engine walks the paths it is given.
    /// </para>
    /// <para>
    /// The value of the whole expression is null on the other side, so the
    /// two sides MERGE: the result carries the taint of the access when it
    /// happened, and nothing when it did not. That is the conservative
    /// direction -- the analysis cannot claim the value is clean when it
    /// might not be.
    /// </para>
    /// </remarks>
    private Operand? TranslateConditionalAccess(ConditionalAccessExpressionSyntax conditionalAccess)
    {
        var receiver = TranslateExpression(conditionalAccess.Expression, store: false);

        // The null test: the receiver is null or not.
        var isNull = NewTemp();
        if (receiver is not null)
        {
            Emit(InstructionKind.BINOP, dest: isNull, lhs1: receiver,
                lhs2: new Operand { Kind = "CONST", ConstKind = "NULL" }, binOp: "EQ",
                node: conditionalAccess);
        }

        var thenLabel = NewLabel("condaccess_then");
        var elseLabel = NewLabel("condaccess_else");
        var endLabel = NewLabel("condaccess_end");
        var result = NewTemp();

        if (receiver is not null)
        {
            Emit(InstructionKind.BRANCH, lhs1: isNull, trueLabel: elseLabel,
                falseLabel: thenLabel, node: conditionalAccess);
        }
        Emit(InstructionKind.LABEL, label: thenLabel, node: conditionalAccess);

        var value = TranslateConditionalTarget(conditionalAccess, receiver);
        if (value is not null)
        {
            Emit(InstructionKind.MOVE, dest: result, lhs1: value, node: conditionalAccess);
        }
        Emit(InstructionKind.JUMP, label: endLabel, node: conditionalAccess);

        // The other side: the access did not happen, so the value is null.
        // The result is NOT overwritten there: the merge is a union, and a
        // value that was tainted on one side stays tainted.
        Emit(InstructionKind.LABEL, label: elseLabel, node: conditionalAccess);
        Emit(InstructionKind.LABEL, label: endLabel, node: conditionalAccess);
        return result;
    }

    /// <summary>
    /// Lower what a null-conditional access reaches.
    /// </summary>
    /// <param name="conditionalAccess">the conditional access</param>
    /// <param name="receiver">the operand holding the receiver</param>
    /// <returns>the operand holding the value</returns>
    private Operand? TranslateConditionalTarget(
        ConditionalAccessExpressionSyntax conditionalAccess,
        Operand? receiver)
    {
        switch (conditionalAccess.WhenNotNull)
        {
            case InvocationExpressionSyntax invocation
                when invocation.Expression is MemberBindingExpressionSyntax binding:
            {
                // A delegate reached through `?.`: the call reaches the
                // closure's body, not the framework's `Invoke`.
                if (TryTranslateConditionalClosureCall(conditionalAccess, out var closureValue))
                {
                    return closureValue;
                }

                var method = _model.GetSymbolInfo(binding).Symbol as IMethodSymbol;
                if (method is null)
                {
                    return null;
                }
                var arguments = new List<Operand>();
                if (receiver is not null)
                {
                    arguments.Add(receiver);
                }
                foreach (var argument in invocation.ArgumentList.Arguments)
                {
                    var operand = TranslateExpression(argument.Expression, store: false);
                    if (operand is null)
                    {
                        var unknown = NewTemp();
                        Emit(InstructionKind.HAVOC, dest: unknown, node: argument);
                        operand = unknown;
                    }
                    arguments.Add(operand);
                }
                var callResult = NewTemp();
                Emit(InstructionKind.CALL, dest: callResult, calleeSig: Signature.For(method),
                    args: arguments, node: conditionalAccess);
                return callResult;
            }

            case MemberBindingExpressionSyntax binding:
            {
                var symbol = _model.GetSymbolInfo(binding).Symbol;
                if (symbol is IPropertySymbol property)
                {
                    var getter = property.GetMethod;
                    if (getter is null)
                    {
                        return null;
                    }
                    var arguments = new List<Operand>();
                    if (receiver is not null)
                    {
                        arguments.Add(receiver);
                    }
                    var propertyResult = NewTemp();
                    Emit(InstructionKind.CALL, dest: propertyResult, calleeSig: Signature.For(getter),
                        args: arguments, node: conditionalAccess);
                    return propertyResult;
                }
                if (symbol is IFieldSymbol field && receiver is not null)
                {
                    var fieldResult = NewTemp();
                    Emit(InstructionKind.FIELDLOAD, dest: fieldResult, lhs1: receiver,
                        fieldName: field.Name, node: conditionalAccess);
                    return fieldResult;
                }
                return null;
            }

            default:
                return TranslateExpression(conditionalAccess.WhenNotNull, store: false);
        }
    }

    /// <summary>
    /// Lower an ANONYMOUS OBJECT: <c>new { Name = "x", Age = 3 }</c>.
    /// </summary>
    /// <param name="creation">the anonymous object creation</param>
    /// <returns>the operand holding the object</returns>
    /// <remarks>
    /// <para>
    /// An anonymous object's members are its CONSTRUCTOR ARGUMENTS. Its
    /// properties are GET-ONLY -- the compiler writes them once, in the
    /// constructor -- so there is no setter to call. Lowering the member
    /// assignment as a property assignment would name a method that does not
    /// exist, and the value written would reach nothing.
    /// </para>
    /// <para>
    /// Passing the values as the constructor's arguments is also what makes
    /// the taint survive: the engine treats a constructed object as tainted
    /// when any of its constructor arguments is, so a tainted member taints
    /// the object, and the object's getter hands that taint back.
    /// </para>
    /// </remarks>
    private Operand? TranslateAnonymousObject(AnonymousObjectCreationExpressionSyntax creation)
    {
        var type = _model.GetTypeInfo(creation).Type;
        var ctorType = TypeMapper.Map(type);

        var arguments = new List<Operand>();
        foreach (var member in creation.Initializers)
        {
            // The member's syntax is a NAME and a VALUE: `Name = value` is
            // spelled `NameEquals` (the `Name =` part) and `Expression` (the
            // value). It is NOT an assignment expression -- so reading the
            // value out of an assignment would find nothing, and the object
            // would be built with no members at all.
            var value = TranslateExpression(member.Expression, store: false);
            if (value is null)
            {
                continue;
            }
            arguments.Add(value);
        }

        var newTemp = NewTemp(ctorType);
        Emit(InstructionKind.NEW, dest: newTemp, ctorType: ctorType, args: arguments,
            node: creation);
        return newTemp;
    }

    /// <summary>
    /// The callee signature of a constructor: <c>owner.&lt;init&gt;/arity</c>.
    /// </summary>
    /// <param name="creation">the object creation expression</param>
    /// <returns>
    /// the callee key, or <c>null</c> when the constructed type cannot be
    /// resolved -- in which case the NEW instruction carries no callee
    /// signature and no constructor sink can match it
    /// </returns>
    /// <remarks>
    /// <para>
    /// A NEW instruction carries BOTH <c>ctorType</c> and <c>calleeSig</c>.
    /// That is the established contract: the Kotlin frontend sets both, and
    /// Plumb's DFA engine queries the sink catalogue for a NEW only when
    /// <c>calleeSig</c> is present -- see
    /// <c>TaintedCheckerBase.checkSinkAtNew</c>, which returns immediately
    /// when the signature is null.
    /// </para>
    /// <para>
    /// So a C# frontend that emits only <c>ctorType</c> makes EVERY
    /// constructor sink structurally unreachable. The scan still reports
    /// success; the findings are simply absent. That is not a precision
    /// trade-off, it is missing coverage, and it is invisible unless
    /// something measures it.
    /// </para>
    /// <para>
    /// The name is <c>&lt;init&gt;</c> -- the same name every other Plumb
    /// frontend uses for a constructor, and the name the sink catalogue
    /// matches. The arity is the number of CONSTRUCTOR ARGUMENTS: the
    /// receiver is not an argument of a constructor, so there is no receiver
    /// offset here.
    /// </para>
    /// </remarks>
    private string? ConstructorSignature(ObjectCreationExpressionSyntax creation)
    {
        // The owner comes from the CONSTRUCTOR symbol when there is one: the
        // constructor's declaring type is the type the catalogue names.
        var symbol = _model.GetSymbolInfo(creation).Symbol;
        if (symbol is IMethodSymbol constructor)
        {
            string owner = TypeMapper.FullName(constructor.ContainingType);
            return $"{owner}.<init>/{constructor.Parameters.Length}";
        }

        // No constructor symbol: the type is ABSTRACT (Roslyn cannot
        // construct an abstract type, so `new AbstractType()` does not
        // compile and there is no constructor symbol to read) or the
        // expression is a type the compiler could not bind.
        //
        // The owner is still recoverable from the TYPE, and the arity from
        // the argument list. Losing the callee signature here would make
        // every constructor sink on an abstract type unreachable -- and an
        // abstract type is exactly where the framework's sink APIs live
        // (`DbCommand`, `Stream`, `XmlReader`). So the type is the fallback,
        // not the exception.
        var typeInfo = _model.GetTypeInfo(creation).Type;
        if (typeInfo is null)
        {
            return null;
        }
        string ownerName = TypeMapper.FullName(typeInfo);
        if (string.IsNullOrEmpty(ownerName))
        {
            return null;
        }
        int arity = creation.ArgumentList is null ? 0 : creation.ArgumentList.Arguments.Count;
        return $"{ownerName}.<init>/{arity}";
    }

    /// <summary>
    /// Lower a binary expression.
    /// </summary>
    /// <remarks>
    /// <para>
    /// The COALESCE operator (<c>a ?? b</c>) is not a binary operation: it
    /// picks one side or the other. It used to be mapped to ADD, which is
    /// how a null-coalescing assignment came to be an addition -- and the
    /// analysis was confident about it.
    /// </para>
    /// <para>
    /// The <c>as</c> operator is a CONVERSION that yields null when it
    /// fails. It is a CAST, and it does not throw.
    /// </para>
    /// </remarks>
    private Operand? TranslateBinary(BinaryExpressionSyntax binary, bool store)
    {
        if (binary.IsKind(SyntaxKind.CoalesceExpression))
        {
            return TranslateCoalesce(binary);
        }

        if (binary.IsKind(SyntaxKind.AsExpression))
        {
            // `a as T`: a conversion that yields null rather than throwing.
            var source = TranslateExpression(binary.Left, store: false);
            var targetType = TypeMapper.Map(_model.GetTypeInfo(binary.Right).Type);
            var castTemp = NewTemp(targetType);
            Emit(InstructionKind.UNARYOP, dest: castTemp, lhs1: source, unOp: "CAST",
                castType: targetType, node: binary);
            return castTemp;
        }

        var left = TranslateExpression(binary.Left, store: false);
        var right = TranslateExpression(binary.Right, store: false);
        var binaryTemp = NewTemp();
        if (left is not null && right is not null)
        {
            Emit(InstructionKind.BINOP, dest: binaryTemp, lhs1: left, lhs2: right,
                binOp: BinaryOperatorOf(binary.Kind()), node: binary);
        }
        return binaryTemp;
    }

    /// <summary>
    /// Lower <c>a ?? b</c>: the left side when it is not null, the right side
    /// otherwise.
    /// </summary>
    /// <param name="binary">the coalescing expression</param>
    /// <returns>the operand holding the chosen value</returns>
    /// <remarks>
    /// It is a branch chain: the left side is tested, the two sides are
    /// evaluated on their own paths, and the result is merged. The merge is
    /// what carries the taint: the result is derived from whichever side was
    /// taken, and both sides are on the instruction stream.
    /// </remarks>
    private Operand? TranslateCoalesce(BinaryExpressionSyntax binary)
    {
        var left = TranslateExpression(binary.Left, store: false);
        var right = TranslateExpression(binary.Right, store: false);
        var result = NewTemp();
        var valueLabel = NewLabel("coalesce_value");
        var fallbackLabel = NewLabel("coalesce_fallback");
        var endLabel = NewLabel("coalesce_end");

        if (left is not null)
        {
            Emit(InstructionKind.BRANCH, lhs1: left, trueLabel: valueLabel,
                falseLabel: fallbackLabel, node: binary);
        }

        Emit(InstructionKind.LABEL, label: valueLabel, node: binary);
        if (left is not null)
        {
            Emit(InstructionKind.MOVE, dest: result, lhs1: left, node: binary);
        }
        Emit(InstructionKind.JUMP, label: endLabel, node: binary);

        Emit(InstructionKind.LABEL, label: fallbackLabel, node: binary);
        if (right is not null)
        {
            Emit(InstructionKind.MOVE, dest: result, lhs1: right, node: binary);
        }

        Emit(InstructionKind.LABEL, label: endLabel, node: binary);
        return result;
    }

    private Operand? TranslatePrefixUnary(PrefixUnaryExpressionSyntax prefix, bool store)
    {
        if (prefix.IsKind(SyntaxKind.PreIncrementExpression)
            || prefix.IsKind(SyntaxKind.PreDecrementExpression))
        {
            var current = TranslateExpression(prefix.Operand, store: false);
            if (current is not null)
            {
                var one = new Operand { Kind = "CONST", ConstKind = "INT", Value = "1" };
                var incrementTemp = NewTemp();
                Emit(InstructionKind.BINOP, dest: incrementTemp, lhs1: current, lhs2: one,
                    binOp: prefix.IsKind(SyntaxKind.PreIncrementExpression) ? "ADD" : "SUB",
                    node: prefix);
                Emit(InstructionKind.MOVE, dest: current, lhs1: incrementTemp, node: prefix);
            }
            return current;
        }

        var operand = TranslateExpression(prefix.Operand, store: false);
        var unaryTemp = NewTemp();
        Emit(InstructionKind.UNARYOP, dest: unaryTemp, lhs1: operand,
            unOp: UnaryOperatorOf(prefix.Kind()), node: prefix);
        return unaryTemp;
    }

    private Operand? TranslatePostfixUnary(PostfixUnaryExpressionSyntax postfix, bool store)
    {
        // The null-forgiving operator: `x!`. See TranslateSuppressNullableWarning
        // for why it is NOT a NULL_CHECK.
        if (postfix.IsKind(SyntaxKind.SuppressNullableWarningExpression))
        {
            return TranslateSuppressNullableWarning(postfix);
        }

        var current = TranslateExpression(postfix.Operand, store: false);
        if (current is null)
        {
            return null;
        }
        if (postfix.IsKind(SyntaxKind.PostIncrementExpression)
            || postfix.IsKind(SyntaxKind.PostDecrementExpression))
        {
            var one = new Operand { Kind = "CONST", ConstKind = "INT", Value = "1" };
            var incrementTemp = NewTemp();
            Emit(InstructionKind.BINOP, dest: incrementTemp, lhs1: current, lhs2: one,
                binOp: postfix.IsKind(SyntaxKind.PostIncrementExpression) ? "ADD" : "SUB",
                node: postfix);
            Emit(InstructionKind.MOVE, dest: current, lhs1: incrementTemp, node: postfix);
        }
        return current;
    }

    private static bool IsCompoundAssignment(SyntaxKind kind) => kind switch
    {
        SyntaxKind.AddAssignmentExpression => true,
        SyntaxKind.SubtractAssignmentExpression => true,
        SyntaxKind.MultiplyAssignmentExpression => true,
        SyntaxKind.DivideAssignmentExpression => true,
        SyntaxKind.ModuloAssignmentExpression => true,
        SyntaxKind.AndAssignmentExpression => true,
        SyntaxKind.OrAssignmentExpression => true,
        SyntaxKind.ExclusiveOrAssignmentExpression => true,
        SyntaxKind.LeftShiftAssignmentExpression => true,
        SyntaxKind.RightShiftAssignmentExpression => true,
        SyntaxKind.UnsignedRightShiftAssignmentExpression => true,
        SyntaxKind.CoalesceAssignmentExpression => true,
        _ => false,
    };

    /// <summary>
    /// The binary operator, in Plumb's own {@code BinaryOperator} names.
    /// </summary>
    /// <remarks>
    /// <para>
    /// The names are the IR's, not C#'s: the sidecar speaks the IR's operator
    /// vocabulary directly, so the Java side does not need a translation table
    /// that could drift out of step with the enum.
    /// </para>
    /// <para>
    /// The COMPOUND ASSIGNMENT kinds are here too. They used to be missing,
    /// and the catch-all said ADD: so <c>x *= y</c> was analysed as
    /// <c>x + y</c>, and the analysis was confident about it.
    /// </para>
    /// </remarks>
    private static string BinaryOperatorOf(SyntaxKind kind) => kind switch
    {
        SyntaxKind.AddExpression => "ADD",
        SyntaxKind.SubtractExpression => "SUB",
        SyntaxKind.MultiplyExpression => "MUL",
        SyntaxKind.DivideExpression => "DIV",
        SyntaxKind.ModuloExpression => "MOD",
        SyntaxKind.BitwiseAndExpression => "AND",
        SyntaxKind.BitwiseOrExpression => "OR",
        SyntaxKind.ExclusiveOrExpression => "XOR",
        SyntaxKind.LeftShiftExpression => "SHL",
        SyntaxKind.RightShiftExpression => "SHR",
        SyntaxKind.UnsignedRightShiftExpression => "USHR",
        SyntaxKind.EqualsExpression => "EQ",
        SyntaxKind.NotEqualsExpression => "NE",
        SyntaxKind.LessThanExpression => "LT",
        SyntaxKind.LessThanOrEqualExpression => "LE",
        SyntaxKind.GreaterThanExpression => "GT",
        SyntaxKind.GreaterThanOrEqualExpression => "GE",
        SyntaxKind.LogicalAndExpression => "LAND",
        SyntaxKind.LogicalOrExpression => "LOR",

        SyntaxKind.AddAssignmentExpression => "ADD",
        SyntaxKind.SubtractAssignmentExpression => "SUB",
        SyntaxKind.MultiplyAssignmentExpression => "MUL",
        SyntaxKind.DivideAssignmentExpression => "DIV",
        SyntaxKind.ModuloAssignmentExpression => "MOD",
        SyntaxKind.AndAssignmentExpression => "AND",
        SyntaxKind.OrAssignmentExpression => "OR",
        SyntaxKind.ExclusiveOrAssignmentExpression => "XOR",
        SyntaxKind.LeftShiftAssignmentExpression => "SHL",
        SyntaxKind.RightShiftAssignmentExpression => "SHR",
        SyntaxKind.UnsignedRightShiftAssignmentExpression => "USHR",

        _ => "ADD",
    };

    /// <summary>The unary operator, in Plumb's own {@code UnaryOperator} names.</summary>
    private static string UnaryOperatorOf(SyntaxKind kind) => kind switch
    {
        SyntaxKind.UnaryMinusExpression => "NEG",
        SyntaxKind.LogicalNotExpression => "NOT",
        SyntaxKind.BitwiseNotExpression => "BITNOT",
        _ => "CAST",
    };

    /// <summary>The operand for a symbol, creating it on first sight.</summary>
    private Operand OperandFor(ISymbol symbol)
    {
        if (_operands.TryGetValue(symbol, out var existing))
        {
            return existing;
        }
        var operand = new Operand
        {
            Kind = "VAR",
            Name = symbol.Name,
            Type = TypeMapper.Map(TypeOfVariable(symbol)),
            IsTemp = false,
        };
        _operands[symbol] = operand;
        return operand;
    }

    /// <summary>
    /// The constant that fills the receiver slot of a STATIC call.
    /// </summary>
    /// <param name="type">the type that declares the member</param>
    /// <returns>
    /// a string constant naming the type, fully qualified
    /// </returns>
    /// <remarks>
    /// <para>
    /// A static member has no receiver object, but the TAC's call convention
    /// still reserves args[0] for the receiver slot, and the engine reads
    /// every argument index against that convention. The slot therefore
    /// carries the DECLARING TYPE, which is the closest thing to a receiver a
    /// static call has -- and it is a constant, because a constant is exactly
    /// what cannot carry taint.
    /// </para>
    /// <para>
    /// The text is the fully qualified type name, so the engine's
    /// "is this a static callee?" test recognises it: that test accepts a
    /// string constant whose last dot-separated segment starts with an
    /// uppercase letter, which a type name does and a package path does not.
    /// </para>
    /// </remarks>
    private static Operand TypePlaceholder(INamedTypeSymbol type)
    {
        return new Operand
        {
            Kind = "CONST",
            ConstKind = "STRING",
            Value = TypeMapper.FullName(type),
        };
    }

    /// <summary>The constant value of a field whose value the compiler knows.</summary>
    /// <param name="member">the enum member or the const field</param>
    /// <returns>
    /// a constant holding the member's value, in the enum's own underlying type
    /// </returns>
    /// <remarks>
    /// <para>
    /// An enum member is a compile-time constant: the C# compiler folds it to
    /// a literal. So the TAC can hold it as a literal too, and the value that
    /// reaches the sink is exactly the value the program would use.
    /// </para>
    /// <para>
    /// The alternative -- reading it as a field off its declaring type -- is
    /// what produced a HAVOC for every enum member in a nested enum, because
    /// the qualifier of such a member is a TYPE and not an object.
    /// </para>
    /// </remarks>
    private static Operand ConstantFieldValue(IFieldSymbol member)
    {
        var underlying = member.ContainingType.TypeKind == TypeKind.Enum
            ? member.ContainingType.EnumUnderlyingType?.SpecialType
            : member.Type?.SpecialType;
        var value = member.ConstantValue;
        var text = value switch
        {
            bool flag => flag ? "1" : "0",
            sbyte number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            byte number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            short number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ushort number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            int number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            uint number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            long number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ulong number => number.ToString(System.Globalization.CultureInfo.InvariantCulture),
            _ => "0",
        };
        var kind = underlying == SpecialType.System_Int64
            || underlying == SpecialType.System_UInt64
            ? "LONG"
            : "INT";
        return new Operand { Kind = "CONST", ConstKind = kind, Value = text };
    }

    /// <summary>The operand for a local or a parameter, registering it as a local.</summary>
    private Operand VariableOperand(ISymbol symbol, bool isTemp)
    {
        if (_operands.TryGetValue(symbol, out var existing))
        {
            return existing;
        }
        var operand = new Operand
        {
            Kind = "VAR",
            Name = symbol.Name,
            Type = TypeMapper.Map(TypeOfVariable(symbol)),
            IsTemp = isTemp,
        };
        _operands[symbol] = operand;

        // The binding records the source name and the slot it landed in. The
        // slot name is not the source name when the name was shadowed, and a
        // reader that wants to follow a source name to the value the engine
        // reasons about needs the pair.
        _scopeBindings.Add(new ScopeBinding
        {
            Name = symbol.Name,
            Slot = operand.Name ?? symbol.Name,
            Type = operand.Type,
            Parameter = symbol is IParameterSymbol,
        });
        return operand;
    }

    /// <summary>The declared type of a variable-like symbol, or null.</summary>
    /// <param name="symbol">the symbol</param>
    /// <returns>the type, or null when the symbol is not a local or a parameter</returns>
    private static ITypeSymbol? TypeOfVariable(ISymbol symbol) => symbol switch
    {
        ILocalSymbol local => local.Type,
        IParameterSymbol parameter => parameter.Type,
        _ => null,
    };

    private Operand NewTemp()
    {
        return NewTemp(TypeRef.Simple("UNKNOWN"));
    }

    /// <summary>
    /// A fresh temporary variable, with a type.
    /// </summary>
    /// <param name="type">the variable's type</param>
    /// <returns>the temporary</returns>
    private Operand NewTemp(TypeRef type)
    {
        _counter++;
        return new Operand
        {
            Kind = "VAR",
            Name = $"t_t{_counter}",
            Type = type,
            IsTemp = true,
        };
    }

    private string NewLabel(string prefix)
    {
        string label;
        do
        {
            _counter++;
            label = $"{prefix}_{_counter}";
        }
        while (!_labels.Add(label));
        return label;
    }

    /// <summary>
    /// Record a problem, and the drop that goes with it.
    /// </summary>
    /// <param name="node">the node that caused it</param>
    /// <param name="kind">the problem kind</param>
    /// <param name="message">the human-readable description</param>
    /// <param name="nodeClass">the simple name of the node's type</param>
    /// <remarks>
    /// <para>
    /// It records a DROP as well, so that a loss can never be silent: the
    /// drop carries the impact level the engine derives the function's
    /// precision from, and the problem carries the sentence a human reads.
    /// </para>
    /// <para>
    /// The construct id is the catch-all one. A site that knows WHICH
    /// construct it lost calls <see cref="Drop"/> with its own id, so that
    /// the id names the construct rather than the failure.
    /// </para>
    /// </remarks>
    private void Report(SyntaxNode node, string kind, string message, string nodeClass)
    {
        var lineSpan = node.GetLocation().GetLineSpan();
        _problems.Add(new Problem
        {
            Kind = kind,
            Message = message,
            NodeClass = nodeClass,
            Line = lineSpan.StartLinePosition.Line + 1,
            Column = lineSpan.StartLinePosition.Character + 1,
        });
        _degraded = true;

        // The impact follows from what the problem means. A construct that
        // was not lowered at all is a loss of MEANING: the analysis does not
        // know what it does. A construct that was lowered into a different
        // shape is a loss of SHAPE: it still computes the same thing.
        var impact = kind == "SOURCE_LOWERED" ? DropImpact.Structure : DropImpact.Semantics;
        Drop(node, DropIds.Unmodelled, message, impact);
    }

    /// <summary>
    /// Record a loss: a construct that was not lowered faithfully.
    /// </summary>
    /// <param name="node">the node the construct is at</param>
    /// <param name="construct">the stable machine-readable id of the construct</param>
    /// <param name="detail">the human-readable description of what was lost</param>
    /// <param name="impact">SEMANTICS, STRUCTURE, VALUE or TYPE</param>
    /// <remarks>
    /// <para>
    /// WHY IT IS SEPARATE FROM Report(). A problem says the frontend could
    /// not do its job. A drop says the frontend DID its job and this is what
    /// the job cost, with the impact level that decides the function's
    /// precision. The difference matters: a construct that was lowered into
    /// the wrong shape -- a switch into a branch chain -- still computes the
    /// right thing, and calling that "a problem" would hide the fact that
    /// the analysis understands it.
    /// </para>
    /// <para>
    /// The construct id names the CONSTRUCT, not the failure, and it carries
    /// no variable part: a coverage report groups by it, and an id that
    /// carried a name or a line number would split one construct into as
    /// many groups as it has occurrences.
    /// </para>
    /// </remarks>
    private void Drop(SyntaxNode? node, string construct, string detail, string impact)
    {
        var drop = new Drop
        {
            Construct = construct,
            Detail = detail,
            Impact = impact,
            Line = -1,
            Column = -1,
            Scope = DropScope.Function,
        };
        if (node is not null)
        {
            var lineSpan = node.GetLocation().GetLineSpan();
            drop.Line = lineSpan.StartLinePosition.Line + 1;
            drop.Column = lineSpan.StartLinePosition.Character + 1;
        }
        _drops.Add(drop);

        // Only a loss of meaning or of shape costs the function its
        // precision. A single unknown value, or an unknown type fact, does
        // not: the function still computes the same thing.
        if (impact == DropImpact.Semantics || impact == DropImpact.Structure)
        {
            _degraded = true;
        }
    }

    private void Emit(
        InstructionKind kind,
        Operand? dest = null,
        Operand? lhs1 = null,
        Operand? lhs2 = null,
        string? binOp = null,
        string? unOp = null,
        TypeRef? castType = null,
        string? calleeSig = null,
        List<Operand>? args = null,
        TypeRef? ctorType = null,
        string? fieldName = null,
        string? trueLabel = null,
        string? falseLabel = null,
        string? label = null,
        string? effect = null,
        string? ownerType = null,
        List<Operand?>? resultDests = null,
        SyntaxNode? node = null)
    {
        var instruction = new Instruction
        {
            Kind = kind.ToString(),
            Dest = dest,
            Lhs1 = lhs1,
            Lhs2 = lhs2,
            BinOp = binOp,
            UnOp = unOp,
            CastType = castType,
            CalleeSig = calleeSig,
            Args = args is { Count: > 0 } ? args : null,
            CtorType = ctorType,
            FieldName = fieldName,
            TrueLabel = trueLabel,
            FalseLabel = falseLabel,
            Label = label,
            Effect = effect,
            OwnerType = ownerType,
            ResultDests = resultDests,
        };

        if (node is not null)
        {
            var lineSpan = node.GetLocation().GetLineSpan();
            instruction.Line = lineSpan.StartLinePosition.Line + 1;
            instruction.Column = lineSpan.StartLinePosition.Character + 1;
        }

        _instructions.Add(instruction);
    }
}

/// <summary>The TAC instruction kinds, by name.</summary>
internal enum InstructionKind
{
    MOVE,
    BINOP,
    UNARYOP,
    CALL,
    NEW,
    NEWARRAY,
    ARRAYLOAD,
    ARRAYSTORE,
    FIELDLOAD,
    FIELDSTORE,
    INSTANCEOF,
    BRANCH,
    JUMP,
    LABEL,
    RETURN,
    THROW,
    NOP,
    CATCH_HANDLER,
    FINALLY_ENTER,
    NULL_CHECK,
    HAVOC,
    UNINTERPRETED,
}
