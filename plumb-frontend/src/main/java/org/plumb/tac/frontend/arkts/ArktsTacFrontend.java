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
package org.plumb.tac.frontend.arkts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.frontend.sidecar.SidecarProtocol;
import org.plumb.tac.frontend.sidecar.SidecarTacMapper;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The ArkTS frontend: source text to {@link TacFunction}s, through the ArkTS compiler's own front
 * end.
 *
 * <h2>How it works</h2>
 *
 * <p>Plumb is a JVM product and the ArkTS compiler is not a JVM program. ArkTS is read by a fork of
 * the TypeScript compiler that knows the language: it parses {@code struct}, it knows the ArkUI
 * decorators, and it parses the declarative UI DSL. Writing a second implementation of that grammar
 * in the JVM would mean a type that resolves differently, hence a different callee signature, hence
 * a sink family that does not match. So this frontend drives a long-lived <b>ArkTS sidecar</b> over
 * stdio instead.
 *
 * <h2>Why the sidecar is PROJECT-SCOPED</h2>
 *
 * <p>An ArkTS file names the modules it imports, and the compiler resolves those names against the
 * project. A file analysed on its own therefore has unknown types wherever it imports something --
 * and an unknown type is an unresolved callee, which is a <b>missing finding</b> rather than a
 * missing precision.
 *
 * <p>So the sidecar holds the whole project and translates out of it. The project is opened once
 * and reused for every file, which is also what makes the approach viable: type checking is the
 * expensive part, and doing it once per project rather than once per file is the difference between
 * usable and unusable.
 *
 * <h2>Why the compiler reads the files itself</h2>
 *
 * <p>The compiler reads the source from disk. It has to: its types are the types of the files as
 * they are, and a file whose text was replaced by a caller's copy would be a <b>different
 * program</b> -- the compiler would resolve the imports of one version and the positions of
 * another.
 *
 * <p>So the text the caller passes in is checked against the text on disk, and a difference is
 * <b>reported</b>: the analysis used the file on disk, and saying so is the difference between a
 * known lower bound and a silent surprise.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as a {@link
 * TacProgram.FunctionProblem} and yields whatever could be parsed. A missing Node runtime or a
 * missing sidecar is reported as an explicit error -- <b>never</b> as "no findings".
 *
 * @see ArktsSidecarClient
 */
public final class ArktsTacFrontend implements TacFrontend, AutoCloseable {

  /** The problems of the CURRENT parse, in the IR's structured form. */
  private final List<TacProgram.FunctionProblem> structuredProblems = new ArrayList<>();

  /** The language this frontend reads. */
  private final SourceLanguage language;

  /**
   * The command that starts the sidecar, or null to look for it.
   *
   * <p>An explicit command is how a caller pins the runtime and the script; it is also how the
   * tests drive the frontend against a stand-in sidecar.
   */
  private final List<String> command;

  /** The projects this frontend has opened, keyed by their root. */
  private final Map<Path, ArktsProjectDiscovery.Project> projects = new HashMap<>();

  private ArktsSidecarClient sidecar;
  private boolean projectOpen;
  private String openProjectId;
  private final Set<String> openProjectFiles = new HashSet<>();

  /**
   * A frontend that finds the sidecar in the default places.
   *
   * @param language the language to read
   */
  public ArktsTacFrontend(SourceLanguage language) {
    this(language, null);
  }

  /**
   * A frontend over an explicit sidecar command.
   *
   * @param language the language to read
   * @param command the command that starts the sidecar, or null to look for it
   */
  public ArktsTacFrontend(SourceLanguage language, List<String> command) {
    this.language = language == null ? SourceLanguage.ARKTS : language;
    this.command = command == null ? null : List.copyOf(command);
  }

  @Override
  public SourceLanguage language() {
    return language;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("ets");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // A construct that could not be lowered downgrades ITS function to
    // SOURCE_LOWERED, and that is decided per function by the sidecar.
    return IrPrecision.SOURCE_FULL;
  }

  @Override
  public boolean requiresClasspath() {
    // The ArkTS compiler resolves types against the project's own files, the
    // SDK's declarations and its dependencies -- the source tree, not a JVM
    // classpath. Asking for one would ask the caller for something the
    // compiler never reads.
    return false;
  }

  /**
   * Parses a source file without a compile context.
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
    if (path == null) {
      // The path is what the sidecar is asked for. Without one there is
      // nothing to ask about, and saying so is better than a failure that
      // looks like a crash.
      String message = "the file has no path, so the sidecar cannot be asked for it";
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem("<file>", "PARSE_PROBLEM", message, null, -1, -1));
      return new ParseResult(List.of(), problems, defaultPrecision());
    }
    try {
      if (ArktsProjectDiscovery.isDeclarationFile(path.getFileName().toString())) {
        // A declaration file holds signatures and no executable body, so
        // there is nothing in it for a dataflow finding to be about.
        String message =
            "this file is a declaration file: it declares the shape of other modules and holds no "
                + "executable body, so it was not analysed";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "DECLARATION_FILE", -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      ArktsProjectDiscovery.Project project = projectOf(path, ctx);
      if (project.files().isEmpty()) {
        String message = "the project holds no ArkTS source file";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "NO_SOURCES", -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      if (project.truncated()) {
        String message =
            "the project holds more than "
                + ArktsProjectDiscovery.MAX_FILES
                + " source files; only the first "
                + ArktsProjectDiscovery.MAX_FILES
                + " were given to the compiler, so the types of the others may be unknown";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "TRUNCATED_PROJECT", -1, -1));
      }

      reportContentDifference(path, source, problems);

      if (!ensureSidecar(problems)) {
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      ensureProject(project, path, problems);
      if (!projectOpen) {
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      Json.JsonObject response =
          request(
              "translate",
              request -> request.put("path", pathKey(path)).put("language", language.name()));
      String incompatible = SidecarProtocol.incompatibility(response);
      if (incompatible != null) {
        String message = "the ArkTS sidecar is not compatible with this build: " + incompatible;
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "PROTOCOL_MISMATCH", -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      if (!response.getBoolean("ok", false)) {
        String error = response.getString("error");
        String message = "the ArkTS sidecar could not translate the file: " + error;
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, null, -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      // The problems that belong to the FILE rather than to one function: a
      // file that did not parse is a partial file, and the partial file is
      // still analysed -- dropping it would hide every taint in it.
      reportFileProblems(path, response, problems);

      List<TacFunction> functions = new ArrayList<>();
      Json.JsonArray array = response.getArray("functions");
      if (array != null) {
        for (int i = 0; i < array.size(); i++) {
          Json.JsonObject function = array.getObject(i);
          if (function != null) {
            functions.add(
                SidecarTacMapper.toFunction(function, path, ctx, language, structuredProblems));
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
          "ArkTS parse failure in "
              + path
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              path.toString(), "PARSE_PROBLEM", message, ex.getClass().getSimpleName(), -1, -1));
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
   * Report the problems the sidecar reported for the FILE.
   *
   * @param path the file
   * @param response the sidecar's response
   * @param problems the problems of the current parse
   */
  private void reportFileProblems(Path path, Json.JsonObject response, List<String> problems) {
    Json.JsonArray array = response.getArray("problems");
    if (array == null) {
      return;
    }
    for (int i = 0; i < array.size(); i++) {
      Json.JsonObject problem = array.getObject(i);
      if (problem == null) {
        continue;
      }
      String message = problem.getString("message");
      if (message == null || message.isBlank()) {
        message = "the file could not be analysed completely";
      }
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              path.toString(),
              problem.getString("kind") == null ? "PARSE_PROBLEM" : problem.getString("kind"),
              message,
              problem.getString("nodeClass"),
              problem.getInt("line", -1),
              problem.getInt("column", -1)));
    }
  }

  /**
   * Report a file whose content on disk differs from the content that was given to the frontend.
   *
   * <p>The compiler reads the file itself, so the analysis is of the file <b>on disk</b>. A caller
   * that passes a different text -- an unsaved buffer, a generated file -- is analysing something
   * other than what it handed over, and that has to be said out loud.
   *
   * @param path the file
   * @param source the text the caller gave
   * @param problems the problems of the current parse
   */
  private void reportContentDifference(Path path, String source, List<String> problems) {
    if (path == null) {
      return;
    }
    String onDisk = SidecarTacMapper.readTextQuietly(path);
    if (onDisk == null || onDisk.equals(source)) {
      return;
    }
    String message =
        "the file's content on disk differs from the content that was given to the frontend; the "
            + "analysis used the content on disk, because the compiler reads the file itself";
    problems.add(message);
    structuredProblems.add(
        new TacProgram.FunctionProblem(
            path.toString(), "PARSE_PROBLEM", message, "CONTENT_MISMATCH", -1, -1));
  }

  /**
   * The project a file belongs to.
   *
   * <p>The discovery is remembered per root: a scan reads every file of a project, and walking the
   * tree again for each of them would make the scan quadratic in the size of the project.
   *
   * @param path the file
   * @param ctx the compile context
   * @return the project
   */
  private ArktsProjectDiscovery.Project projectOf(Path path, CompileContext ctx) {
    Path root = ArktsProjectDiscovery.rootOf(path, ctx);
    if (root == null) {
      return ArktsProjectDiscovery.Project.empty();
    }
    return projects.computeIfAbsent(root, key -> ArktsProjectDiscovery.discover(path, ctx));
  }

  /**
   * Ensure the sidecar is running.
   *
   * @param problems the problems of the current parse
   * @return true when the sidecar is ready
   */
  private boolean ensureSidecar(List<String> problems) {
    if (sidecar != null && sidecar.isAlive()) {
      return true;
    }
    List<String> effective = command;
    if (effective == null) {
      Path script = ArktsSidecarClient.locateScript(null);
      if (script == null) {
        String message =
            "the ArkTS sidecar was not found: it is expected at src/main.mjs under an arkts-sidecar"
                + " directory, and the PLUMB_ARKTS_SIDECAR environment variable does not name one"
                + " either";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                "<project>", "PARSE_PROBLEM", message, "MISSING_SIDECAR", -1, -1));
        return false;
      }
      Path node = ArktsSidecarClient.locateNode();
      if (node == null) {
        String message =
            "no Node runtime was found: the ArkTS sidecar runs on Node, and the PLUMB_NODE "
                + "environment variable does not name one either";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                "<project>", "PARSE_PROBLEM", message, "MISSING_RUNTIME", -1, -1));
        return false;
      }
      effective = ArktsSidecarClient.command(node, script);
    }
    try {
      sidecar = ArktsSidecarClient.launch(effective, null);
      return true;
    } catch (IOException ex) {
      String message = "the ArkTS sidecar could not be started: " + ex.getMessage();
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "MISSING_SIDECAR", -1, -1));
      return false;
    }
  }

  /**
   * Ensure the sidecar holds the project.
   *
   * <p>The project is opened once and reused. It is reopened when the project changes, and when the
   * file being parsed was not part of it -- a file the compiler never opened cannot be translated
   * out of it.
   *
   * @param project the project
   * @param path the file being parsed
   * @param problems the problems of the current parse
   * @throws IOException when the sidecar could not be reached
   */
  private void ensureProject(
      ArktsProjectDiscovery.Project project, Path path, List<String> problems) throws IOException {
    String projectId = "arkts:" + project.root();
    String key = path == null ? null : pathKey(path);
    boolean stale =
        !projectOpen
            || !projectId.equals(openProjectId)
            || (key != null && !openProjectFiles.contains(key));
    if (!stale) {
      return;
    }

    Json.JsonArray sources = new Json.JsonArray();
    for (Path file : project.files()) {
      sources.add(new Json.JsonObject().put("path", pathKey(file)));
    }
    // The file being parsed is always included, even when it is outside the
    // project's tree: a single-file scan is a legitimate case, and a file the
    // compiler never opened cannot be translated.
    if (path != null && !openProjectFiles.contains(pathKey(path))) {
      sources.add(new Json.JsonObject().put("path", pathKey(path)));
    }

    Json.JsonObject request =
        SidecarProtocol.stamp(
            new Json.JsonObject()
                .put("op", "openProject")
                .put("projectId", projectId)
                .put("sourceRoot", project.root() == null ? null : project.root().toString())
                .put("sources", sources));

    Json.JsonObject response = exchange(request);
    String incompatible = SidecarProtocol.incompatibility(response);
    if (incompatible != null) {
      String message = "the ArkTS sidecar is not compatible with this build: " + incompatible;
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "PROTOCOL_MISMATCH", -1, -1));
      return;
    }
    if (!response.getBoolean("ok", false)) {
      String error = response.getString("error");
      String message = "the ArkTS sidecar could not open the project: " + error;
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem("<project>", "PARSE_PROBLEM", message, null, -1, -1));
      return;
    }

    projectOpen = true;
    openProjectId = projectId;
    openProjectFiles.clear();
    for (Path file : project.files()) {
      openProjectFiles.add(pathKey(file));
    }
    if (path != null) {
      openProjectFiles.add(pathKey(path));
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
      throw new IOException("the ArkTS sidecar sent a malformed response: " + ex.getMessage());
    }
  }

  /** A file's path, as the key of the open project and as the name on the wire. */
  private static String pathKey(Path path) {
    return SidecarTacMapper.pathKey(path);
  }

  @Override
  public void close() {
    if (sidecar != null) {
      sidecar.close();
      sidecar = null;
    }
    projectOpen = false;
    openProjectId = null;
    openProjectFiles.clear();
    projects.clear();
  }
}
