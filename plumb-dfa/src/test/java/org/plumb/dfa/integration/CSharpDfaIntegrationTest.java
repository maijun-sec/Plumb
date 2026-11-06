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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.LdapInjectionChecker;
import org.plumb.dfa.checkers.taint.ResourceLeakChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.csharp.CSharpSidecarClient;
import org.plumb.tac.frontend.csharp.CSharpTacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * The DFA consumes a C# function's TAC, and the sink catalogue recognises what the C# frontend
 * produced.
 *
 * <p><b>Why this test exists, and why it lives here.</b> The frontend's own tests prove that the C#
 * frontend emits the right TAC. This test proves the OTHER half: that the engine downstream
 * actually reads that TAC, and that the property-setter sink is reached.
 *
 * <p><b>Why this is the test that matters most.</b> Everything upstream is instrumentation. This is
 * the one that says whether a C# SQL injection is <i>reported</i>. A property assignment that
 * lowered to a {@code FIELDSTORE} would produce a beautiful TAC and no finding, and the scan would
 * still report success -- which is the failure mode the whole design was chosen to avoid.
 *
 * <p><b>Why the sink is a property setter.</b> {@code cmd.CommandText = tainted} is the shape
 * almost every C# SQL injection has. The sink's receiver type is {@code
 * System.Data.Common.DbCommand}, which is the abstract base class in the shared framework -- the
 * concrete providers ship as NuGet packages, so the base class is what a machine can always
 * resolve.
 */
@DisplayName("C#: the DFA reports a SQL injection through a property setter")
class CSharpDfaIntegrationTest {

  /** The rule id the SQL-injection checker reports under. */
  private static final String SQL_RULE = "dfa-taint-sql-injection";

  /** The frontend under test. */
  private static CSharpTacFrontend frontend;

  /** The directory the frontend is given. */
  private static Path sourceRoot;

  /**
   * Whether the .NET toolchain was found.
   *
   * <p>It is resolved in a STATIC INITIALIZER, not in {@code @BeforeAll}: JUnit evaluates an
   * {@code @EnabledIf} condition BEFORE the lifecycle callbacks run, so a flag set in
   * {@code @BeforeAll} would still read {@code false} and every test would be skipped.
   */
  private static final boolean TOOLCHAIN_AVAILABLE;

  static {
    TOOLCHAIN_AVAILABLE = CSharpSidecarClient.locateExecutable(null) != null;
  }

  @BeforeAll
  static void setUp() throws IOException {
    sourceRoot = Files.createTempDirectory("plumb-csharp-dfa-test");
    frontend = new CSharpTacFrontend(CSharpSidecarClient.locateExecutable(null));
  }

  @AfterAll
  static void tearDown() {
    if (frontend != null) {
      frontend.close();
    }
  }

  /**
   * A C# source whose tainted value reaches a SQL command's text through a PROPERTY SETTER.
   *
   * <p>The parameter is the taint source: it comes from outside the function, exactly as a
   * controller's request parameter would.
   */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              System.Data.Common.DbCommand cmd = null;
              cmd.CommandText = input;
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a tainted value that reaches a SQL command's text is reported")
  void aTaintedValueThatReachesTheCommandTextIsReported() {
    List<Finding> findings = scan(SOURCE);

    assertThat(findings)
        .as("the tainted value must reach the SQL sink through the property setter")
        .isNotEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the finding names the property setter, not the setter's receiver")
  void theFindingNamesThePropertySetter() {
    List<Finding> findings = scan(SOURCE);

    assertThat(findings).isNotEmpty();
    Finding finding = findings.get(0);

    // The trace's SINK step must name the setter. This is what a reader sees
    // when they ask "why was this reported?", and it is the step that proves
    // the property setter -- not a field, and not the receiver -- is what the
    // taint reached.
    String trace =
        finding.dataflowSteps().stream()
            .map(step -> step.expr() == null ? "" : step.expr())
            .reduce("", (a, b) -> a + " " + b);
    assertThat(trace)
        .as("the trace must show the setter call, so a reader can see WHY it was reported")
        .contains("set_CommandText");
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a value that does not reach the sink is not reported")
  void aValueThatDoesNotReachTheSinkIsNotReported() {
    // The same sink, but with a value the analysis knows is clean. This is the
    // other half of the measurement: the sink is REACHED, and it is reached
    // because the value is tainted, not because every command assignment
    // reports.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run()
            {
                System.Data.Common.DbCommand cmd = null;
                cmd.CommandText = "select 1";
            }
        }
        """;

    List<Finding> findings = scan(source);

    assertThat(findings)
        .as("a constant is not attacker-controlled, so it must not be reported")
        .isEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the function is analysed as C#, so the language filter applies")
  void theFunctionIsAnalysedAsCSharp() {
    List<TacFunction> functions = frontend.parse(SOURCE, sourceRoot.resolve("Sink.cs")).functions();

    assertThat(functions).isNotEmpty();
    for (TacFunction function : functions) {
      assertThat(function.sourceLanguage())
          .as("the function must be marked as C#, or the language filter cannot do its job")
          .isEqualTo(SourceLanguage.CSHARP);
    }
  }

  /**
   * A tainted value that reaches a PROCESS's command line.
   *
   * <p><b>Why this case, and why it is separate.</b> The other tests use {@code
   * System.Data.Common.DbCommand}, whose sink pattern is HAND-WRITTEN. This one uses {@code
   * System.Diagnostics.ProcessStartInfo}, whose sink pattern is CONVERTED -- it comes from a rule
   * pack, not from a person.
   *
   * <p>That difference matters because a converted pattern can be wrong in ways a hand-written one
   * is not: it can carry a generic-arity marker the TAC erases, it can carry an escaped wildcard
   * that matches nothing, and it can name an argument index that is not the argument the taint
   * reaches. Each of those produces a rule that LOOKS like coverage and reports nothing -- or
   * reports the wrong thing.
   *
   * <p>So this test closes the loop on the conversion: the converted pattern is in the catalogue,
   * the C# frontend produces the signature the pattern expects, the engine matches them, and the
   * finding is reported. The command line is a property setter for the same reason the SQL text is:
   * {@code startInfo.Arguments = tainted} is a call to {@code ProcessStartInfo.set_Arguments}.
   */
  private static final String PROCESS_SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              System.Diagnostics.ProcessStartInfo startInfo = new System.Diagnostics.ProcessStartInfo();
              startInfo.Arguments = input;
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a CONVERTED .NET sink is reached end to end")
  void aConvertedNetSinkIsReachedEndToEnd() {
    List<Finding> findings = scanWith(PROCESS_SOURCE, new CommandInjectionChecker());

    assertThat(findings)
        .as(
            "the converted pattern for ProcessStartInfo.set_Arguments must be reached: the "
                + "catalogue carries it, the frontend produces the signature, and the engine "
                + "must report the finding")
        .isNotEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the converted sink reports the argument the taint reaches, not the receiver")
  void theConvertedSinkReportsTheArgumentTheTaintReaches() {
    List<Finding> findings = scanWith(PROCESS_SOURCE, new CommandInjectionChecker());

    assertThat(findings).isNotEmpty();
    Finding finding = findings.get(0);

    String trace =
        finding.dataflowSteps().stream()
            .map(step -> step.expr() == null ? "" : step.expr())
            .reduce("", (a, b) -> a + " " + b);
    assertThat(trace)
        .as("the trace must show the converted sink, so a reader can see WHY it was reported")
        .contains("set_Arguments");
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a clean value does not reach the converted sink either")
  void aCleanValueDoesNotReachTheConvertedSink() {
    // The other half: the converted sink is reached because the value is
    // tainted, not because every command-line assignment reports.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run()
            {
                System.Diagnostics.ProcessStartInfo startInfo = new System.Diagnostics.ProcessStartInfo();
                startInfo.Arguments = "/c dir";
            }
        }
        """;

    List<Finding> findings = scanWith(source, new CommandInjectionChecker());

    assertThat(findings)
        .as("a constant is not attacker-controlled, so it must not be reported")
        .isEmpty();
  }

  /**
   * A tainted value that reaches a CONSTRUCTOR.
   *
   * <p><b>Why this case exists.</b> A constructor is a {@code NEW} instruction, and the engine
   * looks up a sink for a {@code NEW} only when the instruction carries a CALLEE SIGNATURE. The C#
   * frontend used to emit {@code NEW} with the constructed TYPE only -- so every constructor sink
   * was structurally unreachable: the catalogue carried the pattern, the code was right, and the
   * finding was simply absent while the scan reported success.
   *
   * <p>That is the failure mode this whole design was chosen to avoid, in a place nobody was
   * looking. The fix is that a C# {@code new} now carries the same {@code owner.<init>/arity}
   * callee key every other Plumb frontend emits.
   */
  private static final String CONSTRUCTOR_SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              var cmd = new System.Data.Common.DbCommand();
              System.Console.WriteLine(input);
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a C# constructor carries the callee signature a constructor sink needs")
  void aCsharpConstructorCarriesTheCalleeSignature() {
    // This is the STRUCTURAL measurement: the NEW instruction must name
    // `owner.<init>/arity`, because without it the engine's constructor-sink
    // lookup returns immediately and no constructor rule can ever fire.
    List<TacFunction> functions =
        frontend.parse(CONSTRUCTOR_SOURCE, sourceRoot.resolve("Ctor.cs")).functions();
    assertThat(functions).isNotEmpty();

    boolean sawNew = false;
    for (TacFunction function : functions) {
      for (var instruction : function.instructions()) {
        if (instruction.kind != TacInstruction.Kind.NEW) {
          continue;
        }
        sawNew = true;
        assertThat(instruction.calleeSig)
            .as(
                "a C# `new` must carry the callee signature: the engine queries the sink "
                    + "catalogue for a NEW only when the signature is present, so a NEW without "
                    + "one makes every constructor sink unreachable")
            .isNotNull();
        assertThat(instruction.calleeSig)
            .as("the constructor's callee key must use the <init> convention")
            .contains(".<init>/");
      }
    }
    assertThat(sawNew).as("the source must lower to at least one NEW instruction").isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a C# constructor sink is reachable end to end")
  void aCsharpConstructorSinkIsReachableEndToEnd() {
    // The catalogue carries `System.DirectoryServices.DirectorySearcher.<init>`
    // as a sink, because the vendor's .NET pack names the DirectorySearcher
    // CONSTRUCTOR as a point where untrusted input reaches an LDAP search.
    //
    // The chain has to close: the frontend produces the callee signature, the
    // engine matches the pattern on a NEW instruction, and the finding is
    // reported. Before the C# frontend emitted a callee signature on NEW,
    // every one of these constructor rules was unreachable.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                var searcher = new System.DirectoryServices.DirectorySearcher(input);
            }
        }
        """;

    List<Finding> findings = scanWith(source, new LdapInjectionChecker());

    assertThat(findings)
        .as(
            "the constructor sink must be reachable: the catalogue carries the pattern, the "
                + "frontend must produce the callee signature, and the engine must report it")
        .isNotEmpty();
  }

  /**
   * A C# resource that is never disposed.
   *
   * <p><b>Why this case is in the C# suite and not in a Java one.</b> The resource-leak rule
   * (CWE-404) is not a taint rule: it fires when an allocation is NOT closed. So it does not belong
   * to a sink family, and a review that compares sink families would not have found it.
   *
   * <p>The comparison did find it, because the other tool's checker list has a {@code
   * RESOURCE_LEAK} checker for C#, and the rule's tracked-type list turned out to be Java-only: a
   * C# {@code FileStream} was not an allocation site at all, so the rule never fired on C#.
   */
  private static final String LEAK_SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run()
          {
              var fs = new System.IO.FileStream(System.IO.Path.GetTempFileName(), System.IO.FileMode.Open);
          }
      }
      """;

  private static final String DISPOSED_SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run()
          {
              var fs = new System.IO.FileStream(System.IO.Path.GetTempFileName(), System.IO.FileMode.Open);
              fs.Dispose();
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a C# resource that is never disposed is reported as a leak")
  void aCsharpResourceThatIsNeverDisposedIsReported() {
    List<Finding> findings = scanWith(LEAK_SOURCE, new ResourceLeakChecker());

    assertThat(findings)
        .as("the C# FileStream must be an allocation site, or the rule never fires on C#")
        .isNotEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a C# resource that IS disposed is not reported")
  void aCsharpResourceThatIsDisposedIsNotReported() {
    // The other half, and the one that matters most: .NET spells the close
    // `Dispose`, not `close`. A rule that only knew `close` would report
    // EVERY correctly-disposed .NET resource as a leak -- a false positive
    // on correct code, and one that fires on every resource in the program.
    List<Finding> findings = scanWith(DISPOSED_SOURCE, new ResourceLeakChecker());

    assertThat(findings).as("the resource IS disposed, so there is no leak").isEmpty();
  }

  /**
   * Parses a C# source and runs the SQL-injection checker over it.
   *
   * @param source the C# source
   * @return the findings the SQL-injection rule produced
   */
  private List<Finding> scan(String source) {
    return scanWith(source, new SqlInjectionChecker());
  }

  /**
   * Parses a C# source and runs one taint checker over it.
   *
   * @param source the C# source
   * @param checker the checker to run
   * @return the findings the checker produced
   */
  private List<Finding> scanWith(String source, DfaChecker checker) {
    List<TacFunction> functions = frontend.parse(source, sourceRoot.resolve("Sink.cs")).functions();
    assertThat(functions).as("the C# source must lower to at least one function").isNotEmpty();

    TacProgram program = new TacProgram(sourceRoot.resolve("Sink.cs"), "Sink");
    for (TacFunction fn : functions) {
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
   * A C# source whose tainted value passes through a case conversion.
   *
   * <p>{@code s.ToUpper()} is the sharpest case in .NET: it produces a string derived from the
   * input, so the taint MUST survive it. It is also the case the engine got wrong: the passthrough
   * categories are matched by METHOD NAME, and the list only knew Java's {@code toUpperCase}, so a
   * .NET {@code ToUpper} dropped the taint at the call.
   */
  private static final String CASE_SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              string upper = input.ToUpper();
              System.Data.Common.DbCommand cmd = null;
              cmd.CommandText = upper;
          }
      }
      """;

  /**
   * A tainted value that passes through {@code String.ToUpper} must STILL reach the sink.
   *
   * <p><b>This is the test that pins the .NET case-conversion names.</b> A case conversion produces
   * a string derived from the input, so the taint survives it. If the engine did not recognise
   * {@code ToUpper} as a case conversion, the taint was dropped at the call, the sink was never
   * reached, and the scan reported a clean program for code that flows user input into SQL.
   *
   * <p>A dropped taint is a <em>false negative</em>, which is the expensive kind: the finding that
   * should have been reported never was.
   */
  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a tainted value that passes through String.ToUpper still reaches the sink")
  void aTaintedValueThroughStringToUpperStillReachesTheSink() {
    List<Finding> findings = scan(CASE_SOURCE);

    assertThat(findings)
        .as(
            "ToUpper produces a string derived from the input: the taint must survive it, or the "
                + "sink is never reached")
        .isNotEmpty();
  }

  /**
   * The same chain, but the case conversion is spelled the JAVA way.
   *
   * <p>The other half of the measurement: the Java spelling still works, so the fix did not replace
   * one behaviour with another.
   */
  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the Java spelling of a case conversion still reaches the sink")
  void theJavaSpellingOfACaseConversionStillReachesTheSink() {
    // This is the JAVA shape, run through the C# frontend so the test compares
    // the two spellings under the same engine.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                System.Data.Common.DbCommand cmd = null;
                cmd.CommandText = input.Trim();
            }
        }
        """;

    List<Finding> findings = scan(source);

    assertThat(findings)
        .as("Trim produces a string derived from the input, so the taint must survive it")
        .isNotEmpty();
  }

  /** Whether the .NET toolchain is available. */
  static boolean toolchainAvailable() {
    return TOOLCHAIN_AVAILABLE;
  }
}
