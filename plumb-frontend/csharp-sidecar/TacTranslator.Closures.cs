using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Text;

namespace Plumb.CSharpSidecar;

/// <summary>
/// A closure: a lambda, and the variables it captures.
/// </summary>
/// <remarks>
/// <para>
/// <b>Why a closure is a SEPARATE function.</b> The TAC has no nested function and
/// no function value, so a lambda cannot be a value in an instruction stream.
/// The lambda's BODY therefore becomes a function of its own, and the call
/// sites call it by name.
/// </para>
/// <para>
/// <b>Why the captured variables are PARAMETERS.</b> A C# lambda reads an
/// enclosing variable as it is when the lambda RUNS. The TAC has no
/// reference, so the value is passed at the call site instead. That is what
/// makes the taint of an outer variable reach the lambda's body -- which is
/// the whole point of a closure to a taint analysis: a handler that reads a
/// secret the enclosing method holds.
/// </para>
/// </remarks>
internal sealed class Closure
{
    /// <summary>The lambda's syntax node.</summary>
    public AnonymousFunctionExpressionSyntax Literal { get; init; } = null!;

    /// <summary>
    /// The captured variables, in the order they are passed.
    /// </summary>
    /// <remarks>
    /// The enclosing type's symbol stands for a captured <c>this</c>: a lambda
    /// that mentions <c>this</c> holds the instance, and the instance is the
    /// first thing it needs.
    /// </remarks>
    public List<ISymbol> Captured { get; init; } = new();

    /// <summary>The callee key the call sites use.</summary>
    public string CalleeSig { get; init; } = "";

    /// <summary>The operand that holds the closure's value.</summary>
    public Operand Value { get; init; } = null!;
}

/// <summary>
/// The counter that names the closures of one project.
/// </summary>
/// <remarks>
/// <para>
/// <b>Why the counter is project-wide and not per method.</b> The TAC indexes
/// a function by its ENCLOSING SCOPE and its SIGNATURE, and it also matches a
/// call to a callee by the BARE key -- the method name and the arity, with
/// the owner dropped. Two closures named <c>lambda1</c> in two methods of the
/// same class would share that bare key, the lookup would find two candidates
/// and refuse to pick one, and the closure's body would be unreachable.
/// </para>
/// <para>
/// A counter that never repeats inside the project removes the ambiguity
/// entirely: the bare key names exactly one closure.
/// </para>
/// </remarks>
internal sealed class ClosureCounter
{
    private int _next;

    /// <summary>The next number. Unique within the project.</summary>
    public int Next() => ++_next;
}

/// <summary>
/// The lowering of lambdas: a lambda becomes a function of its own, and the
/// captured variables become its leading parameters.
/// </summary>
internal sealed partial class TacTranslator
{
    /// <summary>The closure a literal became, by the literal's syntax.</summary>
    private readonly Dictionary<AnonymousFunctionExpressionSyntax, Closure> _closureByLiteral = new();

    /// <summary>
    /// The closure a variable holds, by the variable's symbol.
    /// </summary>
    /// <remarks>
    /// It is what makes a call through a closure variable reach the closure's
    /// body: without it the call would be unresolvable, and the closure's
    /// whole body would be invisible to the analysis.
    /// </remarks>
    private readonly Dictionary<ISymbol, Closure> _closureByVar = new();

    /// <summary>The functions the closures produced.</summary>
    private readonly List<Function> _closureFunctions = new();

    /// <summary>The counter that names the closures of the project.</summary>
    private readonly ClosureCounter _closureCounter;

    /// <summary>
    /// The functions the closures of this translator produced.
    /// </summary>
    /// <remarks>
    /// A closure is a function of its own, and it has to reach the store like
    /// any other, or the body it holds would be invisible to the analysis.
    /// </remarks>
    public IReadOnlyList<Function> ClosureFunctions => _closureFunctions;

    /// <summary>
    /// Lower a lambda: <c>x =&gt; body</c> or <c>delegate (...) { ... }</c>.
    /// </summary>
    /// <param name="lambda">the lambda</param>
    /// <returns>
    /// the operand holding the closure's value, or null when the lambda could
    /// not be lowered
    /// </returns>
    /// <remarks>
    /// <para>
    /// A literal is translated ONCE. The call that names it resolves the
    /// literal BEFORE the literal is evaluated, so the translation has to be
    /// idempotent: otherwise the call would create one closure and the
    /// expression another, and the call would reach a body the store never
    /// saw.
    /// </para>
    /// </remarks>
    private Operand? TranslateLambda(AnonymousFunctionExpressionSyntax lambda)
    {
        if (_closureByLiteral.TryGetValue(lambda, out var existing))
        {
            return existing.Value;
        }

        var method = _model.GetSymbolInfo(lambda).Symbol as IMethodSymbol;
        if (method is null)
        {
            Report(lambda, "UNHANDLED_NODE",
                "the lambda's signature could not be resolved", lambda.GetType().Name);
            return null;
        }

        var owner = OwnerType();
        if (owner is null)
        {
            Report(lambda, "UNHANDLED_NODE",
                "the lambda's enclosing type could not be resolved", lambda.GetType().Name);
            return null;
        }

        var captured = CapturedVariables(lambda, owner);

        var sub = new TacTranslator(_model, _closureCounter)
        {
            _ownerType = owner,
            _memberName = _memberName,
        };

        // The captured variables come FIRST: they are the closure's leading
        // parameters, and the call sites pass them in the same order. The
        // engine binds a callee's formal i to the call site's args[i+1], so
        // the layout [receiver, captured..., own...] puts every formal on the
        // argument it belongs to.
        foreach (var symbol in captured)
        {
            sub._params.Add(sub.VariableOperand(symbol, isTemp: false));
        }
        foreach (var parameter in method.Parameters)
        {
            sub._params.Add(sub.VariableOperand(parameter, isTemp: false));
        }

        switch (lambda.Body)
        {
            case BlockSyntax block:
                sub.TranslateBlock(block);
                break;

            case ExpressionSyntax expression:
            {
                // An EXPRESSION body is a block whose last statement returns
                // the expression's value.
                var value = sub.TranslateExpression(expression, store: false);
                if (value is not null)
                {
                    sub.Emit(InstructionKind.RETURN, lhs1: value, node: expression);
                }
                break;
            }

            default:
                sub.Report(lambda, "UNHANDLED_NODE",
                    "the lambda's body was not lowered", lambda.Body.GetType().Name);
                break;
        }

        var name = ClosureName(_closureCounter.Next());
        var arity = captured.Count + method.Parameters.Length;
        var calleeSig = $"{TypeMapper.FullName(owner)}.{name}/{arity}";

        var record = new Closure
        {
            Literal = lambda,
            Captured = captured,
            CalleeSig = calleeSig,
            Value = NewTemp(TypeMapper.Map(_model.GetTypeInfo(lambda).Type)),
        };
        _closureByLiteral[lambda] = record;

        _closureFunctions.Add(BuildClosureFunction(sub, name, owner, method, captured));
        return record.Value;
    }

    /// <summary>
    /// The variables a lambda captures.
    /// </summary>
    /// <param name="lambda">the lambda</param>
    /// <param name="owner">the type the lambda is written in</param>
    /// <returns>the captured symbols, in a stable order</returns>
    /// <remarks>
    /// <para>
    /// A local or a parameter of the ENCLOSING member is captured. A variable
    /// declared INSIDE the lambda is not: it is the lambda's own parameter or
    /// its own local, and the lambda's body already names it.
    /// </para>
    /// <para>
    /// A FIELD is not captured either. A field belongs to the object, not to
    /// the scope, and the object is reached through <c>this</c> -- which is
    /// captured on its own when the body mentions it.
    /// </para>
    /// </remarks>
    private List<ISymbol> CapturedVariables(
        AnonymousFunctionExpressionSyntax lambda,
        INamedTypeSymbol owner)
    {
        var captured = new List<ISymbol>();
        var seen = new HashSet<ISymbol>();

        // `this` is captured when the body mentions it: the lambda holds the
        // instance, and every field the body reads is reached through it.
        if (MentionsThis(lambda))
        {
            captured.Add(owner);
            seen.Add(owner);
        }

        var span = lambda.Span;
        foreach (var node in lambda.DescendantNodes())
        {
            if (node is not IdentifierNameSyntax identifier)
            {
                continue;
            }
            var symbol = _model.GetSymbolInfo(identifier).Symbol;
            if (symbol is not (ILocalSymbol or IParameterSymbol))
            {
                continue;
            }
            if (DeclaredWithin(symbol, span))
            {
                continue;
            }
            if (!seen.Add(symbol))
            {
                continue;
            }
            captured.Add(symbol);
        }

        return captured;
    }

    /// <summary>Whether a lambda's body mentions <c>this</c>.</summary>
    private static bool MentionsThis(AnonymousFunctionExpressionSyntax lambda)
    {
        foreach (var node in lambda.DescendantNodes())
        {
            if (node is ThisExpressionSyntax)
            {
                return true;
            }
        }
        return false;
    }

    /// <summary>
    /// Whether a symbol is DECLARED inside a span.
    /// </summary>
    /// <remarks>
    /// The declaring SYNTAX decides it, not the symbol's kind: the lambda's
    /// own parameters are declared inside the lambda, and so is every local
    /// the lambda declares.
    /// </remarks>
    private static bool DeclaredWithin(ISymbol symbol, TextSpan span)
    {
        foreach (var reference in symbol.DeclaringSyntaxReferences)
        {
            var declaration = reference.GetSyntax().Span;
            if (declaration.Start >= span.Start && declaration.End <= span.End)
            {
                return true;
            }
        }
        return false;
    }

    /// <summary>
    /// The operands of a closure's captured variables, in the order the
    /// closure expects them.
    /// </summary>
    /// <remarks>
    /// The values are read at the CALL SITE, not where the lambda was
    /// written: a closure sees an enclosing variable as it is when it runs.
    /// </remarks>
    private List<Operand> CapturedOperands(Closure closure)
    {
        var operands = new List<Operand>();
        foreach (var symbol in closure.Captured)
        {
            operands.Add(OperandFor(symbol));
        }
        return operands;
    }

    /// <summary>
    /// The name of a closure.
    /// </summary>
    /// <param name="index">the project-wide number</param>
    /// <returns>the name</returns>
    /// <remarks>
    /// The name carries the enclosing member's name and the number. The
    /// NUMBER is what makes it unique: the TAC indexes a function by its
    /// enclosing scope and its signature, and it matches a call to a callee
    /// by the bare key -- the name and the arity, with the owner dropped.
    /// Two closures with the same name in one project would share that bare
    /// key, and the lookup would refuse to choose between them.
    /// </remarks>
    private string ClosureName(int index)
    {
        string member = SanitizeIdentifier(_memberName);
        if (member.Length == 0)
        {
            member = "closure";
        }
        return $"{member}.lambda{index}";
    }

    /// <summary>
    /// A name with only the characters an identifier may carry.
    /// </summary>
    /// <remarks>
    /// The compiler's own names carry characters that are not letters -- the
    /// synthesized entry point is <c>&lt;Main&gt;$</c> -- and the name ends up
    /// in a callee key that a report prints.
    /// </remarks>
    private static string SanitizeIdentifier(string name)
    {
        if (string.IsNullOrEmpty(name))
        {
            return "";
        }
        var characters = new char[name.Length];
        for (int i = 0; i < name.Length; i++)
        {
            char c = name[i];
            characters[i] = char.IsLetterOrDigit(c) || c == '_' ? c : '_';
        }
        return new string(characters);
    }

    /// <summary>
    /// Assemble the wire model of a closure.
    /// </summary>
    /// <remarks>
    /// The parameters are the CAPTURED variables followed by the lambda's own:
    /// that is the order the call sites pass them in, and the arity in the
    /// callee key counts them all.
    /// </remarks>
    private static Function BuildClosureFunction(
        TacTranslator sub,
        string name,
        INamedTypeSymbol owner,
        IMethodSymbol method,
        List<ISymbol> captured)
    {
        var parameters = new List<string>();
        foreach (var symbol in captured)
        {
            parameters.Add(TypeMapper.FullName(CaptureType(symbol, owner)));
        }
        foreach (var parameter in method.Parameters)
        {
            parameters.Add(TypeMapper.FullName(parameter.Type));
        }

        string ownerName = TypeMapper.FullName(owner);
        string returnType = TypeMapper.FullName(method.ReturnType);

        return new Function
        {
            Name = name,
            Signature = $"{ownerName}.{name}({string.Join(",", parameters)}):{returnType}",
            EnclosingClass = owner.Name,
            ReturnType = TypeMapper.Map(method.ReturnType),
            Precision = sub.Degraded ? "SOURCE_LOWERED" : "SOURCE_FULL",
            Synthetic = false,
            Params = sub._params.ToList(),
            Locals = sub._locals.ToList(),
            Instructions = sub._instructions.ToList(),
            Problems = sub._problems.ToList(),
        };
    }

    /// <summary>The declared type of a captured symbol.</summary>
    private static ITypeSymbol CaptureType(ISymbol symbol, INamedTypeSymbol owner) => symbol switch
    {
        ILocalSymbol local => local.Type,
        IParameterSymbol parameter => parameter.Type,
        // The captured `this`: the instance's own type.
        _ => owner,
    };

    /// <summary>
    /// Lower a call of a lambda, or of a variable that holds one.
    /// </summary>
    /// <param name="invocation">the invocation</param>
    /// <param name="result">the operand holding the call's value</param>
    /// <returns>true when the call was a closure's call</returns>
    /// <remarks>
    /// <para>
    /// The three shapes are: a lambda called directly, a call through a
    /// variable that holds a lambda, and the explicit <c>Invoke</c> of such a
    /// variable. Without them the call would name a delegate's
    /// <c>Invoke</c> method, whose body is in the framework, and the
    /// closure's own body would never be reached.
    /// </para>
    /// </remarks>
    private bool TryTranslateClosureCall(InvocationExpressionSyntax invocation, out Operand? result)
    {
        result = null;
        var callee = UnwrapParentheses(invocation.Expression);

        Closure? closure = null;
        if (callee is AnonymousFunctionExpressionSyntax literal)
        {
            // A literal called directly: `((int x) => F(x))(y)`.
            if (TranslateLambda(literal) is null)
            {
                return false;
            }
            closure = _closureByLiteral[literal];
        }
        else if (callee is IdentifierNameSyntax identifier)
        {
            var symbol = _model.GetSymbolInfo(identifier).Symbol;
            if (symbol is null)
            {
                return false;
            }
            if (!_closureByVar.TryGetValue(symbol, out closure))
            {
                return false;
            }
        }
        else if (callee is MemberAccessExpressionSyntax memberAccess)
        {
            // `handler.Invoke(x)`: the RECEIVER holds the closure.
            var method = _model.GetSymbolInfo(memberAccess).Symbol as IMethodSymbol;
            if (method is null || method.Name != "Invoke")
            {
                return false;
            }
            var receiverSymbol = _model.GetSymbolInfo(memberAccess.Expression).Symbol;
            if (receiverSymbol is null)
            {
                return false;
            }
            if (!_closureByVar.TryGetValue(receiverSymbol, out closure))
            {
                return false;
            }
        }
        else
        {
            return false;
        }

        result = EmitClosureCall(closure, invocation.ArgumentList.Arguments, invocation);
        return true;
    }

    /// <summary>
    /// Lower a call of a delegate through a NULL-CONDITIONAL access:
    /// <c>handler?.Invoke(x)</c>.
    /// </summary>
    /// <param name="conditionalAccess">the conditional access</param>
    /// <param name="result">the operand holding the call's value</param>
    /// <returns>true when the call was a closure's call</returns>
    /// <remarks>
    /// The null-conditional call is the SAME call as the plain one, guarded.
    /// The guard is the conditional access's own business, so the call itself
    /// is emitted the same way.
    /// </remarks>
    private bool TryTranslateConditionalClosureCall(
        ConditionalAccessExpressionSyntax conditionalAccess,
        out Operand? result)
    {
        result = null;
        if (conditionalAccess.WhenNotNull is not InvocationExpressionSyntax invocation
            || invocation.Expression is not MemberBindingExpressionSyntax binding)
        {
            return false;
        }
        var method = _model.GetSymbolInfo(binding).Symbol as IMethodSymbol;
        if (method is null || method.Name != "Invoke")
        {
            return false;
        }
        var receiverSymbol = _model.GetSymbolInfo(conditionalAccess.Expression).Symbol;
        if (receiverSymbol is null)
        {
            return false;
        }
        if (!_closureByVar.TryGetValue(receiverSymbol, out var closure))
        {
            return false;
        }
        result = EmitClosureCall(closure, invocation.ArgumentList.Arguments, invocation);
        return true;
    }

    /// <summary>
    /// Emit the call of a closure.
    /// </summary>
    /// <param name="closure">the closure</param>
    /// <param name="arguments">the arguments the source wrote</param>
    /// <param name="node">the node, for the source position</param>
    /// <returns>the operand holding the call's value</returns>
    /// <remarks>
    /// The receiver slot comes first, then the captured variables, then the
    /// arguments the source wrote. The engine binds the callee's formal i to
    /// args[i+1], so the captured variables land on the formals that were
    /// declared for them.
    /// </remarks>
    private Operand? EmitClosureCall(
        Closure closure,
        IEnumerable<ArgumentSyntax> arguments,
        SyntaxNode node)
    {
        var callArguments = new List<Operand> { closure.Value };
        callArguments.AddRange(CapturedOperands(closure));
        foreach (var argument in arguments)
        {
            var operand = TranslateExpression(argument.Expression, store: false);
            if (operand is null)
            {
                var unknown = NewTemp();
                Emit(InstructionKind.HAVOC, dest: unknown, node: argument);
                operand = unknown;
            }
            callArguments.Add(operand);
        }

        var result = NewTemp();
        Emit(InstructionKind.CALL, dest: result, calleeSig: closure.CalleeSig,
            args: callArguments, node: node);
        return result;
    }

    /// <summary>
    /// Remember that a variable holds a closure.
    /// </summary>
    /// <remarks>
    /// The registration is what turns <c>handler(x)</c> into a call of the
    /// closure's body. Without it the call names a delegate's
    /// <c>Invoke</c> method and the closure's own code is never reached.
    /// </remarks>
    private void RegisterClosureHolder(ISymbol symbol, ExpressionSyntax? initializer)
    {
        if (initializer is null)
        {
            return;
        }
        var literal = UnwrapParentheses(initializer) as AnonymousFunctionExpressionSyntax;
        if (literal is null)
        {
            return;
        }
        if (_closureByLiteral.TryGetValue(literal, out var closure))
        {
            _closureByVar[symbol] = closure;
        }
    }

    /// <summary>An expression with its parentheses removed.</summary>
    private static ExpressionSyntax UnwrapParentheses(ExpressionSyntax expression)
    {
        while (expression is ParenthesizedExpressionSyntax parenthesized)
        {
            expression = parenthesized.Expression;
        }
        return expression;
    }
}
