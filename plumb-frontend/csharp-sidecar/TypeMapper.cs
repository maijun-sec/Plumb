using Microsoft.CodeAnalysis;

namespace Plumb.CSharpSidecar;

/// <summary>
/// Maps Roslyn's type model onto Plumb's TAC types.
/// </summary>
/// <remarks>
/// <para>
/// The mapping is deliberately coarse. Plumb's TAC models only what the
/// dataflow engine needs: primitives, a tagged class reference, arrays, and the
/// sentinels. Generics are erased, exactly as they are for Java and Kotlin.
/// </para>
/// <para>
/// <b>The one thing that is NOT erased is nullability.</b> C#'s nullable
/// reference annotations are a warning, not a compile error -- a nullable
/// annotation can be violated at runtime and the program keeps running. So
/// C#'s nullability is deliberately NOT mapped onto <c>TacType.nullable</c>:
/// doing so would let the null-dereference checker suppress a finding that is
/// perfectly real. Every C# reference type stays nullable.
/// </para>
/// </remarks>
internal static class TypeMapper
{
    /// <summary>
    /// The numeric domain of a type, in the text form the wire carries.
    /// </summary>
    /// <param name="type">the TAC type</param>
    /// <returns>
    /// the domain's text, or an empty string when the type has no numeric
    /// domain
    /// </returns>
    /// <remarks>
    /// <para>
    /// The domain is a property of the VALUES a type admits, and it is what a
    /// range check needs: a checker that knows a value is a 32-bit signed
    /// integer can compare it against a bound without asking the type system
    /// first.
    /// </para>
    /// <para>
    /// C#'s integer types are all FIXED width, so the domain is exact. The
    /// floating-point types are IEEE-754, and <c>decimal</c> is a 128-bit
    /// fixed-point number whose scale is part of its value.
    /// </para>
    /// </remarks>
    public static string Domain(TypeRef? type)
    {
        if (type is null)
        {
            return "";
        }
        return type.Kind switch
        {
            "BYTE" => "INTEGRAL(8,signed)",
            "SHORT" => "INTEGRAL(16,signed)",
            "CHAR" => "INTEGRAL(16,signed)",
            "INT" => "INTEGRAL(32,signed)",
            "LONG" => "INTEGRAL(64,signed)",
            "FLOAT" => "FLOATING(24,8)",
            "DOUBLE" => "FLOATING(53,11)",
            "DECIMAL" => "FIXEDPOINT(128,28)",
            _ => "",
        };
    }

    /// <summary>Map a Roslyn type onto a TAC type.</summary>
    /// <param name="type">the Roslyn type, or null</param>
    /// <returns>the TAC type; UNKNOWN when the type is unknown</returns>
    public static TypeRef Map(ITypeSymbol? type)
    {
        if (type is null)
        {
            return TypeRef.Simple("UNKNOWN");
        }

        var mapped = MapCore(type);
        mapped.Domain = Domain(mapped);
        return mapped;
    }

    private static TypeRef MapCore(ITypeSymbol type)
    {
        switch (type.SpecialType)
        {
            case SpecialType.System_Boolean: return TypeRef.Simple("BOOLEAN");
            case SpecialType.System_Byte: return TypeRef.Simple("BYTE");
            case SpecialType.System_SByte: return TypeRef.Simple("BYTE");
            case SpecialType.System_Int16: return TypeRef.Simple("SHORT");
            case SpecialType.System_UInt16: return TypeRef.Simple("SHORT");
            case SpecialType.System_Char: return TypeRef.Simple("CHAR");
            case SpecialType.System_Int32: return TypeRef.Simple("INT");
            case SpecialType.System_UInt32: return TypeRef.Simple("INT");
            case SpecialType.System_Int64: return TypeRef.Simple("LONG");
            case SpecialType.System_UInt64: return TypeRef.Simple("LONG");
            case SpecialType.System_Single: return TypeRef.Simple("FLOAT");
            case SpecialType.System_Double: return TypeRef.Simple("DOUBLE");
            case SpecialType.System_Decimal: return TypeRef.Simple("DECIMAL");
            case SpecialType.System_String: return TypeRef.Class("System.String");
            case SpecialType.System_Object: return TypeRef.Class("System.Object");
            case SpecialType.System_Void: return TypeRef.Simple("VOID");
        }

        if (type is IArrayTypeSymbol array)
        {
            return TypeRef.Array(Map(array.ElementType));
        }

        if (type.TypeKind == TypeKind.TypeParameter)
        {
            // A generic parameter: the concrete type is not known here.
            return TypeRef.Simple("UNKNOWN");
        }

        if (type.TypeKind == TypeKind.Error)
        {
            // An unresolved type. The source does not compile, or the symbol is
            // outside what the model could bind.
            return TypeRef.Simple("UNKNOWN");
        }

        return TypeRef.Class(FullName(type));
    }

    /// <summary>The fully qualified name of a type, in .NET's own namespace form.</summary>
    /// <param name="type">the type</param>
    /// <returns>the fully qualified name</returns>
    public static string FullName(ITypeSymbol type)
    {
        if (type is IArrayTypeSymbol array)
        {
            return FullName(array.ElementType) + "[]";
        }

        if (type is INamedTypeSymbol named)
        {
            // An ANONYMOUS type has no name of its own: the compiler gives it
            // a display form and nothing else. The display form is what the
            // setter and the getter of an anonymous type's property BOTH
            // carry, so they name the same owner -- and they have to, or the
            // value written through the setter and the value read through the
            // getter would be two different variables.
            if (named.Name.Length == 0)
            {
                return named.ToDisplayString(AnonymousTypeFormat);
            }

            // A generic type's name carries its type arguments, but the TAC
            // erases generics -- so `List<int>` and `List<string>` are the same
            // `System.Collections.Generic.List`. The arity is dropped so the
            // name matches the way the catalogue writes it.
            string name = named.Name;
            int tick = name.IndexOf('`');
            if (tick >= 0)
            {
                name = name.Substring(0, tick);
            }

            if (named.ContainingType is not null)
            {
                return FullName(named.ContainingType) + "." + name;
            }

            string? containingNamespace = named.ContainingNamespace?.ToDisplayString();
            if (string.IsNullOrEmpty(containingNamespace) || containingNamespace == "<global namespace>")
            {
                return name;
            }
            return containingNamespace + "." + name;
        }

        return type.Name;
    }

    /// <summary>
    /// The display form of an ANONYMOUS type.
    /// </summary>
    /// <remarks>
    /// The generics are dropped, for the same reason every other type's are:
    /// the TAC erases them.
    /// </remarks>
    private static readonly SymbolDisplayFormat AnonymousTypeFormat =
        SymbolDisplayFormat.CSharpErrorMessageFormat.WithGenericsOptions(
            SymbolDisplayGenericsOptions.None);
}

/// <summary>
/// Builds the callee signatures that the sink catalogue matches against.
/// </summary>
/// <remarks>
/// <para>
/// The signature is <c>owner.method/arity</c> -- the same canonical form every
/// other Plumb frontend emits, and the same form
/// <c>CalleeKeyNormalizer</c> canonicalises. That is what lets a C# sink
/// pattern be written exactly like a Java one.
/// </para>
/// </remarks>
internal static class Signature
{
    /// <summary>The canonical callee key for a method.</summary>
    /// <param name="method">the method</param>
    /// <returns>the <c>owner.method/arity</c> key</returns>
    public static string For(IMethodSymbol method)
    {
        string owner = TypeMapper.FullName(method.ContainingType);
        return $"{owner}.{method.Name}/{method.Parameters.Length}";
    }

    /// <summary>The declaration signature, in Plumb's <c>owner.method(Params):Return</c> form.</summary>
    /// <param name="method">the method</param>
    /// <returns>the signature</returns>
    public static string Declaration(IMethodSymbol method)
    {
        string owner = TypeMapper.FullName(method.ContainingType);
        var parameters = new List<string>();
        foreach (var parameter in method.Parameters)
        {
            parameters.Add(TypeMapper.FullName(parameter.Type));
        }
        string returnType = TypeMapper.FullName(method.ReturnType);
        return $"{owner}.{method.Name}({string.Join(",", parameters)}):{returnType}";
    }
}
