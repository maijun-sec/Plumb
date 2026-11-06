/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.tac.frontend.csharp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The C# frontend: C# source text to {@link TacFunction}s, through Roslyn.
 *
 * <h2>How it works</h2>
 *
 * <p>Plumb is a JVM product and Roslyn is a .NET library, so this frontend cannot call Roslyn
 * in-process. It drives a long-lived <b>.NET sidecar</b> over stdio instead -- see {@link
 * CSharpSidecarClient} and the sidecar's {@code PROTOCOL.md}.
 *
 * <p>The sidecar is <b>project-scoped</b>: one {@code CSharpCompilation} per project, built once
 * and reused for every file. A per-file compilation could not resolve a reference to a type
 * declared in another file of the same project, so the symbol would silently fail to bind and the
 * scan would report "no findings" for a project that contains a vulnerability. The sidecar's
 * compilation is the whole project for exactly that reason.
 *
 * <h2>What the C# frontend contributes to the IR</h2>
 *
 * <p>One thing the Java frontend cannot give: <b>the property accessor as a call</b>.
 *
 * <p>In C#, {@code cmd.CommandText = tainted} is, at the language level, a call to
 * {@code SqlCommand.set_CommandText}. The frontend therefore lowers a property assignment to a
 * {@code CALL} whose callee signature is {@code owner.set_X/arity}, with the assigned value as
 * argument 0.
 *
 * <p>This is not a stylistic choice. The DFA engine queries the sink catalogue only on {@code
 * CALL} instructions, and {@code FIELDSTORE} carries no callee signature at all. A property
 * lowered as a field store would make every property-shaped sink <b>structurally invisible</b>: the
 * finding would be absent while the scan still reported success. A genuine field assignment stays
 * a {@code FIELDSTORE}, because a field really is a field.
 *
 * <p>Nothing else is language-specific. The DFA engine needs no change for C#.
 *
 * <h2>Nullability</h2>
 *
 * <p>C#'s nullable reference annotations are a compile-time <b>warning</b>, not an error: a program
 * that violates one still compiles and still runs. So every C# reference type stays
 * <i>nullable</i> in the IR, and {@link SourceLanguage#hasNonNullTypeSystem()} answers {@code
 * false} for C#. Mapping the annotations onto the IR's nullability would let the null-dereference
 * checker suppress findings that are entirely real.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as a {@link
 * TacProgram.FunctionProblem} and yields whatever could be parsed. A missing .NET toolchain is
 * reported as an explicit error and a non-zero outcome -- <b>never</b> as "no findings".
 */
public final class CSharpTacFrontend implements TacFrontend, AutoCloseable {

  /** The problems of the CURRENT parse, in the IR's structured form. */
  private final List<TacProgram.FunctionProblem> structuredProblems = new ArrayList<>();

  private final Path sidecarExecutable;
  private CSharpSidecarClient sidecar;
  private boolean projectOpen;
  private String openProjectId;

  /**
   * The source text the open project was built with, keyed by the file's path.
   *
   * <p>It is what tells the frontend whether the open project is still current for a file. See
   * {@link #ensureProject}.
   */
  private final Map<String, String> openProjectSources = new LinkedHashMap<>();

  /** A frontend that finds the sidecar in the default places. */
  public CSharpTacFrontend() {
    this(CSharpSidecarClient.locateExecutable(null));
  }

  /**
   * A frontend over an explicit sidecar executable.
   *
   * @param sidecarExecutable the executable, or null to look in the default places
   */
  public CSharpTacFrontend(Path sidecarExecutable) {
    this.sidecarExecutable = sidecarExecutable;
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.CSHARP;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("cs");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // A C# construct that could not be lowered downgrades ITS function to
    // SOURCE_LOWERED, and that is decided per function by the sidecar.
    return IrPrecision.SOURCE_FULL;
  }

  /**
   * Parses a C# source without a compile context.
   *
   * @param source the source text
   * @param path the file's path
   * @return the functions the file declares, plus any problems
   */
  public ParseResult parse(String source, Path path) {
    return parse(source, path, null);
  }

  @Override
  public ParseResult parse(String source, Path path, CompileContext ctx) {
    if (source == null) {
      return ParseResult.empty();
    }
    structuredProblems.clear();

    List<String> problems = new ArrayList<>();
    try {
      ensureProject(ctx, path, source, problems);
      if (!projectOpen) {
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      Json.JsonObject response = request("translate", request -> request.put("path", pathKey(path)));
      if (!response.getBoolean("ok", false)) {
        String error = response.getString("error");
        problems.add("the C# sidecar could not translate " + path + ": " + error);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                "PARSE_PROBLEM",
                "the C# sidecar could not translate the file: " + error,
                null,
                -1,
                -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      List<TacFunction> functions = new ArrayList<>();
      Json.JsonArray array = response.getArray("functions");
      if (array != null) {
        for (int i = 0; i < array.size(); i++) {
          Json.JsonObject function = array.getObject(i);
          if (function != null) {
            functions.add(toFunction(function, path, ctx));
          }
        }
      }

      IrPrecision precision = defaultPrecision();
      for (TacFunction function : functions) {
        if (function.irPrecision() == IrPrecision.SOURCE_LOWERED) {
          precision = IrPrecision.SOURCE_LOWERED;
          break;
        }
      }
      return new ParseResult(functions, problems, precision);
    } catch (IOException | RuntimeException ex) {
      // A frontend must be TOTAL. A broken file reports a problem and yields
      // whatever could be parsed; an exception escaping here would abort the
      // whole scan for one bad file.
      String message =
          "C# parse failure in "
              + path
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              path == null ? "<file>" : path.toString(),
              "PARSE_PROBLEM",
              message,
              ex.getClass().getSimpleName(),
              -1,
              -1));
      return new ParseResult(List.of(), problems, defaultPrecision());
    }
  }

  @Override
  public StructuredParseResult parseStructured(String source, Path path, CompileContext ctx) {
    ParseResult plain = parse(source, path, ctx);
    return new StructuredParseResult(
        plain.functions(), List.copyOf(structuredProblems), plain.precision());
  }

  /**
   * Ensure the sidecar holds the project this file belongs to.
   *
   * <p>The sidecar's compilation covers the WHOLE project, so it is (re)opened when the file's
   * module changes -- and it is opened only once per module otherwise.
   *
   * <p><b>Why the file's own content is part of the key.</b> The sidecar's compilation is a
   * snapshot: it holds the source text it was given at {@code openProject} time. If the file being
   * parsed has since changed, the snapshot is stale, and the analysis would silently describe the
   * OLD code. That is the worst possible outcome -- a scan that reports findings about code that
   * is no longer there.
   *
   * <p>So the project is reopened whenever the module changes OR the file's content differs from
   * what the open project was built with. In the normal case -- a scan walking a project whose
   * files do not change -- the project is opened ONCE, and the expensive part stays expensive only
   * once.
   */
  private void ensureProject(CompileContext ctx, Path path, String source, List<String> problems)
      throws IOException {
    String projectId = projectIdOf(ctx, path);
    boolean stale =
        !projectOpen
            || !projectId.equals(openProjectId)
            || !source.equals(openProjectSourceOf(path));
    if (!stale) {
      return;
    }

    if (sidecar == null) {
      sidecar = CSharpSidecarClient.launch(sidecarExecutable, null);
    }

    // The project's source files. The sidecar needs ALL of them: a compilation
    // over one file cannot resolve the others.
    List<Json.Value> sources = new ArrayList<>();
    Map<String, String> sourceTexts = new LinkedHashMap<>();
    if (ctx != null && ctx.modules() != null) {
      for (CompileContext.Module module : ctx.modules()) {
        Path root = module.sourceRoot();
        if (root == null || !Files.isDirectory(root)) {
          continue;
        }
        try (var walk = Files.walk(root)) {
          for (Path file : walk.filter(Files::isRegularFile).toList()) {
            if (!file.getFileName().toString().endsWith(".cs")) {
              continue;
            }
            String text = readTextQuietly(file);
            if (text == null) {
              continue;
            }
            String key = file.toAbsolutePath().normalize().toString();
            sources.add(new Json.JsonObject().put("path", key).put("text", text));
            sourceTexts.put(key, text);
          }
        }
      }
    }

    // The file being parsed is always included, even when it is outside every
    // module -- a single-file scan is a legitimate case. And it is included
    // with the text the CALLER passed, not with the text on disk: the caller
    // may be parsing an unsaved buffer, and the sidecar must analyse the same
    // text the caller has.
    if (path != null) {
      String pathKey = pathKey(path);
      boolean present = false;
      for (Json.Value value : sources) {
        if (value instanceof Json.JsonObject object
            && pathKey.equals(object.getString("path"))) {
          present = true;
          break;
        }
      }
      if (!present) {
        sources.add(new Json.JsonObject().put("path", pathKey).put("text", source));
      }
      sourceTexts.put(pathKey, source);
    }

    // The reference assemblies: the classpath entries the build description gave
    // us. A C# project references .NET assemblies, not jars, so the classpath
    // is read as a list of assembly paths.
    List<Json.Value> references = new ArrayList<>();
    if (ctx != null) {
      for (Path entry : ctx.classpath()) {
        if (entry != null) {
          references.add(new Json.JsonString(entry.toString()));
        }
      }
    }

    Json.JsonObject request =
        new Json.JsonObject()
            .put("op", "openProject")
            .put("projectId", projectId)
            .put("sources", new Json.JsonArray())
            .put("references", new Json.JsonArray())
            .put("langVersion", "latest")
            .put("nullableContextOptions", "disable")
            .put("unsafe", false);
    // The arrays are filled after construction, because JsonArray is mutable.
    Json.JsonArray sourceArray = new Json.JsonArray();
    for (Json.Value value : sources) {
      sourceArray.add(value);
    }
    request.put("sources", sourceArray);
    Json.JsonArray referenceArray = new Json.JsonArray();
    for (Json.Value value : references) {
      referenceArray.add(value);
    }
    request.put("references", referenceArray);

    Json.JsonObject response = exchange(request);
    if (!response.getBoolean("ok", false)) {
      String error = response.getString("error");
      problems.add("the C# sidecar could not open the project: " + error);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>",
              "PARSE_PROBLEM",
              "the C# sidecar could not open the project: " + error,
              null,
              -1,
              -1));
      return;
    }

    // The compiler's diagnostics are the frontend's problems: they say the
    // source does not compile, which the caller must see.
    Json.JsonArray diagnostics = response.getArray("diagnostics");
    if (diagnostics != null) {
      for (int i = 0; i < diagnostics.size(); i++) {
        Json.JsonObject diagnostic = diagnostics.getObject(i);
        if (diagnostic == null) {
          continue;
        }
        String severity = diagnostic.getString("severity");
        if (!"error".equals(severity)) {
          continue;
        }
        String message = diagnostic.getString("message");
        String diagnosticPath = diagnostic.getString("path");
        int line = diagnostic.getInt("line", -1);
        int column = diagnostic.getInt("column", -1);
        problems.add(
            (diagnosticPath == null ? "" : diagnosticPath + ":")
                + line
                + ":"
                + column
                + ": "
                + message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                diagnosticPath == null ? "<project>" : diagnosticPath,
                "PARSE_PROBLEM",
                message,
                null,
                line,
                column));
      }
    }

    projectOpen = true;
    openProjectId = projectId;
    openProjectSources.clear();
    openProjectSources.putAll(sourceTexts);
  }

  /**
   * The source text the open project was built with for a file.
   *
   * @param path the file
   * @return the text, or null when the open project does not carry this file
   */
  private String openProjectSourceOf(Path path) {
    if (path == null) {
      return null;
    }
    return openProjectSources.get(pathKey(path));
  }

  /** The project id for a file: its module's name, or the file's own path. */
  private static String projectIdOf(CompileContext ctx, Path path) {
    if (ctx != null && path != null) {
      String module = ctx.moduleOf(path);
      if (module != null) {
        return module;
      }
    }
    return path == null ? "default" : pathKey(path);
  }

  private static String pathKey(Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  private static String readTextQuietly(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }

  /** Send a request and read the response. */
  private Json.JsonObject request(String op, java.util.function.Consumer<Json.JsonObject> fill)
      throws IOException {
    Json.JsonObject request = new Json.JsonObject().put("op", op);
    fill.accept(request);
    return exchange(request);
  }

  private Json.JsonObject exchange(Json.JsonObject request) throws IOException {
    String response = sidecar.exchange(Json.write(request));
    try {
      return Json.parseObject(response);
    } catch (Json.JsonException ex) {
      throw new IOException("the C# sidecar sent a malformed response: " + ex.getMessage());
    }
  }

  /** Map one wire function into the IR. */
  private TacFunction toFunction(Json.JsonObject wire, Path path, CompileContext ctx) {
    List<TacInstruction> instructions = new ArrayList<>();
    Json.JsonArray wireInstructions = wire.getArray("instructions");
    if (wireInstructions != null) {
      for (int i = 0; i < wireInstructions.size(); i++) {
        Json.JsonObject instruction = wireInstructions.getObject(i);
        if (instruction != null) {
          instructions.add(toInstruction(instruction));
        }
      }
    }

    List<TacOperand.TacVar> params = new ArrayList<>();
    Json.JsonArray wireParams = wire.getArray("params");
    if (wireParams != null) {
      for (int i = 0; i < wireParams.size(); i++) {
        Json.JsonObject operand = wireParams.getObject(i);
        TacOperand.TacVar variable = toVar(operand);
        if (variable != null) {
          params.add(variable);
        }
      }
    }

    List<TacOperand.TacVar> locals = new ArrayList<>();
    Json.JsonArray wireLocals = wire.getArray("locals");
    if (wireLocals != null) {
      for (int i = 0; i < wireLocals.size(); i++) {
        Json.JsonObject operand = wireLocals.getObject(i);
        TacOperand.TacVar variable = toVar(operand);
        if (variable != null) {
          locals.add(variable);
        }
      }
    }

    // The structured problems the sidecar reported for THIS function.
    Json.JsonArray wireProblems = wire.getArray("problems");
    if (wireProblems != null) {
      for (int i = 0; i < wireProblems.size(); i++) {
        Json.JsonObject problem = wireProblems.getObject(i);
        if (problem == null) {
          continue;
        }
        TacProgram.FunctionProblem structured =
            new TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                problem.getString("kind") == null ? "UNHANDLED_NODE" : problem.getString("kind"),
                problem.getString("message") == null ? "" : problem.getString("message"),
                problem.getString("nodeClass"),
                problem.getInt("line", -1),
                problem.getInt("column", -1));
        structuredProblems.add(structured);
      }
    }

    IrPrecision precision =
        "SOURCE_LOWERED".equals(wire.getString("precision"))
            ? IrPrecision.SOURCE_LOWERED
            : IrPrecision.SOURCE_FULL;

    return new TacFunction(
        wire.getString("name") == null ? "<unknown>" : wire.getString("name"),
        wire.getString("signature") == null ? "" : wire.getString("signature"),
        toType(wire.getObject("returnType")),
        params,
        locals,
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        wire.getString("enclosingClass"),
        org.plumb.tac.ir.SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.CSHARP,
        precision,
        wire.getBoolean("synthetic", false),
        Map.of(),
        ctx == null || path == null ? null : ctx.moduleOf(path));
  }

  /** Map one wire instruction into the IR. */
  private static TacInstruction toInstruction(Json.JsonObject wire) {
    String kindName = wire.getString("kind");
    TacInstruction.Kind kind;
    try {
      kind = TacInstruction.Kind.valueOf(kindName == null ? "NOP" : kindName);
    } catch (IllegalArgumentException ex) {
      // An instruction kind this build does not know. The sidecar is newer
      // than the frontend. The safest reading of an unknown instruction is one
      // whose effect is unknown.
      kind = TacInstruction.Kind.HAVOC;
    }

    TacInstruction.Builder builder = TacInstruction.builder(kind);
    builder.dest(toOperand(wire.getObject("dest")));
    builder.lhs1(toOperand(wire.getObject("lhs1")));
    builder.lhs2(toOperand(wire.getObject("lhs2")));
    builder.calleeSig(wire.getString("calleeSig"));
    builder.ctorType(toType(wire.getObject("ctorType")));
    builder.fieldName(wire.getString("fieldName"));
    builder.trueLabel(wire.getString("trueLabel"));
    builder.falseLabel(wire.getString("falseLabel"));
    builder.label(wire.getString("label"));
    builder.line(wire.getInt("line", -1));
    builder.column(wire.getInt("column", -1));

    String binop = wire.getString("binop");
    if (binop != null) {
      try {
        builder.binop(org.plumb.tac.ir.BinaryOperator.valueOf(binop));
      } catch (IllegalArgumentException ex) {
        // An operator this build does not know. The sidecar speaks the IR's
        // operator vocabulary, so this only happens when the two halves are out
        // of step -- and an unknown operator must not become a silent identity.
        builder.binop(org.plumb.tac.ir.BinaryOperator.ADD);
      }
    }
    String unop = wire.getString("unop");
    if (unop != null) {
      try {
        builder.unop(org.plumb.tac.ir.UnaryOperator.valueOf(unop));
      } catch (IllegalArgumentException ex) {
        builder.unop(org.plumb.tac.ir.UnaryOperator.CAST);
      }
    }
    TacType castType = toType(wire.getObject("castType"));
    if (castType != null) {
      builder.castType(castType);
    }
    Json.JsonArray args = wire.getArray("args");
    if (args != null && args.size() > 0) {
      List<TacOperand> list = new ArrayList<>();
      for (int i = 0; i < args.size(); i++) {
        TacOperand operand = toOperand(args.getObject(i));
        if (operand != null) {
          list.add(operand);
        }
      }
      builder.args(list);
    }
    return builder.build();
  }

  /** Map one wire operand into the IR. */
  private static TacOperand toOperand(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String kind = wire.getString("kind");
    if ("CONST".equals(kind)) {
      return toConst(wire);
    }
    return toVar(wire);
  }

  private static TacOperand.TacVar toVar(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String name = wire.getString("name");
    if (name == null) {
      return null;
    }
    return new TacOperand.TacVar(
        name, toType(wire.getObject("type")), wire.getBoolean("isTemp", false));
  }

  private static TacOperand toConst(Json.JsonObject wire) {
    String kindName = wire.getString("constKind");
    if (kindName == null) {
      kindName = "STRING";
    }
    String value = wire.getString("value");
    try {
      return switch (kindName) {
        case "INT" -> TacOperand.Const.ofInt(Integer.parseInt(value == null ? "0" : value));
        case "LONG" -> TacOperand.Const.ofLong(Long.parseLong(value == null ? "0" : value));
        case "FLOAT" -> TacOperand.Const.ofFloat(Float.parseFloat(value == null ? "0" : value));
        case "DOUBLE" -> TacOperand.Const.ofDouble(Double.parseDouble(value == null ? "0" : value));
        case "DECIMAL" -> TacOperand.Const.ofDecimal(value == null ? "0" : value);
        case "BOOL" -> TacOperand.Const.ofBool(Boolean.parseBoolean(value));
        case "CHAR" -> TacOperand.Const.ofChar(value == null || value.isEmpty() ? '\0' : value.charAt(0));
        case "NULL" -> TacOperand.Const.ofNull();
        default -> TacOperand.Const.ofString(value == null ? "" : value);
      };
    } catch (NumberFormatException ex) {
      // A constant the wire carried in a form this build cannot parse. The
      // value is unknown, not silently zero.
      return TacOperand.Const.ofString(value == null ? "" : value);
    }
  }

  /** Map one wire type into the IR. */
  private static TacType toType(Json.JsonObject wire) {
    if (wire == null) {
      return TacType.UNKNOWN;
    }
    String kindName = wire.getString("kind");
    if (kindName == null) {
      return TacType.UNKNOWN;
    }
    try {
      return switch (kindName) {
        case "BOOLEAN" -> TacType.BOOLEAN;
        case "BYTE" -> TacType.BYTE;
        case "SHORT" -> TacType.SHORT;
        case "CHAR" -> TacType.CHAR;
        case "INT" -> TacType.INT;
        case "LONG" -> TacType.LONG;
        case "FLOAT" -> TacType.FLOAT;
        case "DOUBLE" -> TacType.DOUBLE;
        case "DECIMAL" -> TacType.DECIMAL;
        case "STRING" -> TacType.STRING;
        case "NULL" -> TacType.NULL;
        case "VOID" -> TacType.VOID;
        case "CLASS" -> TacType.ofClass(wire.getString("name") == null ? "" : wire.getString("name"));
        case "ARRAY" -> TacType.arrayOf(toType(wire.getObject("component")));
        default -> TacType.UNKNOWN;
      };
    } catch (IllegalArgumentException ex) {
      return TacType.UNKNOWN;
    }
  }

  /**
   * Whether the .NET toolchain is available.
   *
   * <p>A missing sidecar is an explicit error, not a silent "no findings": a scan that reports
   * success while having analysed nothing is the worst possible outcome.
   *
   * @return true when the sidecar executable was found
   */
  public boolean isToolchainAvailable() {
    return sidecarExecutable != null && Files.isRegularFile(sidecarExecutable);
  }

  @Override
  public void close() {
    if (sidecar != null) {
      sidecar.close();
      sidecar = null;
    }
    projectOpen = false;
    openProjectId = null;
    openProjectSources.clear();
  }
}
