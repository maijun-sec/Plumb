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

    /// <summary>SOURCE_FULL or SOURCE_LOWERED.</summary>
    [JsonPropertyName("precision")]
    public string Precision { get; set; } = "SOURCE_FULL";

    /// <summary>Whether the compiler generated this function.</summary>
    [JsonPropertyName("synthetic")]
    public bool Synthetic { get; set; }

    /// <summary>The parameters, in declaration order.</summary>
    [JsonPropertyName("params")]
    public List<Operand> Params { get; set; } = new();

    /// <summary>The locals.</summary>
    [JsonPropertyName("locals")]
    public List<Operand> Locals { get; set; } = new();

    /// <summary>The instruction stream, in source order.</summary>
    [JsonPropertyName("instructions")]
    public List<Instruction> Instructions { get; set; } = new();

    /// <summary>The problems encountered while lowering this function.</summary>
    [JsonPropertyName("problems")]
    public List<Problem> Problems { get; set; } = new();
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
}
