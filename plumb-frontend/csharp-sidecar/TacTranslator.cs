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
                TranslateExpression(lockStatement.Expression, store: false);
                TranslateStatement(lockStatement.Statement);
                return;

            default:
                Report(statement, "UNHANDLED_NODE",
                    $"the statement {statement.Kind()} was not lowered", statement.GetType().Name);
                return;
        }
    }

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

    private void TranslateTry(TryStatementSyntax tryStatement)
    {
        // The try body is lowered inline, and each catch handler gets its own
        // entry label. The CATCH_HANDLER instruction carries the caught type, so
        // the engine can decide which handler a propagating exception reaches.
        TranslateBlock(tryStatement.Block);

        foreach (var clause in tryStatement.Catches)
        {
            var label = NewLabel("catch");
            Emit(InstructionKind.LABEL, label: label, node: clause);

            var caughtType = clause.Declaration is null
                ? null
                : _model.GetTypeInfo(clause.Declaration.Type).Type;
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
        }

        if (tryStatement.Finally is not null)
        {
            var finallyLabel = NewLabel("finally");
            Emit(InstructionKind.FINALLY_ENTER, label: finallyLabel, node: tryStatement.Finally);
            Emit(InstructionKind.LABEL, label: finallyLabel, node: tryStatement.Finally);
            TranslateBlock(tryStatement.Finally.Block);
        }
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

    private void TranslateUsing(UsingStatementSyntax usingStatement)
    {
        if (usingStatement.Declaration is not null)
        {
            TranslateVariableDeclaration(usingStatement.Declaration);
        }
        if (usingStatement.Expression is not null)
        {
            TranslateExpression(usingStatement.Expression, store: false);
        }
        TranslateStatement(usingStatement.Statement);
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
                // `await` is an async/await state machine. The frontend cannot
                // lower that state machine into the TAC's ordinary control flow
                // without changing what the code means, so the awaited value is
                // taken as-is and the function's precision is downgraded. The
                // TAINT is what matters for the analysis, and it survives: the
                // awaited expression is still lowered, so a tainted value that is
                // awaited stays tainted.
                _degraded = true;
                return TranslateExpression(awaitExpression.Expression, store: false);
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
                var value = TranslateExpression(isPattern.Expression, store: false);
                var type = TypeMapper.Map(_model.GetTypeInfo(isPattern.Pattern).Type);
                var patternTemp = NewTemp();
                Emit(InstructionKind.INSTANCEOF, dest: patternTemp, lhs1: value, ctorType: type,
                    node: isPattern);
                return patternTemp;
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

            case DefaultExpressionSyntax:
                return new Operand { Kind = "CONST", ConstKind = "NULL" };

            case TypeOfExpressionSyntax typeOf:
            {
                var type = TypeMapper.Map(_model.GetTypeInfo(typeOf.Type).Type);
                var typeTemp = NewTemp();
                Emit(InstructionKind.NEW, dest: typeTemp, ctorType: type, node: typeOf);
                return typeTemp;
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

    private Operand? TranslateBinary(BinaryExpressionSyntax binary, bool store)
    {
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
    /// The names are the IR's, not C#'s: the sidecar speaks the IR's operator
    /// vocabulary directly, so the Java side does not need a translation table
    /// that could drift out of step with the enum.
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
        SyntaxKind.CoalesceExpression => "ADD",
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
