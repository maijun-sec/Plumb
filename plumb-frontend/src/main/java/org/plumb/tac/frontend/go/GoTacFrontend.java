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
package org.plumb.tac.frontend.go;

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
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.frontend.sidecar.SidecarProtocol;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The Go frontend: Go source text to {@link TacFunction}s, through the Go toolchain's own type
 * checker.
 *
 * <h2>How it works</h2>
 *
 * <p>Plumb is a JVM product and Go's type resolver is a Go program -- {@code go/parser},
 * {@code go/types} and the toolchain's export data are Go source in the Go standard library.
 * There is no JVM port of them, and writing a second implementation of Go's type system would
 * mean a type that resolves differently, hence a different callee signature, hence a sink family
 * that does not match. So this frontend drives a long-lived <b>Go sidecar</b> over stdio instead.
 *
 * <h2>Why the sidecar is PROJECT-SCOPED</h2>
 *
 * <p>Go's unit of compilation is the <b>package</b>, and a package cannot be compiled on its own:
 * it names symbols its siblings declare. The packages of a project depend on each other, so
 * translating one of them alone would leave every cross-package call unresolved -- and an
 * unresolved call is a missing finding, not a missing precision.
 *
 * <p>So the sidecar holds <b>the whole project</b> -- every package of it -- and translates out of
 * that. The project is opened once and reused for every file, which is also what makes the
 * approach viable: type checking is the expensive part, and doing it once per project rather than
 * once per file is the difference between usable and unusable.
 *
 * <h2>Where the packages come from</h2>
 *
 * <p>From the build, when there was a capture: every package the build compiled, with the export
 * data of its dependencies. Without a capture, from the source tree: the module the file belongs
 * to, and every package in it. Either way the project is the whole thing, never one package.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as a {@link
 * TacProgram.FunctionProblem} and yields whatever could be parsed. A missing Go toolchain is
 * reported as an explicit error -- <b>never</b> as "no findings".
 */
public final class GoTacFrontend implements TacFrontend, AutoCloseable {

  /** The problems of the CURRENT parse, in the IR's structured form. */
  private final List<TacProgram.FunctionProblem> structuredProblems = new ArrayList<>();

  private final Path sidecarExecutable;
  private final GoProjectProfile profile;
  private GoSidecarClient sidecar;
  private boolean projectOpen;
  private String openProjectId;

  /**
   * The source text the open project was built with, keyed by the file's path.
   *
   * <p>It is what tells the frontend whether the open project is still current for a file.
   */
  private final Map<String, String> openProjectSources = new LinkedHashMap<>();

  /** Whether the open project's packages were captured rather than discovered. */
  private boolean packagesCaptured;

  /** A frontend that finds the sidecar in the default places. */
  public GoTacFrontend() {
    this(GoSidecarClient.locateExecutable(null), null);
  }

  /**
   * A frontend over an explicit sidecar executable.
   *
   * @param sidecarExecutable the executable, or null to look in the default places
   */
  public GoTacFrontend(Path sidecarExecutable) {
    this(sidecarExecutable, null);
  }

  /**
   * A frontend over the default sidecar, with the project's packages.
   *
   * @param profile the captured project, or null when there was no capture
   */
  public GoTacFrontend(GoProjectProfile profile) {
    this(GoSidecarClient.locateExecutable(null), profile);
  }

  /**
   * A frontend over an explicit sidecar executable, with the project's packages.
   *
   * @param sidecarExecutable the executable, or null to look in the default places
   * @param profile the captured project, or null when there was no capture
   */
  public GoTacFrontend(Path sidecarExecutable, GoProjectProfile profile) {
    this.sidecarExecutable = sidecarExecutable;
    this.profile = profile;
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.GO;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("go");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // A Go construct that could not be lowered downgrades ITS function to
    // SOURCE_LOWERED, and that is decided per function by the sidecar.
    return IrPrecision.SOURCE_FULL;
  }

  /**
   * Parses a Go source without a compile context.
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

      Json.JsonObject response =
          request("translate", request -> request.put("path", pathKey(path)));
      if (!response.getBoolean("ok", false)) {
        String error = response.getString("error");
        problems.add("the Go sidecar could not translate " + path + ": " + error);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                "PARSE_PROBLEM",
                "the Go sidecar could not translate the file: " + error,
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
          "Go parse failure in "
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
   * Ensure the sidecar holds the whole Go project.
   *
   * <p>The sidecar's compilation covers every package of the project, so it is opened once and
   * reused. It is reopened when the file's content differs from what the open project was built
   * with, because the sidecar's compilation is a snapshot: a file that has since changed would
   * otherwise be analysed against the OLD text.
   *
   * @param ctx the compile context
   * @param path the file being parsed
   * @param source the file's text
   * @param problems the problems of the current parse
   * @throws IOException when the sidecar could not be reached
   */
  private void ensureProject(CompileContext ctx, Path path, String source, List<String> problems)
      throws IOException {
    GoProjectProfile project = profile;
    if (project == null) {
      // No capture: the project is discovered from the source tree, so that the
      // analysis still covers the WHOLE module rather than the one package the
      // file happens to be in.
      project = GoProjectDiscovery.discover(path, ctx);
    }
    packagesCaptured = profile != null && !profile.isEmpty();

    String projectId = projectIdOf(project, path);
    boolean stale =
        !projectOpen || !projectId.equals(openProjectId) || !source.equals(openProjectSourceOf(path));
    if (!stale) {
      return;
    }

    if (sidecar == null) {
      // A null executable means "look in the default places". The lookup
      // happens HERE rather than in the constructor, because the constructor
      // may run long before the sidecar is needed, and the build output may
      // have appeared in between.
      Path executable =
          sidecarExecutable != null ? sidecarExecutable : GoSidecarClient.locateExecutable(null);
      sidecar = GoSidecarClient.launch(executable, null);
    }

    // Every source file of every package, with its text. The sidecar needs the
    // whole project: a package cannot be type checked on its own.
    List<Json.Value> sources = new ArrayList<>();
    Map<String, String> sourceTexts = new LinkedHashMap<>();
    for (GoPackage goPackage : project.packages()) {
      for (Path file : goPackage.files()) {
        String text = readTextQuietly(file);
        if (text == null) {
          continue;
        }
        String key = pathKey(file);
        sources.add(new Json.JsonObject().put("path", key).put("text", text));
        sourceTexts.put(key, text);
      }
    }

    // The file being parsed is always included, even when it is outside every
    // package -- a single-file scan is a legitimate case. And it is included
    // with the text the CALLER passed, not with the text on disk: the caller may
    // be parsing an unsaved buffer, and the sidecar must analyse the same text
    // the caller has.
    if (path != null) {
      String key = pathKey(path);
      boolean present = false;
      for (Json.Value value : sources) {
        if (value instanceof Json.JsonObject object && key.equals(object.getString("path"))) {
          present = true;
          break;
        }
      }
      if (!present) {
        sources.add(new Json.JsonObject().put("path", key).put("text", source));
      }
      sourceTexts.put(key, source);
    }

    Json.JsonObject request =
        SidecarProtocol.stamp(
            new Json.JsonObject()
                .put("op", "openProject")
                .put("projectId", projectId)
                .put("goroot", project.goroot() == null ? null : project.goroot().toString())
                .put("sources", new Json.JsonArray())
                .put("packages", new Json.JsonArray()));
    Json.JsonArray sourceArray = new Json.JsonArray();
    for (Json.Value value : sources) {
      sourceArray.add(value);
    }
    request.put("sources", sourceArray);
    Json.JsonArray packageArray = new Json.JsonArray();
    Map<String, Path> modules = new LinkedHashMap<>();
    for (GoPackage goPackage : project.packages()) {
      packageArray.add(toWirePackage(goPackage));
      modules.putAll(goPackage.modules());
    }
    request.put("packages", packageArray);
    // The modules that live on disk. The sidecar uses them to find the source
    // of a dependency that the toolchain's own lookup cannot: one that was
    // REPLACED, or one that belongs to another module of a workspace. Neither
    // is in the module cache.
    Json.JsonArray moduleArray = new Json.JsonArray();
    for (Map.Entry<String, Path> entry : modules.entrySet()) {
      moduleArray.add(
          new Json.JsonObject()
              .put("path", entry.getKey())
              .put("dir", entry.getValue() == null ? null : entry.getValue().toString()));
    }
    request.put("modules", moduleArray);

    Json.JsonObject response = exchange(request);
    String incompatible = SidecarProtocol.incompatibility(response);
    if (incompatible != null) {
      problems.add("the Go sidecar is not compatible with this build: " + incompatible);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", incompatible, "PROTOCOL_MISMATCH", -1, -1));
      return;
    }
    if (!response.getBoolean("ok", false)) {
      String error = response.getString("error");
      problems.add("the Go sidecar could not open the project: " + error);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>",
              "PARSE_PROBLEM",
              "the Go sidecar could not open the project: " + error,
              null,
              -1,
              -1));
      return;
    }

    // The type checker's diagnostics are the frontend's problems: they say the
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

    if (!packagesCaptured) {
      String message =
          "the Go project's dependencies were not captured, so the packages' imports cannot be "
              + "resolved from the build's export data and the types of external packages may be "
              + "unknown";
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "MISSING_COMPILATION_INPUTS", -1, -1));
    }

    // The build's export data is the best source of a dependency's types, and
    // it is gone in the normal case: the toolchain writes it into the build's
    // temporary directory and deletes that directory when the build ends. The
    // dependencies are then resolved from their SOURCE, which is a second
    // opinion about the types rather than the build's own. That is a real
    // difference in what the analysis reasons about, so it is reported.
    if (project.lostExportData() != null && !project.lostExportData().isEmpty()) {
      String message =
          "the Go build's import configuration was gone when the capture was read ("
              + String.join("; ", project.lostExportData())
              + "): the build deletes it when it ends, so the dependencies' types were resolved "
              + "from their source rather than from the build's export data";
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "MISSING_COMPILATION_INPUTS", -1, -1));
    }

    projectOpen = true;
    openProjectId = projectId;
    openProjectSources.clear();
    openProjectSources.putAll(sourceTexts);
  }

  /**
   * The wire shape of one package.
   *
   * @param goPackage the package
   * @return the wire object
   */
  private static Json.JsonObject toWirePackage(GoPackage goPackage) {
    Json.JsonObject wire =
        new Json.JsonObject()
            .put("importPath", goPackage.importPath())
            .put("dir", goPackage.directory() == null ? null : goPackage.directory().toString());
    Json.JsonArray files = new Json.JsonArray();
    for (Path file : goPackage.files()) {
      files.add(new Json.JsonString(file.toString()));
    }
    wire.put("files", files);
    wire.put("goVersion", goPackage.goVersion());
    Json.JsonArray tags = new Json.JsonArray();
    for (String tag : goPackage.buildTags()) {
      tags.add(new Json.JsonString(tag));
    }
    wire.put("buildTags", tags);
    Json.JsonArray imports = new Json.JsonArray();
    for (Map.Entry<String, Path> entry : goPackage.imports().entrySet()) {
      Json.JsonObject dependency =
          new Json.JsonObject()
              .put("importPath", entry.getKey())
              .put("exportData", entry.getValue() == null ? null : entry.getValue().toString());
      // The dependency's source directory. It is what lets the sidecar resolve a
      // dependency that the toolchain's own lookup cannot find: one that was
      // replaced, or one that belongs to another module of a workspace. A
      // dependency without it is still resolved from the module cache.
      Path sourceDirectory = GoModuleSources.directoryFor(goPackage.modules(), entry.getKey());
      if (sourceDirectory != null) {
        dependency.put("dir", sourceDirectory.toString());
      }
      imports.add(dependency);
    }
    wire.put("imports", imports);
    return wire;
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

  /**
   * The project id.
   *
   * <p>The project is the whole Go project, so the id is the same for every file of it -- which is
   * exactly what makes the sidecar hold one compilation for the project rather than one per file.
   *
   * @param project the project
   * @param path the file being parsed
   * @return the id
   */
  private static String projectIdOf(GoProjectProfile project, Path path) {
    if (project != null && !project.moduleRoots().isEmpty()) {
      return "go:" + project.moduleRoots().get(0);
    }
    if (project != null && !project.packages().isEmpty()) {
      return "go:" + project.packages().get(0).importPath();
    }
    return path == null ? "go:default" : "go:" + pathKey(path);
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
    // EVERY request carries the version, not just the opening one. The
    // sidecar refuses a request that names no version, because a frontend too
    // old to know about the handshake is exactly what the handshake exists to
    // catch -- and a request that silently lost its stamp would be refused for
    // a reason nobody could see.
    Json.JsonObject request = SidecarProtocol.stamp(new Json.JsonObject().put("op", op));
    fill.accept(request);
    return exchange(request);
  }

  private Json.JsonObject exchange(Json.JsonObject request) throws IOException {
    String response = sidecar.exchange(Json.write(request));
    try {
      return Json.parseObject(response);
    } catch (Json.JsonException ex) {
      throw new IOException("the Go sidecar sent a malformed response: " + ex.getMessage());
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
        TacOperand.TacVar variable = toVar(wireParams.getObject(i));
        if (variable != null) {
          params.add(variable);
        }
      }
    }

    List<TacOperand.TacVar> locals = new ArrayList<>();
    Json.JsonArray wireLocals = wire.getArray("locals");
    if (wireLocals != null) {
      for (int i = 0; i < wireLocals.size(); i++) {
        TacOperand.TacVar variable = toVar(wireLocals.getObject(i));
        if (variable != null) {
          locals.add(variable);
        }
      }
    }

    // The structured problems the sidecar reported for THIS function. A Go
    // construct that could not be modelled has to be reported, never absent.
    Json.JsonArray wireProblems = wire.getArray("problems");
    if (wireProblems != null) {
      for (int i = 0; i < wireProblems.size(); i++) {
        Json.JsonObject problem = wireProblems.getObject(i);
        if (problem == null) {
          continue;
        }
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                problem.getString("kind") == null ? "UNHANDLED_NODE" : problem.getString("kind"),
                problem.getString("message") == null ? "" : problem.getString("message"),
                problem.getString("nodeClass"),
                problem.getInt("line", -1),
                problem.getInt("column", -1)));
      }
    }

    IrPrecision precision =
        "SOURCE_LOWERED".equals(wire.getString("precision"))
            ? IrPrecision.SOURCE_LOWERED
            : IrPrecision.SOURCE_FULL;

    // The Go-specific facts the sidecar reports in the wire's own fields.
    //
    // They are reported HERE rather than by the sidecar, because they are
    // STRUCTURED: the side table says WHICH call runs at the exit and WHERE
    // the defer was written, and the flag says that the function starts a
    // goroutine. A single sentence from the sidecar could not carry that.
    //
    // The sidecar marks the function as not fully understood for both of
    // them, so the information is not lost by being reported on this side.
    Json.JsonArray wireDeferred = wire.getArray("deferredCalls");
    if (wireDeferred != null) {
      for (int i = 0; i < wireDeferred.size(); i++) {
        Json.JsonObject deferred = wireDeferred.getObject(i);
        if (deferred == null) {
          continue;
        }
        String callee = deferred.getString("calleeSig");
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                "SOURCE_LOWERED",
                "the call "
                    + (callee == null ? "<unknown>" : callee)
                    + " runs when the function exits; the TAC places it at the defer statement",
                null,
                deferred.getInt("line", -1),
                deferred.getInt("column", -1)));
      }
    }
    if (wire.getBoolean("startsGoroutine", false)) {
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              path == null ? "<file>" : path.toString(),
              "SOURCE_LOWERED",
              "the function starts a goroutine; the analysis models no happens-before between"
                  + " goroutines",
              null,
              -1,
              -1));
    }

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
        SourceLanguage.GO,
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
   * Whether the Go toolchain is available.
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
