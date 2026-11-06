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
package org.plumb.dfa.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.SymbolTable;
import org.plumb.tac.frontend.csharp.CSharpSidecarClient;
import org.plumb.tac.frontend.csharp.CSharpTacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * The C# frontend's TAC has to survive the GLOBAL call graph, which is where a C# program stops
 * being one compilation unit and becomes several.
 *
 * <p><b>Why this is its own test.</b> The sidecar holds one project-scoped compilation, so a type
 * declared in one file resolves in another. But a resolved symbol is not a resolved CALL EDGE: the
 * dataflow engine follows the call graph, and the call graph is built from the TAC's callee
 * signatures. A signature that does not match the callee's own signature is a dangling edge, and
 * the taint stops at it -- silently, and with the scan reporting success.
 *
 * <p><b>What is measured.</b> A tainted value that crosses a FILE boundary into a SQL sink is
 * reported. That exercises: the cross-file symbol resolution, the callee signature's cross-file
 * form, and the global call graph's edge.
 */
@DisplayName("C#: the call graph across compilation units")
class CSharpCrossFileCallGraphTest {

  /** The frontend under test. */
  private static CSharpTacFrontend frontend;

  /** The directory the frontend is given. */
  private static Path sourceRoot;

  /**
   * Whether the .NET toolchain was found.
   *
   * <p>It is resolved in a STATIC INITIALIZER, not in {@code @BeforeAll}: JUnit evaluates an
   * {@code @EnabledIf} condition BEFORE the lifecycle callbacks run.
   */
  private static final boolean TOOLCHAIN_AVAILABLE;

  static {
    TOOLCHAIN_AVAILABLE = CSharpSidecarClient.locateExecutable(null) != null;
  }

  @BeforeAll
  static void setUp() throws IOException {
    sourceRoot = Files.createTempDirectory("plumb-csharp-crossfile-test");
    frontend = new CSharpTacFrontend(CSharpSidecarClient.locateExecutable(null));
    // Both files go on DISK under the source root, and the context declares
    // that root as a module. That is how a real scan reaches the frontend: the
    // sidecar's project is built from the module's source root, so a file is
    // only resolvable against its siblings if they are in the project.
    Files.writeString(sourceRoot.resolve("Entry.cs"), SOURCE);
    Files.writeString(sourceRoot.resolve("Helper.cs"), SINK);
  }

  @AfterAll
  static void tearDown() {
    if (frontend != null) {
      frontend.close();
    }
  }

  /** The file that holds the SOURCE. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Entry
      {
          public void Run(string input)
          {
              Helper.Write(input);
          }
      }
      """;

  /** The file that holds the SINK. */
  private static final String SINK =
      """
      namespace Demo;

      public class Helper
      {
          public static void Write(string text)
          {
              System.Data.Common.DbCommand cmd = null;
              cmd.CommandText = text;
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a tainted value that crosses a FILE boundary into a SQL sink is reported")
  void aTaintedValueThatCrossesAFileBoundaryIsReported() {
    List<Finding> findings = scan();

    assertThat(findings)
        .as(
            "the taint must cross the file boundary: the callee's signature must resolve "
                + "against the global call graph, or the taint stops at the call edge "
                + "and the finding is absent while the scan reports success")
        .isNotEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("both files' functions are in the program, so the call edge can exist")
  void bothFilesFunctionsAreInTheProgram() {
    List<TacFunction> caller =
        frontend.parse(SOURCE, sourceRoot.resolve("Entry.cs"), context()).functions();
    List<TacFunction> callee =
        frontend.parse(SINK, sourceRoot.resolve("Helper.cs"), context()).functions();

    assertThat(caller).as("the calling file must lower to functions").isNotEmpty();
    assertThat(callee).as("the called file must lower to functions").isNotEmpty();

    boolean sawWrite = false;
    for (TacFunction function : callee) {
      if ("Write".equals(function.name())) {
        sawWrite = true;
      }
    }
    assertThat(sawWrite)
        .as("the called file must contain the function the other file calls")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the call site names the called function, so the call graph can resolve the edge")
  void theCallSiteNamesTheCalledFunction() {
    // The call edge exists only when the call site can be CONNECTED to the
    // callee. The two spellings differ -- the call site uses the TAC's callee
    // key (`owner.method/arity`) and the function carries its declaration
    // signature (`owner.method(Params):Return`) -- so the connection is made
    // by the resolver, not by string equality. What has to hold is that the
    // call site NAMES the called function: a callee key that does not name it
    // cannot be resolved to it, and the edge silently does not exist.
    List<TacFunction> caller =
        frontend.parse(SOURCE, sourceRoot.resolve("Entry.cs"), context()).functions();
    List<TacFunction> callee =
        frontend.parse(SINK, sourceRoot.resolve("Helper.cs"), context()).functions();

    String calledSignature = null;
    for (TacFunction function : callee) {
      if ("Write".equals(function.name())) {
        calledSignature = function.signature();
      }
    }
    assertThat(calledSignature).as("the callee must have a signature").isNotNull();

    String callSiteSignature = null;
    for (TacFunction function : caller) {
      for (var instruction : function.instructions()) {
        if (instruction.kind != TacInstruction.Kind.CALL) {
          continue;
        }
        if (instruction.calleeSig != null && instruction.calleeSig.contains("Write")) {
          callSiteSignature = instruction.calleeSig;
        }
      }
    }
    assertThat(callSiteSignature).as("the caller must contain a call to Write").isNotNull();
    assertThat(callSiteSignature)
        .as("the call site must name the called function's owner and name")
        .contains("Helper")
        .contains("Write");
  }

  /**
   * Parses both files and runs the SQL-injection checker over them.
   *
   * @return the findings the checker produced
   */
  private List<Finding> scan() {
    List<TacFunction> caller =
        frontend.parse(SOURCE, sourceRoot.resolve("Entry.cs"), context()).functions();
    List<TacFunction> callee =
        frontend.parse(SINK, sourceRoot.resolve("Helper.cs"), context()).functions();
    assertThat(caller).as("the C# source must lower to at least one function").isNotEmpty();
    assertThat(callee).as("the C# source must lower to at least one function").isNotEmpty();

    // Both files go into ONE program: the global call graph spans them.
    TacProgram program = new TacProgram(sourceRoot.resolve("Entry.cs"), "Demo");
    for (TacFunction fn : caller) {
      program.addFunction(fn);
    }
    for (TacFunction fn : callee) {
      program.addFunction(fn);
    }

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig config =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
                2,
                1000,
                1000,
                100,
                4,
                false,
                false,
                false,
                "full",
                "cwe_family",
                "sarif",
                false,
                0),
            base.receiverPassthroughPatterns());

    DfaChecker checker = new SqlInjectionChecker();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);

    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        out.add(finding);
      }
    }
    return out;
  }

  /**
   * A compile context over the source root.
   *
   * <p>The module's source root is what tells the frontend which files belong to the project.
   * Without it the sidecar's project holds one file, and a call to a sibling does not resolve -- so
   * the cross-file edge cannot exist, and the test would measure nothing.
   */
  private static CompileContext context() {
    return new CompileContext() {
      @Override
      public List<Module> modules() {
        return List.of(new Module("Demo", sourceRoot, List.of(), List.of(), List.of(), null));
      }

      @Override
      public List<Path> classpath() {
        return List.of();
      }

      @Override
      public SymbolTable symbols() {
        return new SymbolTable() {
          @Override
          public void register(String ownerType, String methodName, int arity, String signature) {}

          @Override
          public java.util.Optional<String> lookup(String ownerType, String methodName, int arity) {
            return java.util.Optional.empty();
          }

          @Override
          public List<String> lookupByName(String methodName, int arity) {
            return List.of();
          }
        };
      }

      @Override
      public <T> T computeIfAbsent(String key, java.util.function.Function<String, T> factory) {
        return factory.apply(key);
      }

      @Override
      public void close() {}
    };
  }

  /** Whether the .NET toolchain is available. */
  static boolean toolchainAvailable() {
    return TOOLCHAIN_AVAILABLE;
  }
}
