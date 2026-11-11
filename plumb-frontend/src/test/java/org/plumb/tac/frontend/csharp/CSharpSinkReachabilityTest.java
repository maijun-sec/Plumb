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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The catalogue's C# symbols must be symbols the C# frontend PRODUCES.
 *
 * <p><b>Why this is a separate test from the lowering one.</b> The lowering test proves the
 * frontend emits a {@code CALL}. This one proves the catalogue RECOGNISES it. Either alone is not
 * enough: a {@code CALL} the catalogue does not match is as invisible as a {@code FIELDSTORE}, and
 * the whole point of the measurement is that the catalogue's symbols are the ones the frontend
 * emits.
 *
 * <p><b>Why the owner must be the DECLARING type.</b> The C# frontend names a callee's owner after
 * the type that DECLARES the member, not the type the caller holds: a member that the holder only
 * INHERITS is attributed to the type that declares it. A catalogue pattern that names the caller's
 * type names a symbol the frontend never emits, and the entry is dead -- silently, because the scan
 * still reports success. That is the shape the indexer bug had, and this test is what keeps it
 * fixed.
 *
 * <p>The cases below are the ones the shared framework can reach. A pattern whose type needs a
 * package is not here: there is nothing to produce without the package, so the case would pass for
 * the wrong reason.
 */
@DisplayName("C#: the catalogue's C# symbols are the ones the frontend produces")
class CSharpSinkReachabilityTest {

  private static CSharpTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new CSharpTestSupport();
  }

  @AfterAll
  static void tearDown() {
    if (support != null) {
      support.close();
    }
  }

  /**
   * One case: the catalogue's pattern, and the C# source whose call must produce a signature the
   * pattern matches.
   *
   * @param family the catalogue family
   * @param pattern the catalogue's own regex
   * @param source the C# source that calls the symbol
   */
  private record Case(String family, String pattern, String source) {}

  /** The cases the shared framework can reach. */
  private static final List<Case> CASES =
      List.of(
          new Case(
              "command.sinks",
              "^System\\.Diagnostics\\.ProcessStartInfo\\.set_FileName(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      var info = new System.Diagnostics.ProcessStartInfo();
                      info.FileName = input;
                  }
              }
              """),
          new Case(
              "command.sinks",
              "^System\\.Diagnostics\\.Process\\.Start(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      System.Diagnostics.Process.Start(input);
                  }
              }
              """),
          new Case(
              "cleartext-tx.sinks",
              "^System\\.IO\\.StreamWriter\\.WriteLine(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      System.IO.StreamWriter writer = null;
                      writer.WriteLine(input);
                  }
              }
              """),
          new Case(
              "credential.sinks",
              "^System\\.Data\\.Common\\.DbConnectionStringBuilder\\.(set_Item|Add)(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      System.Data.Common.DbConnectionStringBuilder builder = null;
                      builder.Add("Password", input);
                  }
              }
              """),
          new Case(
              "file-path.sinks",
              "^System\\.IO\\.Compression\\.ZipFile\\.ExtractToDirectory(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      System.IO.Compression.ZipFile.ExtractToDirectory(input, "out");
                  }
              }
              """),
          new Case(
              "deserialization.sinks",
              "^System\\.Text\\.Json\\.JsonSerializer\\.Deserialize(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      System.Text.Json.JsonSerializer.Deserialize<string>(input);
                  }
              }
              """),
          new Case(
              "dos.sinks",
              "^System\\.Threading\\.Thread\\.Sleep(/\\d+)?",
              """
              namespace Demo;

              public class Sink
              {
                  public void Run(string input)
                  {
                      System.Threading.Thread.Sleep(1000);
                  }
              }
              """));

  @Test
  @DisplayName("every case produces a signature the catalogue's own pattern matches")
  void everyCaseProducesAMatchableSignature() {
    for (Case testCase : CASES) {
      Pattern pattern = Pattern.compile(testCase.pattern());
      List<TacFunction> functions = support.parse(testCase.source()).functions();

      List<String> signatures = new java.util.ArrayList<>();
      for (TacFunction function : functions) {
        for (TacInstruction instruction : function.instructions()) {
          if (instruction.kind != TacInstruction.Kind.CALL
              && instruction.kind != TacInstruction.Kind.NEW) {
            continue;
          }
          if (instruction.calleeSig != null) {
            signatures.add(instruction.calleeSig);
          }
        }
      }

      assertThat(signatures.stream().anyMatch(s -> pattern.matcher(s).find()))
          .as(
              "the catalogue's pattern "
                  + testCase.pattern()
                  + " must match a signature the frontend produced; it produced "
                  + signatures)
          .isTrue();
    }
  }

  @Test
  @DisplayName("the owner's name in the signature is the type that DECLARES the member")
  void theOwnerIsTheDeclaringType() {
    // `StreamWriter` declares its own `WriteLine` -- it OVERRIDES the one
    // `TextWriter` declares -- so the member the frontend resolves is the
    // one `StreamWriter` declares, and the signature says `StreamWriter`.
    // A catalogue pattern naming `TextWriter` would name a symbol this
    // frontend never emits.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                System.IO.StreamWriter writer = null;
                writer.WriteLine(input);
            }
        }
        """;

    List<String> signatures = new java.util.ArrayList<>();
    for (TacFunction function : support.parse(source).functions()) {
      for (TacInstruction instruction : function.instructions()) {
        if (instruction.calleeSig != null) {
          signatures.add(instruction.calleeSig);
        }
      }
    }

    // `StreamWriter` OVERRIDES `TextWriter.WriteLine(string)`, so the member
    // the frontend resolves is the one `StreamWriter` declares -- and the
    // signature says `StreamWriter`. A catalogue pattern that named
    // `TextWriter` here would name a symbol the frontend never emits, and the
    // entry would be dead. Naming the base class instead is the same failure
    // in the other direction, so the assertion pins the exact owner.
    List<String> writeLineSignatures =
        signatures.stream().filter(s -> s.contains("WriteLine")).toList();

    assertThat(writeLineSignatures)
        .as("the signature names the type that declares the method")
        .hasSize(1)
        .allMatch(s -> s.startsWith("System.IO.StreamWriter.WriteLine/"));
  }
}
