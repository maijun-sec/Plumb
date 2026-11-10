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
package org.plumb.tac.frontend.python;

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
 * The Python frontend: source text to {@link TacFunction}s, through CPython's own parser.
 *
 * <h2>How it works</h2>
 *
 * <p>Plumb is a JVM product and Python's parser is not a JVM program -- it is part of the reference
 * implementation, and it is what decides what Python 3 means. Writing a second implementation of it
 * in the JVM would mean a name that resolves differently, hence a different callee signature, hence
 * a sink family that does not match. So this frontend drives a long-lived <b>Python sidecar</b>
 * over stdio instead, and the sidecar drives the {@code ast} module of the interpreter it runs on.
 *
 * <h2>Why the sidecar is PROJECT-SCOPED</h2>
 *
 * <p>A Python file's module name is its path under the project's root, and a callee's owner is the
 * module that declares it. So the sidecar is told where the project is, once, and every file is
 * translated with that root in mind. The project is opened once and reused for every file, which is
 * also what makes the approach viable: a scan reads every file of a project, and re-opening it for
 * each of them would make the scan quadratic in the size of the project.
 *
 * <h2>Why the interpreter's version is CHECKED</h2>
 *
 * <p>The sidecar parses with Python 3's grammar. A Python 2 interpreter would read a Python 3 file
 * as a broken one -- or worse, read it as a Python 2 file -- so the frontend asks the sidecar which
 * interpreter it is running on and refuses one that is not a Python 3. The version is checked at
 * run time rather than assumed, because a PATH lookup cannot tell a Python 2 from a Python 3 by its
 * name.
 *
 * <h2>Why the parser reads the files itself</h2>
 *
 * <p>The sidecar reads the source from disk. It has to: the text a caller passes in may not be the
 * text on disk, and a file whose text was replaced would be a <b>different program</b>. So the text
 * the caller passes in is checked against the text on disk, and a difference is <b>reported</b>:
 * the analysis used the file on disk, and saying so is the difference between a known lower bound
 * and a silent surprise.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as a {@link
 * TacProgram.FunctionProblem} and yields whatever could be parsed. A missing Python runtime or a
 * missing sidecar is reported as an explicit error -- <b>never</b> as "no findings".
 *
 * @see PythonSidecarClient
 */
public final class PythonTacFrontend implements TacFrontend, AutoCloseable {

  /** The problems of the CURRENT parse, in the IR's structured form. */
  private final List<TacProgram.FunctionProblem> structuredProblems = new ArrayList<>();

  /**
   * The command that starts the sidecar, or null to look for it.
   *
   * <p>An explicit command is how a caller pins the interpreter and the script; it is also how the
   * tests drive the frontend against a stand-in sidecar.
   */
  private final List<String> command;

  /** The projects this frontend has opened, keyed by their root. */
  private final Map<Path, PythonProjectDiscovery.Project> projects = new HashMap<>();

  private PythonSidecarClient sidecar;
  private boolean projectOpen;
  private String openProjectId;
  private final Set<String> openProjectFiles = new HashSet<>();
  private boolean interpreterChecked;

  /** A frontend that finds the sidecar in the default places. */
  public PythonTacFrontend() {
    this(null);
  }

  /**
   * A frontend over an explicit sidecar command.
   *
   * @param command the command that starts the sidecar, or null to look for it
   */
  public PythonTacFrontend(List<String> command) {
    this.command = command == null ? null : List.copyOf(command);
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.PYTHON3;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("py");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // A construct that could not be lowered downgrades ITS function to
    // SOURCE_LOWERED, and that is decided per function by the sidecar.
    return IrPrecision.SOURCE_FULL;
  }

  @Override
  public boolean requiresClasspath() {
    // The parser resolves nothing against a JVM classpath: it reads Python
    // source and the standard library. Asking for one would ask the caller
    // for something the parser never reads.
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
      if (PythonProjectDiscovery.isStubFile(path.getFileName().toString())) {
        // A type stub holds signatures and no executable body, so there is
        // nothing in it for a dataflow finding to be about.
        String message =
            "this file is a type stub: it declares the shape of other modules and holds no "
                + "executable body, so it was not analysed";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "STUB_FILE", -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      PythonProjectDiscovery.Project project = projectOf(path, ctx);
      if (project.files().isEmpty()) {
        String message = "the project holds no Python source file";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "NO_SOURCES", -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      if (project.truncated()) {
        String message =
            "the project holds more than "
                + PythonProjectDiscovery.MAX_FILES
                + " source files; only the first "
                + PythonProjectDiscovery.MAX_FILES
                + " were given to the sidecar, so the module names of the others may be wrong";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "TRUNCATED_PROJECT", -1, -1));
      }

      reportContentDifference(path, source, problems);

      if (!ensureSidecar(problems)) {
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      if (!ensureInterpreterIsPython3(problems)) {
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      ensureProject(project, path, problems);
      if (!projectOpen) {
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      Json.JsonObject response =
          request(
              "translate",
              request -> request.put("path", pathKey(path)).put("language", language().name()));
      String incompatible = SidecarProtocol.incompatibility(response);
      if (incompatible != null) {
        String message = "the Python sidecar is not compatible with this build: " + incompatible;
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, "PROTOCOL_MISMATCH", -1, -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      if (!response.getBoolean("ok", false)) {
        String error = response.getString("error");
        String message = "the Python sidecar could not translate the file: " + error;
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                path.toString(), "PARSE_PROBLEM", message, null, -1, -1));
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
          "Python parse failure in "
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
   * Report a file whose content on disk differs from the content that was given to the frontend.
   *
   * <p>The sidecar reads the file itself, so the analysis is of the file <b>on disk</b>. A caller
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
    String onDisk = readTextQuietly(path);
    if (onDisk == null || onDisk.equals(source)) {
      return;
    }
    String message =
        "the file's content on disk differs from the content that was given to the frontend; the "
            + "analysis used the content on disk, because the parser reads the file itself";
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
  private PythonProjectDiscovery.Project projectOf(Path path, CompileContext ctx) {
    Path root = PythonProjectDiscovery.rootOf(path, ctx);
    if (root == null) {
      return PythonProjectDiscovery.Project.empty();
    }
    return projects.computeIfAbsent(root, key -> PythonProjectDiscovery.discover(path, ctx));
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
      Path script = PythonSidecarClient.locateScript(null);
      if (script == null) {
        String message =
            "the Python sidecar was not found: it is expected at src/main.py under a python-sidecar"
                + " directory, and the PLUMB_PYTHON_SIDECAR environment variable does not name one"
                + " either";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                "<project>", "PARSE_PROBLEM", message, "MISSING_SIDECAR", -1, -1));
        return false;
      }
      Path python = PythonSidecarClient.locatePython();
      if (python == null) {
        String message =
            "no Python runtime was found: the Python sidecar runs on Python 3, and the "
                + "PLUMB_PYTHON environment variable does not name one either";
        problems.add(message);
        structuredProblems.add(
            new TacProgram.FunctionProblem(
                "<project>", "PARSE_PROBLEM", message, "MISSING_RUNTIME", -1, -1));
        return false;
      }
      effective = PythonSidecarClient.command(python, script);
    }
    try {
      sidecar = PythonSidecarClient.launch(effective, null);
      interpreterChecked = false;
      return true;
    } catch (IOException ex) {
      String message = "the Python sidecar could not be started: " + ex.getMessage();
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "MISSING_SIDECAR", -1, -1));
      return false;
    }
  }

  /**
   * Ensure the sidecar runs on a Python 3.
   *
   * <p>The sidecar reports the interpreter it runs on, and the frontend refuses one that is not a
   * Python 3. The check is a <b>runtime</b> check rather than an assumption, because a PATH lookup
   * cannot tell a Python 2 from a Python 3 by its name -- and a Python 2 interpreter would read a
   * Python 3 file as a broken one, or worse, read it as a Python 2 file.
   *
   * @param problems the problems of the current parse
   * @return true when the interpreter is a Python 3
   */
  private boolean ensureInterpreterIsPython3(List<String> problems) throws IOException {
    if (interpreterChecked) {
      return true;
    }
    Json.JsonObject response = request("ping", request -> {});
    String incompatible = SidecarProtocol.incompatibility(response);
    if (incompatible != null) {
      String message = "the Python sidecar is not compatible with this build: " + incompatible;
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "PROTOCOL_MISMATCH", -1, -1));
      return false;
    }
    if (!response.getBoolean("ok", false)) {
      String message =
          "the Python sidecar could not answer a liveness check: " + response.getString("error");
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem("<project>", "PARSE_PROBLEM", message, null, -1, -1));
      return false;
    }
    Json.JsonObject runtime = response.getObject("runtime");
    int major = runtime == null ? -1 : runtime.getInt("major", -1);
    if (major != 3) {
      String version = runtime == null ? null : runtime.getString("version");
      String message =
          "the Python sidecar runs on Python "
              + (version == null ? "an unknown version" : version)
              + ", and Plumb analyses Python 3: a Python 2 interpreter would read a Python 3 file "
              + "as a broken one";
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "UNSUPPORTED_PYTHON_VERSION", -1, -1));
      return false;
    }
    interpreterChecked = true;
    return true;
  }

  /**
   * Ensure the sidecar holds the project.
   *
   * <p>The project is opened once and reused. It is reopened when the project changes, and when the
   * file being parsed was not part of it -- a file the sidecar never saw cannot be translated.
   *
   * @param project the project
   * @param path the file being parsed
   * @param problems the problems of the current parse
   * @throws IOException when the sidecar could not be reached
   */
  private void ensureProject(
      PythonProjectDiscovery.Project project, Path path, List<String> problems) throws IOException {
    String projectId = "python:" + project.root();
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
    // sidecar never saw cannot be translated.
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
      String message = "the Python sidecar is not compatible with this build: " + incompatible;
      problems.add(message);
      structuredProblems.add(
          new TacProgram.FunctionProblem(
              "<project>", "PARSE_PROBLEM", message, "PROTOCOL_MISMATCH", -1, -1));
      return;
    }
    if (!response.getBoolean("ok", false)) {
      String error = response.getString("error");
      String message = "the Python sidecar could not open the project: " + error;
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

  /**
   * Send a request and read the response.
   *
   * @param op the operation
   * @param fill fills the request
   * @return the response
   * @throws IOException when the sidecar could not be reached
   */
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
      throw new IOException("the Python sidecar sent a malformed response: " + ex.getMessage());
    }
  }

  /** Map one wire function into the IR. */
  private TacFunction toFunction(Json.JsonObject wire, Path path, CompileContext ctx) {
    return SidecarTacMapper.toFunction(wire, path, ctx, language(), structuredProblems);
  }

  /**
   * A file's path, as the key of the open project and as the name on the wire.
   *
   * @param path the path
   * @return the key
   */
  private static String pathKey(Path path) {
    return SidecarTacMapper.pathKey(path);
  }

  private static String readTextQuietly(Path file) {
    return SidecarTacMapper.readTextQuietly(file);
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
    interpreterChecked = false;
  }
}
