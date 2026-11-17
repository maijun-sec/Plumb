using System.Text.Json.Serialization;

namespace Plumb.CSharpSidecar;

/// <summary>
/// The impact of a drop: what the loss costs the function that carries it.
/// </summary>
/// <remarks>
/// <para>
/// The engine derives the function's precision from the drops, and the
/// precision is what says whether a finding may be trusted. So the impact
/// has to be exact, and it has to mean one thing.
/// </para>
/// <para>
/// <b>SEMANTICS</b> — the analysis does not know what the construct DOES.
/// The function's precision becomes SOURCE_DEGRADED.
/// </para>
/// <para>
/// <b>STRUCTURE</b> — the construct's shape was lowered away, but it still
/// computes the same thing. The function's precision becomes SOURCE_LOWERED.
/// </para>
/// <para>
/// <b>VALUE</b> — exactly ONE value became unknown. The function keeps its
/// precision.
/// </para>
/// <para>
/// <b>TYPE</b> — a type-level fact is unknown. The function keeps its
/// precision.
/// </para>
/// </remarks>
public static class DropImpact
{
    /// <summary>The analysis does not know what the construct does.</summary>
    public const string Semantics = "SEMANTICS";

    /// <summary>The shape was lowered away; the meaning was not.</summary>
    public const string Structure = "STRUCTURE";

    /// <summary>Exactly one value became unknown.</summary>
    public const string Value = "VALUE";

    /// <summary>A type-level fact is unknown.</summary>
    public const string Type = "TYPE";
}

/// <summary>The scope a drop belongs to.</summary>
public static class DropScope
{
    /// <summary>The loss belongs to one function.</summary>
    public const string Function = "FUNCTION";

    /// <summary>The loss belongs to the whole program.</summary>
    public const string Program = "PROGRAM";
}

/// <summary>
/// The C# construct ids.
/// </summary>
/// <remarks>
/// <para>
/// They are stable: a coverage report groups by them, so renaming one
/// silently splits a coverage series. The prefix is the language, because
/// the id vocabulary is shared across sidecars and a C# loss must not be
/// counted as a Go one.
/// </para>
/// <para>
/// The ids name the CONSTRUCT, never the variable part of it: no line
/// numbers, no names, no counts.
/// </para>
/// </remarks>
public static class DropIds
{
    /// <summary>A construct the translator has no lowering for at all.</summary>
    public const string Unmodelled = "csharp.unmodelled";

    /// <summary>The async state machine was flattened rather than modelled.</summary>
    public const string AsyncStateMachine = "csharp.async.stateMachine";

    /// <summary>An await suspends and resumes at a later point.</summary>
    public const string AwaitSuspend = "csharp.await.suspend";

    /// <summary>The iterator's state machine was flattened rather than modelled.</summary>
    public const string IteratorStateMachine = "csharp.iterator.stateMachine";

    /// <summary>A dynamic member is bound at run time.</summary>
    public const string DynamicBinding = "csharp.dynamic.binding";

    /// <summary>The decimal's fixed-point rounding is not exact.</summary>
    public const string DecimalRounding = "csharp.decimal.rounding";

    /// <summary>A checked block's overflow check is not modelled.</summary>
    public const string CheckedOverflow = "csharp.checked.overflow";

    /// <summary>An attribute's compiled-in constant value is not modelled.</summary>
    public const string AttributeCompiledValue = "csharp.attribute.compiledValue";

    /// <summary>An override outside the compilation unit may exist.</summary>
    public const string VirtualOpenHierarchy = "csharp.virtual.openHierarchy";

    /// <summary>A pattern match does not cover every case.</summary>
    public const string PatternNotExhaustive = "csharp.pattern.notExhaustive";

    /// <summary>Which thread enters a critical section first is a run-time fact.</summary>
    public const string LockContention = "csharp.lock.contention";

    /// <summary>How asynchronous continuations interleave is a run-time fact.</summary>
    public const string ConcurrencyOrder = "csharp.concurrency.order";

    /// <summary>A generic type's arguments are erased.</summary>
    public const string GenericErased = "csharp.generic.erased";

    /// <summary>A type parameter's constraint was not read.</summary>
    public const string GenericConstraint = "csharp.generic.constraint";

    /// <summary>A type parameter's variance was not read.</summary>
    public const string GenericVariance = "csharp.generic.variance";

    /// <summary>The receiver of a through-pointer write is not tracked.</summary>
    public const string PointerCell = "csharp.pointer.cell";

    /// <summary>A span's indexer write is not modelled.</summary>
    public const string ReadOnlySpan = "csharp.readonlySpan.write";

    /// <summary>A catch clause's filter was not read.</summary>
    public const string CatchFilter = "csharp.catch.filter";

    /// <summary>A resource's disposal does not cover every path.</summary>
    public const string ResourceClosePath = "csharp.resource.closePath";

    /// <summary>A 'goto case' targets a label the switch generates.</summary>
    public const string GotoCaseTarget = "csharp.goto.caseTarget";
}

/// <summary>
/// A drop: a construct that was not lowered faithfully.
/// </summary>
public sealed class Drop
{
    /// <summary>The stable machine-readable id of the construct.</summary>
    [JsonPropertyName("construct")]
    public string Construct { get; set; } = "";

    /// <summary>A human-readable description of what was lost.</summary>
    [JsonPropertyName("detail")]
    public string Detail { get; set; } = "";

    /// <summary>SEMANTICS, STRUCTURE, VALUE or TYPE.</summary>
    [JsonPropertyName("impact")]
    public string Impact { get; set; } = DropImpact.Semantics;

    /// <summary>The source line. -1 when unknown.</summary>
    [JsonPropertyName("line")]
    public int Line { get; set; } = -1;

    /// <summary>The source column. -1 when unknown.</summary>
    [JsonPropertyName("column")]
    public int Column { get; set; } = -1;

    /// <summary>FUNCTION or PROGRAM.</summary>
    [JsonPropertyName("scope")]
    public string Scope { get; set; } = DropScope.Function;
}

/// <summary>
/// A try region: a try/catch/finally shape inside a function.
/// </summary>
/// <remarks>
/// The shape is what lets the engine build the exceptional edges. Without it
/// a catch body has to be treated as ordinary code, and the ordinary code is
/// the part that does not run when there is no exception.
/// </remarks>
public sealed class TryRegion
{
    /// <summary>The region's id, unique within the function.</summary>
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    /// <summary>
    /// The index of the first instruction of the protected region, or -1.
    /// </summary>
    /// <remarks>
    /// An INDEX, not a label. The engine's CFG builder asks which instruction RANGE a
    /// region covers, and a label is not a range: two labels can name the same place, and
    /// a label says nothing about where the region ends. When the region was written as
    /// labels, every region arrived empty, the builder wired no exceptional edge at all,
    /// and a catch body was unreachable on the exception path -- so the taint that flowed
    /// into it was lost, silently.
    /// </remarks>
    [JsonPropertyName("tryStart")]
    public int TryStart { get; set; } = -1;

    /// <summary>The index of the last instruction of the protected region, or -1.</summary>
    [JsonPropertyName("tryEnd")]
    public int TryEnd { get; set; } = -1;

    /// <summary>The index of the first instruction of the catch handlers, or -1.</summary>
    [JsonPropertyName("catchStart")]
    public int CatchStart { get; set; } = -1;

    /// <summary>The index of the last instruction of the catch handlers, or -1.</summary>
    [JsonPropertyName("catchEnd")]
    public int CatchEnd { get; set; } = -1;

    /// <summary>The labels of the catch handlers, in source order.</summary>
    [JsonPropertyName("catchLabels")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? CatchLabels { get; set; }

    /// <summary>The exception types the handlers catch, in the same order.</summary>
    [JsonPropertyName("catchTypes")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? CatchTypes { get; set; }

    /// <summary>The label of the finally block.</summary>
    [JsonPropertyName("finallyLabel")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? FinallyLabel { get; set; }

    /// <summary>The index of the first instruction of the finally block, or -1.</summary>
    [JsonPropertyName("finallyStart")]
    public int FinallyStart { get; set; } = -1;

    /// <summary>The index of the last instruction of the finally block, or -1.</summary>
    [JsonPropertyName("finallyEnd")]
    public int FinallyEnd { get; set; } = -1;

    /// <summary>
    /// The labels the finally block runs on the way out: the normal exit,
    /// every catch's exit, and the rethrow's exit.
    /// </summary>
    [JsonPropertyName("finallyExits")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? FinallyExits { get; set; }

    /// <summary>The variables a synthetic finally owns.</summary>
    [JsonPropertyName("resourceVars")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? ResourceVars { get; set; }

    /// <summary>Whether the finally was synthesized rather than written.</summary>
    [JsonPropertyName("syntheticFinally")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
    public bool SyntheticFinally { get; set; }
}

/// <summary>
/// A scope binding: a local-scope name and the slot it lives in.
/// </summary>
/// <remarks>
/// The TAC's variables are slots, and a name is a source-level alias for one.
/// The binding is what lets a reader trace a source name to the slot the
/// engine reasons about.
/// </remarks>
public sealed class ScopeBinding
{
    /// <summary>The source-level name.</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    /// <summary>The slot the name is bound to.</summary>
    [JsonPropertyName("slot")]
    public string Slot { get; set; } = "";

    /// <summary>The declared type of the binding, when known.</summary>
    [JsonPropertyName("type")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TypeRef? Type { get; set; }

    /// <summary>Whether the binding is a parameter rather than a local.</summary>
    [JsonPropertyName("parameter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
    public bool Parameter { get; set; }
}

/// <summary>
/// The member kinds a function can be.
/// </summary>
/// <remarks>
/// The kind decides what a reader may conclude from the function: a
/// constructor is an entry point, a closure is a value, a finalizer runs
/// when the object dies.
/// </remarks>
public static class MemberKinds
{
    /// <summary>An ordinary method.</summary>
    public const string Method = "METHOD";

    /// <summary>A constructor.</summary>
    public const string Constructor = "CONSTRUCTOR";

    /// <summary>A static constructor.</summary>
    public const string StaticInitializer = "STATIC_INITIALIZER";

    /// <summary>A property's getter.</summary>
    public const string Getter = "GETTER";

    /// <summary>A property's setter.</summary>
    public const string Setter = "SETTER";

    /// <summary>An indexer's accessor.</summary>
    public const string PropertyAccessor = "PROPERTY_ACCESSOR";

    /// <summary>An operator or a conversion operator.</summary>
    public const string Operator = "OPERATOR";

    /// <summary>An event's add or remove accessor.</summary>
    public const string EventAccessor = "EVENT_ACCESSOR";

    /// <summary>A finalizer.</summary>
    public const string Finalizer = "FINALIZER";

    /// <summary>A local function.</summary>
    public const string LocalFunction = "LOCAL_FUNCTION";

    /// <summary>A synthesized closure.</summary>
    public const string Closure = "CLOSURE";

    /// <summary>A field or property initializer.</summary>
    public const string FieldInitializer = "FIELD_INITIALIZER";
}
