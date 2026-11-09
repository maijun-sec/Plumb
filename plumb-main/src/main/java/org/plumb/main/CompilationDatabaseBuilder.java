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
package org.plumb.main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.DefaultCompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.csharp.CSharpProjectProfile;
import org.plumb.tac.frontend.csharp.CSharpTacFrontend;
import org.plumb.tac.frontend.go.GoProjectProfile;
import org.plumb.tac.frontend.go.GoTacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * Builds the TAC from a compilation database.
 *
 * <h2>Why the database and not the captures</h2>
 *
 * <p>A compilation database is the interchange format between a build and an analyser: for every
 * compilation unit, the exact command that compiled it. A build can produce it, another tool can
 * produce it, and the analyser reads whichever it was given. Reading one producer's private capture
 * files instead would tie the analyser to that producer, which is exactly the coupling the format
 * exists to remove.
 *
 * <h2>What the database gives each language</h2>
 *
 * <ul>
 *   <li>the <b>JVM</b> languages: the classpath, the module path, the output directory, the
 *       encoding and the language level -- read into the capture shape the JVM frontends already
 *       consume;
 *   <li><b>C#</b>: the reference assemblies, the preprocessor symbols, the language version, the
 *       nullable context and the source generators -- the five inputs a Roslyn compilation needs
 *       and cannot guess;
 *   <li><b>Go</b>: the packages, their import paths, their language versions and the export data of
 *       their dependencies -- the inputs a Go type checker needs and cannot invent.
 * </ul>
 *
 * <h2>Why the module graph is recovered for C#</h2>
 *
 * <p>A project reference in the build file becomes an ordinary reference assembly on the compiler's
 * command line, so the dependency graph of a solution is visible in the database. The graph is
 * recovered by matching each project's references against the other projects' outputs.
 *
 * <h2>Totality</h2>
 *
 * <p>One failing file does not abort the build. Every failure becomes a problem, and whatever could
 * be translated is returned.
 */
public final class CompilationDatabaseBuilder {

  private CompilationDatabaseBuilder() {}

  /**
   * Build the TAC from a compilation database.
   *
   * @param entries the database's entries
   * @return the programs and the problems
   */
  public static CaptureTacBuilder.BuildResult build(List<CompilationDatabaseEntry> entries) {
    Map<String, TacProgram> programs = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    if (entries == null || entries.isEmpty()) {
      return new CaptureTacBuilder.BuildResult(programs, problems);
    }

    List<CompilationDatabaseEntry> csharpEntries = new ArrayList<>();
    List<CompilationDatabaseEntry> goEntries = new ArrayList<>();
    List<CompilationDatabaseEntry> jvmEntries = new ArrayList<>();
    for (CompilationDatabaseEntry entry : entries) {
      if (CsharpCompilePlan.isCsharpCommand(entry)) {
        csharpEntries.add(entry);
      } else if (GoCompilePlan.isGoCommand(entry)) {
        goEntries.add(entry);
      } else if (JavacCompilePlan.isJvmCommand(entry)) {
        jvmEntries.add(entry);
      }
    }

    if (!csharpEntries.isEmpty()) {
      buildCsharp(csharpEntries, programs, problems);
    }
    if (!goEntries.isEmpty()) {
      buildGo(goEntries, programs, problems);
    }
    if (!jvmEntries.isEmpty()) {
      List<CaptureEntry> captures = new ArrayList<>();
      for (CompilationDatabaseEntry entry : jvmEntries) {
        try {
          captures.add(JavacCompilePlan.captureOf(entry));
        } catch (RuntimeException e) {
          // ONE command that cannot be read must not cost the WHOLE capture.
          //
          // A capture is a list of commands, and the list is the only record
          // of what the build did. A reader that throws on the first command
          // it does not understand turns "this one command was unusual" into
          // "the project has no TAC at all" -- and the scan reports success.
          // So the command becomes a problem, and the other commands are still
          // read.
          problems.add(
              "the compilation command could not be read: "
                  + e.getClass().getSimpleName()
                  + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
      }
      CaptureTacBuilder.BuildResult jvm = CaptureTacBuilder.build(captures);
      programs.putAll(jvm.programs());
      problems.addAll(jvm.problems());
    }
    return new CaptureTacBuilder.BuildResult(programs, problems);
  }

  /**
   * Build the TAC of a C# solution.
   *
   * @param entries the C# entries
   * @param programs the programs, to fill
   * @param problems the problems, to fill
   */
  private static void buildCsharp(
      List<CompilationDatabaseEntry> entries,
      Map<String, TacProgram> programs,
      List<String> problems) {
    List<CsharpCompilePlan.Project> projects = CsharpCompilePlan.projectsOf(entries);
    if (projects.isEmpty()) {
      return;
    }

    // The module names come from the assemblies the projects produce, and the
    // outputs are what the dependency edges are matched against.
    Map<String, Path> outputs = new LinkedHashMap<>();
    Map<String, CsharpCompilePlan.Project> byName = new LinkedHashMap<>();
    for (CsharpCompilePlan.Project project : projects) {
      String name = moduleNameOf(project);
      if (byName.containsKey(name)) {
        continue;
      }
      byName.put(name, project);
      if (project.output() != null) {
        outputs.put(name, project.output());
      }
    }

    Map<String, CSharpProjectProfile> profiles = new LinkedHashMap<>();
    List<CompileContext.Module> modules = new ArrayList<>();
    List<Path> allReferences = new ArrayList<>();
    for (Map.Entry<String, CsharpCompilePlan.Project> entry : byName.entrySet()) {
      CsharpCompilePlan.Project project = entry.getValue();
      profiles.put(entry.getKey(), project.profile());
      allReferences.addAll(project.profile().references());
      modules.add(
          new CompileContext.Module(
              entry.getKey(),
              sourceRootOf(project),
              project.sourceFiles(),
              project.profile().references(),
              project.profile().referencedProjects(outputs),
              project.profile().langVersion()));
    }

    try (DefaultCompileContext context =
        new DefaultCompileContext(modules, distinct(allReferences))) {
      try (CSharpTacFrontend frontend = new CSharpTacFrontend(null, profiles)) {
        for (CsharpCompilePlan.Project project : byName.values()) {
          for (Path file : project.sourceFiles()) {
            translate(frontend, file, context, programs, problems);
          }
        }
      }
    }
  }

  /**
   * Build the TAC of a Go project.
   *
   * @param entries the Go entries
   * @param programs the programs, to fill
   * @param problems the problems, to fill
   */
  private static void buildGo(
      List<CompilationDatabaseEntry> entries,
      Map<String, TacProgram> programs,
      List<String> problems) {
    GoProjectProfile profile = GoCompilePlan.projectOf(entries);
    if (profile.isEmpty()) {
      problems.add("the Go build was captured but no package could be read from it");
      return;
    }

    // The build's export data is the best source of a dependency's types, and
    // it is gone in the normal case: the toolchain writes it into the build's
    // temporary directory and deletes that directory when the build ends. So
    // its absence is reported -- but it is NOT a problem of THIS stage,
    // because the build succeeded and every file was read. The report belongs
    // to the analysis of each file, and the frontend raises it there.
    //
    // Putting it in this stage's problem list would make the build stage
    // report a failure for every Go project, which is the same as reporting
    // no failure at all: the exit code would stop meaning anything.

    List<CompileContext.Module> modules = new ArrayList<>();
    for (Path root : profile.moduleRoots()) {
      modules.add(
          new CompileContext.Module(
              root.getFileName() == null ? "go" : root.getFileName().toString(),
              root,
              filesUnder(root, profile),
              List.of(),
              List.of(),
              null));
    }
    if (modules.isEmpty()) {
      modules.add(
          new CompileContext.Module(
              "go", Path.of("."), profile.allFiles(), List.of(), List.of(), null));
    }

    try (DefaultCompileContext context = new DefaultCompileContext(modules, List.of())) {
      try (GoTacFrontend frontend = new GoTacFrontend(null, profile)) {
        for (Path file : profile.allFiles()) {
          translate(frontend, file, context, programs, problems);
        }
      }
    }
  }

  /**
   * Translate one file with a frontend.
   *
   * @param frontend the frontend
   * @param file the file
   * @param context the compile context
   * @param programs the programs, to fill
   * @param problems the problems, to fill
   */
  private static void translate(
      TacFrontend frontend,
      Path file,
      CompileContext context,
      Map<String, TacProgram> programs,
      List<String> problems) {
    if (file == null || !Files.isRegularFile(file)) {
      return;
    }
    String key = file.toAbsolutePath().normalize().toString();
    if (programs.containsKey(key)) {
      // Two compiles of the same file are the same file. The second one would
      // produce the same program, and storing it twice would make the store's
      // size a lie.
      return;
    }
    String source;
    try {
      source = Files.readString(file);
    } catch (IOException e) {
      problems.add(file + ": " + e.getMessage());
      return;
    }
    try {
      TacFrontend.ParseResult parsed = frontend.parse(source, file, context);
      if (parsed == null) {
        return;
      }
      if (parsed.functions().isEmpty() && parsed.problems().isEmpty()) {
        return;
      }
      TacProgram program = new TacProgram(file, "");
      for (TacFunction function : parsed.functions()) {
        program.addFunction(function);
      }
      for (String problem : parsed.problems()) {
        program.addProgramProblem(
            new TacProgram.FunctionProblem(
                file.getFileName().toString(), "FRONTEND", problem, null, -1, -1));
      }
      programs.put(key, program);
    } catch (RuntimeException e) {
      problems.add(file + ": " + e.getMessage());
    }
  }

  /**
   * The module name of a C# project.
   *
   * <p>It is the name of the assembly the project produces, because that is what the build calls
   * the project -- and because a project reference names that same assembly, which is how the
   * dependency edge is found.
   *
   * @param project the project
   * @return the name
   */
  private static String moduleNameOf(CsharpCompilePlan.Project project) {
    Path output = project.output();
    if (output != null && output.getFileName() != null) {
      String name = output.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return dot > 0 ? name.substring(0, dot) : name;
    }
    Path root = sourceRootOf(project);
    return root.getFileName() == null ? "csharp" : root.getFileName().toString();
  }

  /**
   * The source root of a C# project: the directory that holds its sources.
   *
   * <p>It is the deepest directory that contains every one of the project's files, so that a file
   * is attributed to the right project.
   *
   * @param project the project
   * @return the root
   */
  private static Path sourceRootOf(CsharpCompilePlan.Project project) {
    List<Path> files = project.sourceFiles();
    if (files.isEmpty()) {
      return project.directory() == null ? Path.of(".") : project.directory();
    }
    Path common = files.get(0).toAbsolutePath().normalize().getParent();
    for (Path file : files) {
      Path parent = file.toAbsolutePath().normalize().getParent();
      if (parent == null) {
        continue;
      }
      while (common != null && !parent.startsWith(common)) {
        common = common.getParent();
      }
    }
    return common == null ? Path.of(".") : common;
  }

  /**
   * The files of a Go module.
   *
   * @param root the module's root
   * @param profile the project
   * @return the files
   */
  private static List<Path> filesUnder(Path root, GoProjectProfile profile) {
    List<Path> out = new ArrayList<>();
    for (Path file : profile.allFiles()) {
      if (file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
        out.add(file);
      }
    }
    return out;
  }

  /**
   * The distinct entries of a path list.
   *
   * @param paths the paths
   * @return the distinct ones, in order
   */
  private static List<Path> distinct(List<Path> paths) {
    Set<Path> out = new LinkedHashSet<>();
    for (Path path : paths) {
      if (path != null) {
        out.add(path);
      }
    }
    return List.copyOf(out);
  }
}
