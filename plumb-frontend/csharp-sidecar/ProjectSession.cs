using System.Globalization;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Text;

namespace Plumb.CSharpSidecar;

/// <summary>
/// Holds the project compilation and translates files out of it.
/// </summary>
/// <remarks>
/// <para>
/// <b>Why the compilation is project-scoped.</b> A per-file
/// <c>CSharpCompilation</c> cannot resolve a reference to a type declared in
/// another file of the same project. That is not a precision loss -- the
/// symbol would simply fail to bind, and the frontend would report "no
/// findings" for a project that contains a vulnerability. So the compilation
/// covers the whole project, and it is built ONCE and reused for every file.
/// </para>
/// <para>
/// <b>Why the session is long-lived.</b> Building the compilation is the
/// expensive part: it parses every source file and binds every reference.
/// Doing it once per project is what makes the sidecar approach viable at
/// all.
/// </para>
/// </remarks>
internal sealed class ProjectSession
{
    private readonly string _projectId;
    private readonly CSharpCompilation _compilation;
    private readonly Dictionary<string, SyntaxTree> _treesByPath = new(StringComparer.OrdinalIgnoreCase);

    private ProjectSession(string projectId, CSharpCompilation compilation)
    {
        _projectId = projectId;
        _compilation = compilation;
    }

    /// <summary>The project id this session serves.</summary>
    public string ProjectId => _projectId;

    /// <summary>
    /// Build a project session.
    /// </summary>
    /// <param name="request">the openProject request</param>
    /// <returns>the session</returns>
    public static ProjectSession Open(Request request)
    {
        var trees = new List<SyntaxTree>();
        var treesByPath = new Dictionary<string, SyntaxTree>(StringComparer.OrdinalIgnoreCase);

        foreach (var source in request.Sources ?? new List<SourceFile>())
        {
            var text = SourceText.From(source.Text, System.Text.Encoding.UTF8);
            var tree = CSharpSyntaxTree.ParseText(
                text,
                new CSharpParseOptions(
                    languageVersion: ResolveLanguageVersion(request.LangVersion),
                    documentationMode: DocumentationMode.None,
                    preprocessorSymbols: request.PreprocessorSymbols ?? new List<string>()),
                path: source.Path);
            trees.Add(tree);
            treesByPath[source.Path] = tree;
        }

        var metadataReferences = new List<MetadataReference>();
        foreach (var reference in request.References ?? new List<string>())
        {
            if (string.IsNullOrWhiteSpace(reference))
            {
                continue;
            }
            try
            {
                metadataReferences.Add(MetadataReference.CreateFromFile(reference));
            }
            catch (Exception ex)
            {
                // A reference that cannot be loaded is a reference the compiler
                // will not see. The compile then reports the unresolved symbols,
                // which is the honest outcome. The failure is not fatal: the
                // rest of the references still work.
                Console.Error.WriteLine(
                    $"plumb-csharp-sidecar: could not load the reference '{reference}': {ex.Message}");
            }
        }

        // The reference assemblies of the running framework. Without them, even
        // `System.String` does not resolve, and the frontend's output would be
        // empty for a reason that has nothing to do with the code under
        // analysis.
        metadataReferences.AddRange(FrameworkReferences());

        var options = new CSharpCompilationOptions(
            OutputKind.DynamicallyLinkedLibrary,
            nullableContextOptions: ResolveNullableOptions(request.NullableContextOptions),
            allowUnsafe: request.Unsafe,
            // The compilation is never emitted; it exists only to bind symbols.
            // Concurrent builds are disabled so that the diagnostic set is
            // deterministic.
            concurrentBuild: false,
            optimizationLevel: OptimizationLevel.Debug);

        var compilation = CSharpCompilation.Create(
            assemblyName: "Plumb.CSharpAnalysis",
            syntaxTrees: trees,
            references: metadataReferences,
            options: options);

        var session = new ProjectSession(request.ProjectId ?? "project", compilation);
        foreach (var pair in treesByPath)
        {
            session._treesByPath[pair.Key] = pair.Value;
        }
        return session;
    }

    /// <summary>
    /// The framework's own reference assemblies.
    /// </summary>
    /// <remarks>
    /// The trusted platform assemblies of the running runtime are one file per
    /// assembly, which is exactly the shape the compiler wants. The fallback is
    /// the shared framework directory.
    /// </remarks>
    /// <returns>the metadata references</returns>
    public static IEnumerable<MetadataReference> FrameworkReferences()
    {
        var trusted = AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES") as string;
        if (!string.IsNullOrEmpty(trusted))
        {
            foreach (var path in trusted.Split(Path.PathSeparator))
            {
                if (path.EndsWith(".dll", StringComparison.OrdinalIgnoreCase)
                    && File.Exists(path))
                {
                    var reference = TryCreateReference(path);
                    if (reference is not null)
                    {
                        yield return reference;
                    }
                }
            }
            yield break;
        }

        // The fallback: the shared framework directory.
        var runtimeDirectory = Path.GetDirectoryName(typeof(object).Assembly.Location);
        if (runtimeDirectory is null || !Directory.Exists(runtimeDirectory))
        {
            yield break;
        }
        foreach (var path in Directory.EnumerateFiles(runtimeDirectory, "*.dll"))
        {
            var reference = TryCreateReference(path);
            if (reference is not null)
            {
                yield return reference;
            }
        }
    }

    private static MetadataReference? TryCreateReference(string path)
    {
        try
        {
            return MetadataReference.CreateFromFile(path);
        }
        catch (Exception)
        {
            // Not a managed assembly, or not loadable. It is skipped: the
            // compiler reports what it cannot resolve, which is the honest
            // outcome.
            return null;
        }
    }

    private static LanguageVersion ResolveLanguageVersion(string? requested)
    {
        if (string.IsNullOrWhiteSpace(requested))
        {
            return LanguageVersion.Latest;
        }
        if (requested.Equals("latest", StringComparison.OrdinalIgnoreCase))
        {
            return LanguageVersion.Latest;
        }
        if (requested.Equals("default", StringComparison.OrdinalIgnoreCase))
        {
            return LanguageVersion.Default;
        }
        if (Enum.TryParse<LanguageVersion>(requested, ignoreCase: true, out var parsed)
            && !LooksNumeric(requested))
        {
            return parsed;
        }
        // A build writes the version as a NUMBER ("12.0"), not as the compiler's
        // own spelling ("CSharp12"), so the number is what a captured request
        // normally carries.
        //
        // It is mapped here rather than left to Enum.TryParse, because the enum's
        // members are NAMED and SCALED: CSharp10 is the number 1000, not 10.
        // So Enum.TryParse("10") does not fail -- it succeeds and produces the
        // number 10, which is not a member at all. An undefined value makes the
        // compiler run at the newest language version, so the symptom is silent:
        // code the project itself would reject is compiled anyway, and nothing
        // reports the difference.
        var numeric = NumericLanguageVersion(requested);
        if (numeric.HasValue)
        {
            return numeric.Value;
        }
        return LanguageVersion.Latest;
    }

    /// <summary>
    /// Whether a spelling is a number rather than a name.
    /// </summary>
    /// <param name="requested">the requested version</param>
    /// <returns>true when the spelling starts with a digit</returns>
    private static bool LooksNumeric(string requested)
    {
        var text = requested.Trim();
        return text.Length > 0 && char.IsDigit(text[0]);
    }

    /// <summary>
    /// The language version for a numeric spelling.
    /// </summary>
    /// <remarks>
    /// The accepted spellings are "12", "12.0" and "12.0.1" -- the shapes a build
    /// file writes the version in. Only the leading component is read, because
    /// the language version is the major version; the rest is the framework and
    /// the patch level. The one exception is 7.1, 7.2 and 7.3, which are
    /// separate language versions.
    /// </remarks>
    /// <param name="requested">the requested version</param>
    /// <returns>the version, or null when the spelling names no version</returns>
    private static LanguageVersion? NumericLanguageVersion(string requested)
    {
        var text = requested.Trim();
        var dot = text.IndexOf('.');
        var majorText = dot < 0 ? text : text.Substring(0, dot);
        if (!int.TryParse(majorText, NumberStyles.Integer, CultureInfo.InvariantCulture, out var major))
        {
            return null;
        }
        if (dot < 0)
        {
            return major switch
            {
                1 => LanguageVersion.CSharp1,
                2 => LanguageVersion.CSharp2,
                3 => LanguageVersion.CSharp3,
                4 => LanguageVersion.CSharp4,
                5 => LanguageVersion.CSharp5,
                6 => LanguageVersion.CSharp6,
                7 => LanguageVersion.CSharp7,
                8 => LanguageVersion.CSharp8,
                9 => LanguageVersion.CSharp9,
                10 => LanguageVersion.CSharp10,
                11 => LanguageVersion.CSharp11,
                12 => LanguageVersion.CSharp12,
                _ => null,
            };
        }
        var minorText = text.Substring(dot + 1);
        var end = minorText.IndexOf('.');
        if (end >= 0)
        {
            minorText = minorText.Substring(0, end);
        }
        if (!int.TryParse(minorText, NumberStyles.Integer, CultureInfo.InvariantCulture, out var minor))
        {
            return null;
        }
        if (major != 7)
        {
            return null;
        }
        return minor switch
        {
            1 => LanguageVersion.CSharp7_1,
            2 => LanguageVersion.CSharp7_2,
            3 => LanguageVersion.CSharp7_3,
            _ => null,
        };
    }

    private static NullableContextOptions ResolveNullableOptions(string? requested)
    {
        if (string.IsNullOrWhiteSpace(requested))
        {
            return NullableContextOptions.Disable;
        }
        return requested.ToLowerInvariant() switch
        {
            "enable" => NullableContextOptions.Enable,
            "warnings" => NullableContextOptions.Warnings,
            "annotations" => NullableContextOptions.Annotations,
            _ => NullableContextOptions.Disable,
        };
    }

    /// <summary>
    /// The compiler's diagnostics for the whole project.
    /// </summary>
    /// <returns>the diagnostics</returns>
    public List<Diagnostic> Diagnostics()
    {
        var result = new List<Diagnostic>();
        foreach (var diagnostic in _compilation.GetDiagnostics())
        {
            var lineSpan = diagnostic.Location.GetLineSpan();
            result.Add(new Diagnostic
            {
                Severity = diagnostic.Severity.ToString().ToLowerInvariant(),
                Message = diagnostic.GetMessage(),
                Path = diagnostic.Location.IsInSource ? diagnostic.Location.SourceTree?.FilePath : null,
                Line = lineSpan.StartLinePosition.Line + 1,
                Column = lineSpan.StartLinePosition.Character + 1,
            });
        }
        return result;
    }

    /// <summary>
    /// Translate one file of the project.
    /// </summary>
    /// <param name="path">the file's path</param>
    /// <returns>the functions declared in the file</returns>
    public List<Function> Translate(string path)
    {
        var functions = new List<Function>();

        if (!_treesByPath.TryGetValue(path, out var tree))
        {
            // The file is not part of the open project. The Java side asked for
            // something the sidecar does not have; saying so is better than
            // returning an empty result that reads like "no findings".
            functions.Add(new Function
            {
                Name = "<unknown>",
                Signature = "<unknown>",
                Problems =
                {
                    new Problem
                    {
                        Kind = "PARSE_PROBLEM",
                        Message = $"the file '{path}' is not part of the open project",
                    },
                },
            });
            return functions;
        }

        var model = _compilation.GetSemanticModel(tree);
        var root = tree.GetRoot();

        foreach (var declaration in root.DescendantNodes())
        {
            switch (declaration)
            {
                case MethodDeclarationSyntax method:
                    functions.Add(TranslateMethod(method, model));
                    break;

                case ConstructorDeclarationSyntax constructor:
                    functions.Add(TranslateConstructor(constructor, model));
                    break;

                case PropertyDeclarationSyntax property:
                    // A property's ACCESSORS are the functions. The property
                    // itself is not a function: it has no body and no call
                    // target of its own.
                    foreach (var accessor in property.AccessorList?.Accessors ?? default)
                    {
                        functions.Add(TranslateAccessor(accessor, model, property));
                    }
                    if (property.ExpressionBody is not null)
                    {
                        functions.Add(TranslateExpressionBodyProperty(property, model));
                    }
                    break;

                case LocalFunctionStatementSyntax localFunction:
                    functions.Add(TranslateLocalFunction(localFunction, model));
                    break;
            }
        }

        return functions;
    }

    private Function TranslateMethod(MethodDeclarationSyntax method, SemanticModel model)
    {
        var symbol = model.GetDeclaredSymbol(method) as IMethodSymbol;
        var translator = new TacTranslator(model);
        translator.Translate(method, symbol);
        if (method.ExpressionBody is not null)
        {
            translator.TranslateArrow(method.ExpressionBody);
        }

        return BuildFunction(
            symbol,
            name: method.Identifier.ValueText,
            enclosingClass: symbol?.ContainingType.Name,
            translator);
    }

    private Function TranslateConstructor(ConstructorDeclarationSyntax constructor, SemanticModel model)
    {
        var symbol = model.GetDeclaredSymbol(constructor) as IMethodSymbol;
        var translator = new TacTranslator(model);
        translator.Translate(constructor, symbol);
        if (constructor.ExpressionBody is not null)
        {
            translator.TranslateArrow(constructor.ExpressionBody);
        }

        return BuildFunction(
            symbol,
            name: symbol?.Name ?? constructor.Identifier.ValueText,
            enclosingClass: symbol?.ContainingType.Name ?? constructor.Identifier.ValueText,
            translator);
    }

    private Function TranslateAccessor(
        AccessorDeclarationSyntax accessor,
        SemanticModel model,
        PropertyDeclarationSyntax property)
    {
        var symbol = model.GetDeclaredSymbol(accessor) as IMethodSymbol;
        var translator = new TacTranslator(model);
        translator.Translate(accessor, symbol);
        if (accessor.ExpressionBody is not null)
        {
            translator.TranslateArrow(accessor.ExpressionBody);
        }

        return BuildFunction(
            symbol,
            name: $"{property.Identifier.ValueText}.{accessor.Keyword.ValueText}",
            enclosingClass: symbol?.ContainingType.Name ?? property.Identifier.ValueText,
            translator);
    }

    private Function TranslateExpressionBodyProperty(
        PropertyDeclarationSyntax property,
        SemanticModel model)
    {
        var symbol = model.GetDeclaredSymbol(property) as IPropertySymbol;
        var getter = symbol?.GetMethod;
        var translator = new TacTranslator(model);

        // An expression-bodied property is a getter whose body is the
        // expression. It is lowered as a function so that a taint flowing into
        // the property's value is visible.
        if (property.ExpressionBody is not null)
        {
            translator.TranslateArrow(property.ExpressionBody);
        }

        return BuildFunction(
            getter,
            name: $"{property.Identifier.ValueText}.get",
            enclosingClass: symbol?.ContainingType.Name,
            translator);
    }

    private Function TranslateLocalFunction(
        LocalFunctionStatementSyntax localFunction,
        SemanticModel model)
    {
        var symbol = model.GetDeclaredSymbol(localFunction) as IMethodSymbol;
        var translator = new TacTranslator(model);
        translator.Translate(localFunction, symbol);
        if (localFunction.ExpressionBody is not null)
        {
            translator.TranslateArrow(localFunction.ExpressionBody);
        }

        return BuildFunction(
            symbol,
            name: localFunction.Identifier.ValueText,
            enclosingClass: symbol?.ContainingType.Name,
            translator);
    }

    private static Function BuildFunction(
        IMethodSymbol? symbol,
        string name,
        string? enclosingClass,
        TacTranslator translator)
    {
        return new Function
        {
            Name = name,
            Signature = symbol is null ? name : Signature.Declaration(symbol),
            EnclosingClass = enclosingClass,
            ReturnType = TypeMapper.Map(symbol?.ReturnType),
            Precision = translator.Degraded ? "SOURCE_LOWERED" : "SOURCE_FULL",
            Synthetic = symbol?.IsImplicitlyDeclared ?? false,
            Params = translator.Params.ToList(),
            Locals = translator.Locals.ToList(),
            Instructions = translator.Instructions.ToList(),
            Problems = translator.Problems.ToList(),
        };
    }
}
