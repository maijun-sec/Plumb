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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.UncontrolledRecursionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.engine.ScannedOrigin;
import org.plumb.reporter.Finding;
import org.plumb.tac.bytecode.BytecodeAnalyzer;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * What the bytecode lane does with a construction, and what it reports.
 *
 * <p><b>Why the bytecode lane is asked about constructors at all.</b> The sink catalogue names
 * constructors -- {@code java.io.File.<init>/1} among roughly fifty others -- and the Java source
 * front end puts that name on a NEW instruction. A JAR is a first-class input: the scan routes
 * {@code .jar}, {@code .class} and {@code .war} to the bytecode analyser and runs the same checkers
 * over it, so a catalogue entry that only the source front end can reach is a rule that never fires
 * on a deployed artifact.
 *
 * <p><b>Why the answer is not obvious from the Jimple.</b> Jimple has no constructor call. It has a
 * {@code new} that allocates and a separate special-invoke that runs the constructor, so the
 * constructor's name and its arguments live on the invoke and not on the allocation. Where the name
 * lands decides whether the catalogue can match it, and the argument layout decides which argument
 * the catalogue entry names.
 *
 * <p><b>What the measurement showed.</b> The name lands on the CALL, not on the NEW. The CALL's
 * argument list carries the receiver in its first slot -- the same convention the engine already
 * uses for every instance call -- so the engine's receiver offset lines the catalogue's argument
 * index up with the argument it names. The catalogue entries are reachable.
 *
 * <p><b>The gate that was closed, and what it hid.</b> The name being reachable is not the same as
 * the rule firing. The engine analyses a function body only when its origin is in the configured
 * set, and the bytecode front end marks everything it lowers as {@link SourceOrigin#LIBRARY}: a
 * class file carries no evidence of whether it is the code under review or a dependency of it. The
 * default analysed set is {@code SOURCE} alone, so every bytecode function body was skipped before
 * a checker could look at an instruction, and a scan of a JAR reported nothing at all -- the one
 * outcome a scanner must never produce silently, because it is indistinguishable from a clean
 * artifact.
 */
@DisplayName("Taint: the bytecode lane and the constructors")
class BytecodeConstructorSinkTest {

  /**
   * A program that opens a file whose name arrived over the wire.
   *
   * <p>The name comes from a reader, and {@code readLine} is one of the input sources the engine
   * knows by name. Nothing outside the JDK is needed, so the program compiles with the compiler the
   * test already has.
   */
  private static final String SOURCE =
      """
      import java.io.BufferedReader;
      import java.io.File;
      import java.io.IOException;

      public class Constructed {
        public File open(BufferedReader reader) throws IOException {
          String name = reader.readLine();
          return new File(name);
        }
      }
      """;

  /** Compile the program and return the directory the classes landed in. */
  private static Path compile(Path directory) throws IOException {
    Path source = directory.resolve("Constructed.java");
    Files.writeString(source, SOURCE);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            System.in,
            System.out,
            System.err,
            "-d",
            directory.toString(),
            "-encoding",
            "UTF-8",
            source.toString());
    assertThat(rc).as("the program must compile").isEqualTo(0);
    return directory;
  }

  /** The programs the bytecode lane produced. */
  private static List<TacProgram> programsOf(Path directory) {
    return new BytecodeAnalyzer(List.of(directory)).analyze();
  }

  /** The functions of the given programs. */
  private static List<TacFunction> functionsOf(List<TacProgram> programs) {
    List<TacFunction> functions = new ArrayList<>();
    for (TacProgram program : programs) {
      functions.addAll(program.functions());
    }
    return functions;
  }

  /** Run the taint engine over the given functions and return the taint findings. */
  private static List<Finding> scan(List<TacFunction> functions, DfaChecker checker) {
    TacProgram program = new TacProgram(Path.of("Constructed.java"), "Constructed");
    for (TacFunction function : functions) {
      program.addFunction(function);
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) findings.add(finding);
    }
    return findings;
  }

  private static boolean hasFinding(List<Finding> findings, String callee) {
    for (Finding finding : findings) {
      if (finding.message().contains(callee)) return true;
    }
    return false;
  }

  @Test
  @DisplayName("the bytecode lane names the constructor")
  void theBytecodeLaneNamesTheConstructor(@TempDir Path directory) throws IOException {
    Path classes = compile(directory);
    List<TacFunction> functions = functionsOf(programsOf(classes));
    assertThat(functions).as("the compiled class must be in the stream").isNotEmpty();

    boolean named = false;
    boolean onNew = false;
    for (TacFunction function : functions) {
      for (TacInstruction instruction : function.instructions()) {
        if (instruction.calleeSig == null) continue;
        if (!instruction.calleeSig.contains("File.<init>")) continue;
        named = true;
        if (instruction.kind == TacInstruction.Kind.NEW) onNew = true;
      }
    }

    assertThat(named).as("the constructor must be named somewhere in the stream").isTrue();
    assertThat(onNew)
        .as(
            "the allocation carries the name in the source lane; the bytecode lane names it on the"
                + " invoke")
        .isFalse();
  }

  @Test
  @DisplayName("the bytecode front end cannot tell an artifact from a dependency")
  void theBytecodeFrontEndCannotTellAnArtifactFromADependency(@TempDir Path directory)
      throws IOException {
    Path classes = compile(directory);
    List<TacFunction> functions = functionsOf(programsOf(classes));
    assertThat(functions).isNotEmpty();

    for (TacFunction function : functions) {
      assertThat(function.origin())
          .as("a class file carries no evidence of whether it is the code under review")
          .isEqualTo(SourceOrigin.LIBRARY);
    }
  }

  @Test
  @DisplayName("an artifact the user named is analysed")
  void anArtifactTheUserNamedIsAnalysed(@TempDir Path directory) throws IOException {
    Path classes = compile(directory);
    List<TacFunction> functions = functionsOf(programsOf(classes));

    List<TacFunction> restamped = new ArrayList<>();
    for (TacFunction function : functions) {
      restamped.add(ScannedOrigin.restamp(function, SourceOrigin.SOURCE));
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    assertThat(config.tuning().shouldAnalyse(SourceOrigin.SOURCE))
        .as("the default analysed set is the source")
        .isTrue();
    assertThat(config.tuning().shouldAnalyse(SourceOrigin.LIBRARY))
        .as("a dependency is not analysed by default")
        .isFalse();

    for (TacFunction function : restamped) {
      assertThat(config.tuning().shouldAnalyse(function.origin()))
          .as("the artifact the user named must be analysed")
          .isTrue();
    }
  }

  @Test
  @DisplayName("the tainted name reaches the file the program opens")
  void theTaintedNameReachesTheFileTheProgramOpens(@TempDir Path directory) throws IOException {
    Path classes = compile(directory);
    List<TacFunction> functions = functionsOf(programsOf(classes));

    List<TacFunction> restamped = new ArrayList<>();
    for (TacFunction function : functions) {
      restamped.add(ScannedOrigin.restamp(function, SourceOrigin.SOURCE));
    }

    List<Finding> findings = scan(restamped, new PathTraversalChecker());

    assertThat(hasFinding(findings, "java.io.File.<init>"))
        .as("the name that arrived over the wire is the path the program opens")
        .isTrue();
  }

  @Test
  @DisplayName("without the re-stamp the artifact is analysed as nothing")
  void withoutTheReStampTheArtifactIsAnalysedAsNothing(@TempDir Path directory) throws IOException {
    Path classes = compile(directory);
    List<TacFunction> functions = functionsOf(programsOf(classes));

    List<Finding> findings = scan(functions, new PathTraversalChecker());

    assertThat(findings)
        .as("the library origin is not analysed, so the body is never looked at")
        .isEmpty();
  }

  @Test
  @DisplayName("a constructor is not recursion")
  void aConstructorIsNotRecursion(@TempDir Path directory) throws IOException {
    Path classes = compile(directory);
    List<TacFunction> functions = functionsOf(programsOf(classes));

    List<TacFunction> restamped = new ArrayList<>();
    for (TacFunction function : functions) {
      restamped.add(ScannedOrigin.restamp(function, SourceOrigin.SOURCE));
    }

    List<Finding> findings = scan(restamped, new UncontrolledRecursionChecker());

    // The bytecode front end names the implicit super() call the compiler inserts, so every
    // class with a constructor carries a `java.lang.Object.<init>/0` call whose receiver is
    // `this`. A constructor that called itself would never return, and the language rejects it
    // at compile time -- so this call is not recursion, and reporting it as such told every JAR
    // that it had unbounded recursion.
    assertThat(findings)
        .as("running a constructor is not re-entering the enclosing method")
        .isEmpty();
  }
}
