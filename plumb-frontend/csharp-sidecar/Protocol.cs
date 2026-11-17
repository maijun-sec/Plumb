using System.Text.Json.Serialization;

namespace Plumb.CSharpSidecar;

/// <summary>
/// The wire model of the TAC instruction stream.
/// </summary>
/// <remarks>
/// <para>
/// These types mirror Plumb's TAC IR one-for-one, but they are a WIRE MODEL, not
/// the IR itself: they live on the .NET side of a process boundary, so they
/// cannot be the Java classes. The Java side maps them into
/// <c>org.plumb.tac.ir.TacInstruction</c> and friends.
/// </para>
/// <para>
/// See <c>PROTOCOL.md</c> for the contract. The field names are the JSON
/// property names, so they are lower-camel-cased to match the wire format.
/// </para>
/// </remarks>
public static class Tac
{
    /// <summary>A JSON-ignore marker for fields the wire format does not carry.</summary>
    public const int None = -1;
}

/// <summary>A type in the wire model.</summary>
public sealed class TypeRef
{
    /// <summary>The TAC type kind: BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, STRING, CLASS, ARRAY, NULL, VOID, UNKNOWN, DECIMAL.</summary>
    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "UNKNOWN";

    /// <summary>The fully qualified name, for CLASS.</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>The component type, for ARRAY.</summary>
    [JsonPropertyName("component")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TypeRef? Component { get; set; }

    /// <summary>Whether the reference may hold null. Defaults to true (Java's semantics).</summary>
    [JsonPropertyName("nullable")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
    public bool Nullable { get; set; } = true;

    /// <summary>
    /// The numeric domain of the type, when it is a numeric type.
    /// </summary>
    /// <remarks>
    /// It is a text form -- "INTEGRAL(32,signed)", "FLOATING(53,11)" -- so
    /// that a reader does not have to know a second notation. A type
    /// without a domain says nothing about its values, which is not the
    /// same as saying the values are unconstrained.
    /// </remarks>
    [JsonPropertyName("domain")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Domain { get; set; }

    /// <summary>The type arguments of a generic type, in declaration order.</summary>
    [JsonPropertyName("typeArguments")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<TypeRef>? TypeArguments { get; set; }

    /// <summary>The bound of a type parameter: the constraint it satisfies.</summary>
    [JsonPropertyName("bound")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TypeRef? Bound { get; set; }

    public static TypeRef Simple(string kind) => new() { Kind = kind };

    public static TypeRef Class(string name) => new() { Kind = "CLASS", Name = name };

    public static TypeRef Array(TypeRef component) => new() { Kind = "ARRAY", Component = component };
}

/// <summary>An operand in the wire model.</summary>
public sealed class Operand
{
    /// <summary>VAR or CONST.</summary>
    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "VAR";

    /// <summary>The variable's name, for VAR.</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>The variable's type, for VAR. Absent means UNKNOWN.</summary>
    [JsonPropertyName("type")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TypeRef? Type { get; set; }

    /// <summary>Whether the variable is a synthetic temporary.</summary>
    [JsonPropertyName("isTemp")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
    public bool IsTemp { get; set; }

    /// <summary>The constant's kind, for CONST: INT, LONG, FLOAT, DOUBLE, BOOL, CHAR, STRING, NULL, DECIMAL.</summary>
    [JsonPropertyName("constKind")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ConstKind { get; set; }

    /// <summary>The constant's value, for CONST. A number's or a boolean's text form.</summary>
    [JsonPropertyName("value")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Value { get; set; }
}

/// <summary>An instruction in the wire model.</summary>
public sealed class Instruction
{
    /// <summary>The TAC instruction kind, by name.</summary>
    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "NOP";

    /// <summary>The destination operand.</summary>
    [JsonPropertyName("dest")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Operand? Dest { get; set; }

    /// <summary>The first source operand.</summary>
    [JsonPropertyName("lhs1")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Operand? Lhs1 { get; set; }

    /// <summary>The second source operand.</summary>
    [JsonPropertyName("lhs2")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Operand? Lhs2 { get; set; }

    /// <summary>The binary operator, for BINOP.</summary>
    [JsonPropertyName("binop")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? BinOp { get; set; }

    /// <summary>The unary operator, for UNARYOP.</summary>
    [JsonPropertyName("unop")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? UnOp { get; set; }

    /// <summary>The cast's target type, for UNARYOP/CAST.</summary>
    [JsonPropertyName("castType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TypeRef? CastType { get; set; }

    /// <summary>The callee signature, for CALL and UNINTERPRETED: <c>owner.method/arity</c>.</summary>
    [JsonPropertyName("calleeSig")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? CalleeSig { get; set; }

    /// <summary>The argument list, for CALL, NEW and UNINTERPRETED.</summary>
    [JsonPropertyName("args")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Operand>? Args { get; set; }

    /// <summary>The constructed or caught type, for NEW, NEWARRAY, INSTANCEOF and CATCH_HANDLER.</summary>
    [JsonPropertyName("ctorType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TypeRef? CtorType { get; set; }

    /// <summary>The field name, for FIELDLOAD and FIELDSTORE.</summary>
    [JsonPropertyName("fieldName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? FieldName { get; set; }

    /// <summary>The true-branch label, for BRANCH.</summary>
    [JsonPropertyName("trueLabel")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? TrueLabel { get; set; }

    /// <summary>The false-branch label, for BRANCH.</summary>
    [JsonPropertyName("falseLabel")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? FalseLabel { get; set; }

    /// <summary>The label, for JUMP and LABEL.</summary>
    [JsonPropertyName("label")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Label { get; set; }

    /// <summary>The source line. -1 when unknown.</summary>
    [JsonPropertyName("line")]
    public int Line { get; set; } = -1;

    /// <summary>The source column. -1 when unknown.</summary>
    [JsonPropertyName("column")]
    public int Column { get; set; } = -1;

    /// <summary>
    /// The execution effect of this instruction, when it is not an ordinary
    /// step. DEFERRED means the call runs when the function returns;
    /// CONCURRENT means it runs on another thread; SUSPEND means the call
    /// yields and the function resumes at a later point. Empty means the call
    /// runs here, in this order.
    /// </summary>
    [JsonPropertyName("effect")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Effect { get; set; }

    /// <summary>
    /// The results of a call that returns more than one value.
    /// </summary>
    /// <remarks>
    /// The list is the RESULTS, in order: entry 0 is the first result, and
    /// it is the same value as <see cref="Dest"/>. A null entry means
    /// "this result is not bound".
    /// </remarks>
    [JsonPropertyName("resultDests")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Operand?>? ResultDests { get; set; }

    /// <summary>
    /// The variables the call writes back through a by-reference argument.
    /// </summary>
    /// <remarks>
    /// The list is parallel to <see cref="Args"/>: entry i is the variable
    /// that argument i is written back into, and a null entry means that
    /// argument is not by reference.
    /// </remarks>
    [JsonPropertyName("refOutDests")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Operand?>? RefOutDests { get; set; }

    /// <summary>
    /// The type that owns the field, for FIELDLOAD and FIELDSTORE.
    /// </summary>
    /// <remarks>
    /// The field name alone does not identify a storage: a promoted member
    /// and the member it promotes have the same name and two different
    /// storages. The owner says which type declared it.
    /// </remarks>
    [JsonPropertyName("ownerType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? OwnerType { get; set; }
}

/// <summary>A structured problem: something the frontend could not lower.</summary>
public sealed class Problem
{
    /// <summary>PARSE_PROBLEM, TRANSLATION_ERROR or UNHANDLED_NODE.</summary>
    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "PARSE_PROBLEM";

    /// <summary>A human-readable description.</summary>
    [JsonPropertyName("message")]
    public string Message { get; set; } = "";

    /// <summary>The simple class name of the AST node that caused it.</summary>
    [JsonPropertyName("nodeClass")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? NodeClass { get; set; }

    /// <summary>The source line. -1 when unknown.</summary>
    [JsonPropertyName("line")]
    public int Line { get; set; } = -1;

    /// <summary>The source column. -1 when unknown.</summary>
    [JsonPropertyName("column")]
    public int Column { get; set; } = -1;
}

/// <summary>A function in the wire model.</summary>
public sealed class Function
{
    /// <summary>The method's name.</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    /// <summary>The method's signature, in Plumb's <c>owner.method(Params):Return</c> form.</summary>
    [JsonPropertyName("signature")]
    public string Signature { get; set; } = "";

    /// <summary>The enclosing class's short name.</summary>
    [JsonPropertyName("enclosingClass")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? EnclosingClass { get; set; }

    /// <summary>The declared return type.</summary>
    [JsonPropertyName("returnType")]
    public TypeRef ReturnType { get; set; } = TypeRef.Simple("VOID");

    /// <summary>
    /// SOURCE_FULL, SOURCE_LOWERED or SOURCE_DEGRADED.
    /// </summary>
    /// <remarks>
    /// It is derived from the drops, not asserted: a SEMANTICS drop degrades
    /// the function, a STRUCTURE drop lowers it, and a VALUE or TYPE drop
    /// leaves it alone.
    /// </remarks>
    [JsonPropertyName("precision")]
    public string Precision { get; set; } = "SOURCE_FULL";

    /// <summary>
    /// What KIND of member this function is: METHOD, CONSTRUCTOR, GETTER,
    /// SETTER, PROPERTY_ACCESSOR, OPERATOR, EVENT_ACCESSOR, FINALIZER,
    /// LOCAL_FUNCTION, CLOSURE, STATIC_INITIALIZER or FIELD_INITIALIZER.
    /// </summary>
    /// <remarks>
    /// The kind decides what a reader may conclude from the function: a
    /// constructor is an entry point, a closure is not, a finalizer runs
    /// when the object dies.
    /// </remarks>
    [JsonPropertyName("memberKind")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? MemberKind { get; set; }

    /// <summary>Whether the compiler generated this function.</summary>
    [JsonPropertyName("synthetic")]
    public bool Synthetic { get; set; }

    /// <summary>The parameters, in declaration order.</summary>
    [JsonPropertyName("params")]
    public List<Operand> Params { get; set; } = new();

    /// <summary>
    /// The formal parameters with their types, in declaration order.
    /// </summary>
    /// <remarks>
    /// It carries the same operands as <see cref="Params"/>. It is spelled
    /// out separately because the two names are used by two different
    /// readers: <c>params</c> is the TAC's slot list, and this one is the
    /// source-level signature the engine matches a call's arguments against.
    /// </remarks>
    [JsonPropertyName("parameters")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Operand>? Parameters { get; set; }

    /// <summary>The locals.</summary>
    [JsonPropertyName("locals")]
    public List<Operand> Locals { get; set; } = new();

    /// <summary>
    /// The local-scope bindings: a source-level name and the slot it lives
    /// in.
    /// </summary>
    [JsonPropertyName("scopeBindings")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<ScopeBinding>? ScopeBindings { get; set; }

    /// <summary>The instruction stream, in source order.</summary>
    [JsonPropertyName("instructions")]
    public List<Instruction> Instructions { get; set; } = new();

    /// <summary>The problems encountered while lowering this function.</summary>
    [JsonPropertyName("problems")]
    public List<Problem> Problems { get; set; } = new();

    /// <summary>
    /// What this function lost: the constructs that were not lowered
    /// faithfully.
    /// </summary>
    /// <remarks>
    /// This is the channel the coverage report reads. An empty list means
    /// the function was fully understood; a non-empty one says exactly what
    /// was not, and at what cost.
    /// </remarks>
    [JsonPropertyName("drops")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Drop>? Drops { get; set; }

    /// <summary>
    /// The try/catch/finally regions of the function.
    /// </summary>
    /// <remarks>
    /// The exceptional edges of a CFG are built from these. Without them the
    /// only way to see a catch is to see the catch's BODY, and the body is
    /// the part that does not run when there is no exception.
    /// </remarks>
    [JsonPropertyName("tryRegions")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<TryRegion>? TryRegions { get; set; }

    /// <summary>
    /// The numeric domain of the function's result, when it is known.
    /// </summary>
    /// <remarks>
    /// It is a text form of the domain -- "INTEGRAL(32,signed)" -- because
    /// the domain is a property of a VALUE, and the value's own type record
    /// is where the shape lives.
    /// </remarks>
    [JsonPropertyName("domain")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Domain { get; set; }

    /// <summary>
    /// The annotations on the member: the attribute names, in source order.
    /// </summary>
    /// <remarks>
    /// An attribute is a security fact: it marks an entry point, an
    /// authorization check, a validation, a data binding. The sidecar read
    /// NO attributes at all, so every one of those marks was invisible.
    /// </remarks>
    [JsonPropertyName("methodAnnotations")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? MethodAnnotations { get; set; }

    /// <summary>
    /// The annotations on the member's DECLARING TYPE.
    /// </summary>
    [JsonPropertyName("classAnnotations")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? ClassAnnotations { get; set; }

    /// <summary>
    /// The annotations' argument values, keyed by the attribute's name.
    /// </summary>
    /// <remarks>
    /// The value is a TEXT form: <c>Name(arg1,arg2)</c>. The text form is
    /// the same one the Go side's struct tags use, and it is deliberate: a
    /// structured table would need a grammar the engine would have to know,
    /// and a text form is something a report can print.
    /// </remarks>
    [JsonPropertyName("annotationValues")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, string>? AnnotationValues { get; set; }
}

/// <summary>A compiler diagnostic.</summary>
public sealed class Diagnostic
{
    /// <summary>error, warning, info or hidden.</summary>
    [JsonPropertyName("severity")]
    public string Severity { get; set; } = "error";

    /// <summary>The diagnostic's message.</summary>
    [JsonPropertyName("message")]
    public string Message { get; set; } = "";

    /// <summary>The file the diagnostic is in.</summary>
    [JsonPropertyName("path")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Path { get; set; }

    /// <summary>The source line. -1 when unknown.</summary>
    [JsonPropertyName("line")]
    public int Line { get; set; } = -1;

    /// <summary>The source column. -1 when unknown.</summary>
    [JsonPropertyName("column")]
    public int Column { get; set; } = -1;
}

/// <summary>A request from the Java side.</summary>
public sealed class Request
{
    /// <summary>The operation: openProject, translate, closeProject, ping or shutdown.</summary>
    [JsonPropertyName("op")]
    public string Op { get; set; } = "ping";

    /// <summary>
    /// The protocol version the Java side speaks.
    /// </summary>
    /// <remarks>
    /// The sidecar refuses a request that names a version it does not speak, so a
    /// frontend and a sidecar that were built against different protocols fail
    /// loudly instead of being half-understood. A request that carries no version
    /// is accepted: it comes from a frontend older than the handshake, and it
    /// behaves the way that frontend expects.
    /// </remarks>
    [JsonPropertyName("protocolVersion")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? ProtocolVersion { get; set; }

    /// <summary>The project id, for openProject.</summary>
    [JsonPropertyName("projectId")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ProjectId { get; set; }

    /// <summary>The project's source files, for openProject.</summary>
    [JsonPropertyName("sources")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<SourceFile>? Sources { get; set; }

    /// <summary>The reference assemblies and libraries, for openProject.</summary>
    [JsonPropertyName("references")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? References { get; set; }

    /// <summary>The defined preprocessor symbols, for openProject.</summary>
    [JsonPropertyName("preprocessorSymbols")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? PreprocessorSymbols { get; set; }

    /// <summary>The language version, for openProject.</summary>
    [JsonPropertyName("langVersion")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? LangVersion { get; set; }

    /// <summary>The nullable context options, for openProject.</summary>
    [JsonPropertyName("nullableContextOptions")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? NullableContextOptions { get; set; }

    /// <summary>Whether unsafe code is permitted, for openProject.</summary>
    [JsonPropertyName("unsafe")]
    public bool Unsafe { get; set; }

    /// <summary>The file to translate, for translate.</summary>
    [JsonPropertyName("path")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Path { get; set; }
}

/// <summary>One source file in a project request.</summary>
public sealed class SourceFile
{
    /// <summary>The file's absolute path.</summary>
    [JsonPropertyName("path")]
    public string Path { get; set; } = "";

    /// <summary>The file's contents.</summary>
    [JsonPropertyName("text")]
    public string Text { get; set; } = "";
}

/// <summary>The versions this sidecar speaks.</summary>
public static class Versions
{
    /// <summary>
    /// The wire protocol version.
    /// </summary>
    /// <remarks>
    /// The Java side refuses a sidecar whose version it does not understand.
    /// The check exists because the two halves are built independently, and an
    /// instruction kind the frontend did not know used to become HAVOC -- which
    /// the engine never matches against a sink family, so the finding was absent
    /// while the scan reported success.
    /// </remarks>
    public const int PROTOCOL = 1;

    /// <summary>
    /// The IR version this sidecar was built against.
    /// </summary>
    /// <remarks>
    /// It travels with the wire so that a sidecar built against a different IR is
    /// refused rather than half-understood.
    /// </remarks>
    public const int IR = 1;
}

/// <summary>A response to the Java side.</summary>
public sealed class Response
{
    /// <summary>The operation this answers.</summary>
    [JsonPropertyName("op")]
    public string Op { get; set; } = "";

    /// <summary>
    /// The protocol version this sidecar speaks.
    /// </summary>
    /// <remarks>
    /// Every response carries it, so the Java side can refuse a sidecar it does
    /// not understand instead of interpreting it.
    /// </remarks>
    [JsonPropertyName("protocolVersion")]
    public int ProtocolVersion { get; set; } = Versions.PROTOCOL;

    /// <summary>The IR version this sidecar was built against.</summary>
    [JsonPropertyName("irVersion")]
    public int IrVersion { get; set; } = Versions.IR;

    /// <summary>Whether the operation succeeded.</summary>
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }

    /// <summary>The failure message, when the operation failed.</summary>
    [JsonPropertyName("error")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Error { get; set; }

    /// <summary>The compiler's diagnostics.</summary>
    [JsonPropertyName("diagnostics")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Diagnostic>? Diagnostics { get; set; }

    /// <summary>The translated functions.</summary>
    [JsonPropertyName("functions")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Function>? Functions { get; set; }

    /// <summary>
    /// The losses that belong to the WHOLE PROGRAM rather than to one
    /// function: a member kind that was never reached, a build fact the
    /// sidecar does not read, a package rule that is not implemented.
    /// </summary>
    /// <remarks>
    /// They are here because they have no function to live in. A file that
    /// declares an attribute whose value is compiled in loses something no
    /// function of that file owns.
    /// </remarks>
    [JsonPropertyName("drops")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Drop>? Drops { get; set; }

    /// <summary>
    /// The program-level drops, under the wire-protocol name for them.
    /// </summary>
    /// <remarks>
    /// The same records as <see cref="Drops"/>, under the other name the
    /// file-level drop channel is known by. A reader that knows either name
    /// finds the same records.
    /// </remarks>
    [JsonPropertyName("programDrops")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Drop>? ProgramDrops { get; set; }
}
