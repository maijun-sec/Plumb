package org.plumb.tac.bytecode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.plumb.tac.bytecode.jimple.JimpleToTacConverter;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.frontend.SootClassSource;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.Body;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.views.View;
import sootup.java.bytecode.frontend.inputlocation.ArchiveBasedAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;

/**
 * The entry point for {@code .jar} / {@code .class} analysis. Given a file path (or a directory),
 * it spins up a SootUp {@link JavaView} over the artifact, walks the class set, and emits a {@link
 * TacProgram} per class with one {@link TacFunction} per method.
 *
 * <p>This is deliberately a thin wrapper around the SootUp 3.0.0 bytecode frontend: the heavy
 * lifting (ASM-based bytecode reading, Jimple conversion, type assignment, SSA formation) is done
 * by {@code AsmJavaClassProvider} + {@code BytecodeBodyInterceptors.Default} that the view installs
 * automatically. We only need to:
 *
 * <ol>
 *   <li>Open the .jar / directory as a {@link View}.
 *   <li>Enumerate the classes via {@code view.getClasses()}.
 *   <li>For each class, iterate its methods and call {@code method.getBody()} to materialize the
 *       Jimple {@link Body}, then run {@link JimpleToTacConverter}.
 *   <li>Wrap the resulting functions into a {@link TacProgram} per class with the right {@code
 *       typeName()} so downstream consumers (analysis context, MergedProgram) can resolve
 *       cross-class identifiers.
 * </ol>
 *
 * <p>Errors at any step are logged and the offending class is skipped ? we never want a malformed
 * .class to bring down the whole scan.
 *
 * <p><b>Multi-input support.</b> The {@link #BytecodeAnalyzer(List)} constructor accepts a list of
 * paths and builds a single {@link JavaView} with all of them as input locations. The resulting
 * view sees classes from every path, so call resolution across the paths works through the standard
 * {@code CallResolver} (a callee class loaded from path 2 is resolvable from a caller in path 1).
 * This is the fix for the previously doc-noted "commons-text references commons-lang3 silently fall
 * through" limitation: the user just needs to pass both .jars as inputs to a single {@code
 * BytecodeAnalyzer} call.
 *
 * <p><b>Per-class source path.</b> The previous limitation was that the {@code
 * TacProgram.sourcePath} for a class was always the first input's path (we couldn't tell which
 * input each class came from). Now each class's source path is resolved to the actual artifact it
 * came from:
 *
 * <ul>
 *   <li>For a {@code .jar} / {@code .war} / {@code .ear} / {@code .zip} input: the source path is
 *       the archive file itself (e.g. {@code lib.jar}).
 *   <li>For a directory input: the source path is the actual {@code .class} file (e.g. {@code
 *       D:/src/com/foo/Lib.class}), which matches the per-{@code .java} granularity of the AST
 *       path.
 *   <li>For a single {@code .class} input: the source path is the {@code .class} file itself.
 * </ul>
 *
 * Findings now report the correct artifact for each class, so cross-jar scans tell the user which
 * {@code .jar} each finding came from.
 */
public final class BytecodeAnalyzer {

  private static final Logger LOG = LoggerFactory.getLogger(BytecodeAnalyzer.class);

  private final List<Path> inputs;
  private final SourceType sourceType;

  /**
   * Per-input {@link AnalysisInputLocation} → user-supplied input path. Used by {@link
   * #convertClass} to resolve the actual source path for a class (the {@code .jar} it came from, or
   * the {@code .class} file in a directory). Built lazily in {@link #openView()} so the same path →
   * location identity is used for the map and for the view.
   */
  private final java.util.Map<AnalysisInputLocation, Path> locationToInput =
      new java.util.HashMap<>();

  public BytecodeAnalyzer(Path input) {
    this(List.of(input), SourceType.Application);
  }

  public BytecodeAnalyzer(Path input, SourceType sourceType) {
    this(List.of(input), sourceType);
  }

  /**
   * Multi-input constructor. The resulting analyzer sees classes from all input paths in a single
   * SootUp {@link View}, so cross-input call resolution works.
   *
   * <p>Duplicate FQNs across inputs: SootUp returns the first-found class for {@code
   * view.getClass(FQN)} (the order is the input list order). A {@code WARN} is logged for each
   * duplicate FQN so the user can reorder inputs to control which version wins.
   */
  public BytecodeAnalyzer(List<Path> inputs) {
    this(inputs, SourceType.Application);
  }

  public BytecodeAnalyzer(List<Path> inputs, SourceType sourceType) {
    // Defensive copy + dedup; preserve order so the "first
    // input wins for FQN conflicts" rule is deterministic.
    Set<Path> dedup = new LinkedHashSet<>(inputs);
    this.inputs = List.copyOf(dedup);
    this.sourceType = sourceType;
  }

  /**
   * The configured inputs. Used by {@code ScanRunner} for reporting the artifact set to the user
   * (e.g. for the per-class source path attribute on findings).
   */
  public List<Path> inputs() {
    return inputs;
  }

  /**
   * Analyze the configured input(s) and emit one {@link TacProgram} per class. The result is
   * suitable for direct consumption by the existing DFA engine (the output uses the same {@link
   * TacFunction} shape as the AST-driven translator).
   */
  public List<TacProgram> analyze() {
    for (Path input : inputs) {
      if (!Files.exists(input)) {
        throw new IllegalArgumentException("Input does not exist: " + input);
      }
    }
    List<TacProgram> out = new ArrayList<>();
    View view = openView();
    // View is a stateless facade over the input location; we
    // don't need to close it explicitly. (SootUp 3.0.0's View
    // interface doesn't expose close; the underlying
    // AnalysisInputLocation does, but the view's cache
    // delegate handles cleanup via weak references.)
    // Detect FQN conflicts across inputs. SootUp
    // returns first-found, but we want to surface this to
    // the user (the order of inputs matters and they should
    // know).
    java.util.Set<String> seenFqns = new java.util.HashSet<>();
    for (SootClass cls : view.getClasses().toList()) {
      String fqn = cls.getType().getFullyQualifiedName();
      if (!seenFqns.add(fqn)) {
        LOG.warn("Duplicate FQN {} across inputs; first input wins", fqn);
      }
      try {
        TacProgram program = convertClass(view, cls, fqn);
        if (program != null) {
          out.add(program);
        }
      } catch (RuntimeException ex) {
        LOG.warn("Failed to convert class {}: {}", cls.getName(), ex.getMessage());
      }
    }
    return out;
  }

  /**
   * Build a single SootUp {@link JavaView} that spans all configured inputs. Each input gets the
   * appropriate {@link AnalysisInputLocation} (directory / .class / archive) based on its path
   * shape. The map also populates {@link #locationToInput} so {@link #convertClass} can resolve
   * each class to the user-supplied input that actually contributed it.
   */
  private View openView() {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    for (Path input : inputs) {
      AnalysisInputLocation loc = locationFor(input);
      locations.add(loc);
      // Stash the location → user-input mapping so we
      // can later identify which user input a class came
      // from. Identity-based map (AnalysisInputLocation uses
      // default Object equality) — each locationFor() call
      // returns a fresh instance, but the same instance is
      // used for the view and the map, so the lookup
      // succeeds.
      locationToInput.put(loc, input);
    }
    return new JavaView(locations);
  }

  /**
   * Map a single input path to the matching {@link AnalysisInputLocation} (helper extracted from
   * the previous single-input {@link #openView()}).
   */
  private AnalysisInputLocation locationFor(Path input) {
    if (Files.isDirectory(input)) {
      // PathBasedAnalysisInputLocation.create auto-detects
      // directory vs .jar vs .class and dispatches to the
      // right concrete subclass.
      return PathBasedAnalysisInputLocation.create(input, sourceType);
    }
    if (input.toString().endsWith(".class")) {
      // SootUp's public API is .jar- or directory-oriented.
      // For a single .class file we wrap it in a parent
      // directory view.
      Path parent = input.toAbsolutePath().getParent();
      if (parent == null) parent = input;
      return PathBasedAnalysisInputLocation.create(parent, sourceType);
    }
    // .jar / .war / .ear — archive location.
    return new ArchiveBasedAnalysisInputLocation(input, sourceType);
  }

  private TacProgram convertClass(View view, SootClass cls, String fqn) {
    // Resolve the actual source path for this class.
    // SootUp 3.0.0 exposes this via the per-class
    // {@link SootClassSource} (cls.getClassSource()): we ask
    // the source which {@link AnalysisInputLocation} it came
    // from, look up the corresponding user input, and then
    // shape the path based on whether that input is an
    // archive or a directory.
    //
    // Previously, this was always {@code inputs.get(0)} —
    // so a class from {@code lib.jar} (the second input) had
    // its findings attributed to {@code app.jar} (the first
    // input). Now the attribution is correct.
    Path sourcePath = resolveClassSourcePath(cls);
    TacProgram program = new TacProgram(sourcePath, fqn);
    Set<? extends SootMethod> methods = cls.getMethods();
    for (SootMethod method : methods) {
      try {
        if (method.isAbstract() || method.isNative()) continue;
        Body body = method.getBody();
        if (body == null) continue;
        TacFunction fn = new JimpleToTacConverter(body).convert(body);
        program.addFunction(fn);
      } catch (RuntimeException ex) {
        LOG.warn("Failed to convert method {}.{}: {}", fqn, method.getName(), ex.getMessage());
      }
    }
    if (program.functions().isEmpty()) {
      LOG.debug("Class {} produced no TAC functions (library/synthetic?)", fqn);
      return null;
    }
    return program;
  }

  /**
   * Resolve the actual source path for a class. The path is shaped by the kind of input the class
   * came from:
   *
   * <ul>
   *   <li><b>Archive input</b> ({@code .jar} / {@code .war} / {@code .ear} / {@code .zip}): report
   *       the archive file path (e.g. {@code lib.jar}). SootUp's per-class {@code getSourcePath()}
   *       returns the path inside the archive (e.g. {@code com/foo/Lib.class}), which is not what
   *       the user wants to see in findings ? they want to know which {@code .jar} the class came
   *       from.
   *   <li><b>Directory input</b>: report the actual {@code .class} file path (e.g. {@code
   *       D:/src/com/foo/Lib.class}). This matches the per-{@code .java} granularity of the AST
   *       path: each class has its own file column.
   *   <li><b>Single {@code .class} input</b>: report the {@code .class} file path (the class IS the
   *       input).
   *   <li><b>Unknown location</b> (defensive): fall back to the first input (or {@code "bytecode"}
   *       if no inputs are configured).
   * </ul>
   *
   * <p>The mapping from {@link AnalysisInputLocation} to user input is built in {@link #openView()}
   * and is the same identity that the view uses, so the lookup is exact.
   */
  private Path resolveClassSourcePath(SootClass cls) {
    try {
      SootClassSource src = cls.getClassSource();
      if (src == null) {
        return firstInputOrDefault();
      }
      AnalysisInputLocation loc = src.getAnalysisInputLocation();
      Path userInput = loc == null ? null : locationToInput.get(loc);
      if (userInput == null) {
        // Defensive: SootUp's view shouldn't include
        // classes from locations we didn't register,
        // but guard against it (e.g. SootUp 3.0.0
        // might inject default JDK classes).
        return firstInputOrDefault();
      }
      if (Files.isDirectory(userInput)) {
        // Directory input: each .class file is its own
        // class. Use the per-class source path (the
        // actual .class file inside the directory).
        // This gives the user per-class file
        // attribution, matching the AST .java
        // granularity.
        Path classFile = src.getSourcePath();
        if (classFile != null) return classFile;
      }
      // Archive input or single .class input: the user
      // input is the right thing to report. For an
      // archive it's the .jar file; for a single .class
      // it's the .class file (and the locationToInput
      // mapping returns the user input verbatim).
      return userInput;
    } catch (RuntimeException ex) {
      // Defensive: getClassSource() can throw on
      // synthetic / library classes that the SootUp
      // view returns without a real source. Fall back
      // rather than fail the whole scan.
      LOG.debug("getClassSource failed for {}: {}", cls.getName(), ex.getMessage());
      return firstInputOrDefault();
    }
  }

  private Path firstInputOrDefault() {
    return inputs.isEmpty() ? Path.of("bytecode") : inputs.get(0);
  }

  /**
   * Helper for tests / one-off use: load a {@code .class} file directly (not wrapped in a {@code
   * .jar}) and produce a single {@link TacProgram}. The SootUp API doesn't expose a single-file
   * view, so we use a directory-based view over the parent directory and find the matching class by
   * simple name.
   */
  public static Optional<TacProgram> analyzeSingleClass(Path classFile) throws IOException {
    if (!Files.exists(classFile) || !classFile.toString().endsWith(".class")) {
      return Optional.empty();
    }
    Path parent = classFile.toAbsolutePath().getParent();
    if (parent == null) parent = classFile;
    View view = new JavaView(PathBasedAnalysisInputLocation.create(parent, SourceType.Application));
    String fileName = classFile.getFileName().toString();
    String simpleName = fileName.substring(0, fileName.length() - ".class".length());
    for (SootClass cls : view.getClasses().toList()) {
      String clsName = cls.getType().getFullyQualifiedName();
      if (clsName.endsWith("." + simpleName) || clsName.equals(simpleName)) {
        TacProgram program = new BytecodeAnalyzer(parent).convertClass(view, cls, clsName);
        return Optional.ofNullable(program);
      }
    }
    return Optional.empty();
  }
}
