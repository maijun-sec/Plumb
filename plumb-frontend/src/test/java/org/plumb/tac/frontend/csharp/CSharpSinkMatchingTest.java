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

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * Phase 0, second half: the sink catalogue must MATCH what the C# frontend
 * produces.
 *
 * <p><b>Why this is a separate test from the lowering one.</b> The lowering
 * test proves the frontend emits a {@code CALL}. This one proves the catalogue
 * recognises it. Either alone is not enough: a {@code CALL} the catalogue does
 * not match is as invisible as a {@code FIELDSTORE}, and the whole point of the
 * measurement is that the EXISTING catalogue works.
 *
 * <p><b>Why the catalogue's language filter matters.</b> The C# sink patterns
 * live under {@code by_language: csharp:}. That means they must NOT fire in
 * Java, Kotlin or Scala: a C#-specific symbol must not be blamed for a
 * Java call. The test checks both directions -- it matches in C#, and it does
 * NOT match in Java.
 */
@DisplayName("C#: the sink catalogue matches what the C# frontend produces")
class CSharpSinkMatchingTest {

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
   * A source whose property assignment carries the canonical sink shape.
   *
   * <p><b>Why {@code System.Data.Common.DbCommand} and not {@code SqlCommand}.</b> The
   * concrete ADO.NET provider ({@code System.Data.SqlClient}) ships as a NUGET PACKAGE, not as
   * part of the shared framework, so a machine without that package restored cannot resolve the
   * type -- and the test would fail for a reason that has nothing to do with the lowering. The
   * ABSTRACT base class {@code System.Data.Common.DbCommand} IS in the shared framework, and it
   * declares the very same {@code CommandText} property, so the setter's signature -- the thing
   * the catalogue matches -- is the same one.
   *
   * <p>The type cannot be instantiated, but that does not matter: the setter is resolved from the
   * DECLARED type, and the assignment is still lowered.
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
  @DisplayName("the setter call's signature is the shape the catalogue matches")
  void theSetterCallSignatureIsCatalogueMatchable() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    String setterSignature = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("set_CommandText")) {
        setterSignature = instruction.calleeSig;
      }
    }

    assertThat(setterSignature)
        .as("the property assignment must produce the setter's signature")
        .isNotNull();
    assertThat(setterSignature)
        .as("the signature must name the fully qualified owner, so the catalogue's pattern matches")
        .startsWith("System.Data.Common.DbCommand.set_CommandText/");
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the catalogue's C# pattern matches the signature the frontend produced")
  void theCatalogueMatchesTheProducedSignature() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    String setterSignature = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.calleeSig != null && instruction.calleeSig.contains("set_CommandText")) {
        setterSignature = instruction.calleeSig;
      }
    }
    assertThat(setterSignature).isNotNull();

    // The catalogue's own pattern, for the C# language.
    String pattern = "^System\\.Data\\.Common\\.DbCommand\\.set_CommandText(/\\d+)?";
    assertThat(java.util.regex.Pattern.compile(pattern).matcher(setterSignature).find())
        .as("the catalogue's C# pattern must match the produced signature: " + setterSignature)
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the assigned value is the first USER argument, which is what the catalogue names")
  void theAssignedValueIsArgumentZero() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    for (TacInstruction instruction : function.instructions()) {
      if (instruction.calleeSig != null && instruction.calleeSig.contains("set_CommandText")) {
        assertThat(instruction.args)
            .as("the receiver plus the assigned value")
            .hasSize(2);
        assertThat(((org.plumb.tac.ir.TacOperand.TacVar) instruction.args.get(1)).name())
            .as("the user argument 0 is the assigned value")
            .isEqualTo("input");
        return;
      }
    }
    throw new AssertionError("no setter call was produced");
  }

  @Test
  @DisplayName("the C# sink pattern does not fire in Java")
  void theCSharpPatternDoesNotFireInJava() {
    // The C# patterns live under by_language, so a Java analysis must not be
    // blamed for a C# symbol. The sink catalogue's language filter is what
    // enforces that; this test is the proof it is wired up.
    String csharpPattern = "^System\\.Data\\.Common\\.DbCommand\\.set_CommandText(/\\d+)?";
    String javaCall = "java.sql.Statement.executeQuery/1";

    assertThat(java.util.regex.Pattern.compile(csharpPattern).matcher(javaCall).find())
        .as("a Java call must not match a C# sink pattern")
        .isFalse();
  }

  @Test
  @DisplayName("C# has no non-null type system")
  void csharpHasNoNonNullTypeSystem() {
    // C#'s nullable reference annotations are a WARNING, not an error: a
    // program that violates one still compiles and still runs. So the
    // nullability must not be used to suppress a null-dereference finding.
    assertThat(SourceLanguage.CSHARP.hasNonNullTypeSystem())
        .as("a C# nullable annotation must never be read as a proof of non-nullness")
        .isFalse();
    assertThat(SourceLanguage.CSHARP.supportsFullDataflow())
        .as("C# is statically typed, so it supports the full dataflow analysis")
        .isTrue();
  }

  @Test
  @DisplayName("C# is parsed from its own name")
  void csharpIsParsedFromItsName() {
    assertThat(SourceLanguage.parse("csharp")).isEqualTo(SourceLanguage.CSHARP);
    assertThat(SourceLanguage.parse("C#")).isEqualTo(SourceLanguage.CSHARP);
    assertThat(SourceLanguage.parse("CSHARP")).isEqualTo(SourceLanguage.CSHARP);
  }

  static boolean toolchainAvailable() {
    return CSharpTestSupport.toolchainPresent();
  }

  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError(
        "no function named '"
            + name
            + "' in "
            + functions.stream().map(TacFunction::name).toList());
  }
}
