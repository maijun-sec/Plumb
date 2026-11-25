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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.arkts.ArktsSidecarClient;
import org.plumb.tac.frontend.arkts.ArktsTacFrontend;
import org.plumb.tac.frontend.csharp.CSharpSidecarClient;
import org.plumb.tac.frontend.csharp.CSharpTacFrontend;
import org.plumb.tac.frontend.php.PhpSidecarClient;
import org.plumb.tac.frontend.php.PhpTacFrontend;
import org.plumb.tac.frontend.python.PythonSidecarClient;
import org.plumb.tac.frontend.python.PythonTacFrontend;
import org.plumb.tac.frontend.ts.TsSidecarClient;
import org.plumb.tac.frontend.ts.TsTacFrontend;
import org.plumb.tac.ir.ExceptionTypeHierarchies;
import org.plumb.tac.ir.ExceptionTypeMatcher;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacBasicBlock;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacType;

/**
 * The type a THROW carries, and the catch edge it decides.
 *
 * <p><b>What this measures.</b> The engine matches a catch handler against the TYPE of the
 * exception that is thrown: the handler whose declared type is a supertype of the thrown type is
 * the handler that runs, and the handlers after it are unreachable for that throw. The two names
 * live on the instructions -- the {@code ctorType} of the {@code THROW} and of the {@code
 * CATCH_HANDLER} -- and the relation between them is world knowledge the engine supplies.
 *
 * <p><b>Why it is an END-TO-END test.</b> The wire carries the type, and the Java side reads it
 * unconditionally. So the question is not "does the mapper read the field" -- it does -- but "does
 * the sidecar SEND it, and does the engine then decide the catch edge". A test that only looked at
 * the mapper would pass with every sidecar broken, and a test that only looked at the sidecar would
 * pass with the engine unable to use the type. This test goes through both: the REAL sidecar as a
 * PROCESS, the REAL wire, the REAL mapper, the REAL IR and the REAL CFG builder.
 *
 * <p><b>Why the programs look the way they do.</b> A block that raises an exception of an UNKNOWN
 * type -- a call, a construction -- keeps EVERY handler reachable, because the constructor or the
 * call may raise something else. So the exception a throw raises is built OUTSIDE the try, and what
 * the try raises is a value whose type is already known. That is the shape in which the engine's
 * typed matching can decide anything, and it is the shape a reader has to see to understand what
 * the type buys.
 *
 * <p><b>What the two script languages cannot show.</b> A TypeScript or an ArkTS {@code catch}
 * clause names no type -- the language has no way to write one -- so the handler carries no type,
 * and the engine has nothing to compare the thrown type against. The throw now carries its type on
 * the wire, and that is what these tests pin; the catch edge stays the over-approximation, and the
 * test says so rather than pretending otherwise.
 */
@DisplayName("the sidecars: the type a THROW carries decides the catch edge")
class SidecarThrowTypeCatchMatchE2ETest {

  // ---- C# -------------------------------------------------------------------------------

  /** A C# program whose try raises an IOException and whose handlers catch it and its base. */
  private static final String CSHARP_SOURCE =
      """
      namespace Demo;

      public class Handler
      {
          public void Run(string input)
          {
              var failure = new System.IO.IOException(input);
              try
              {
                  throw failure;
              }
              catch (System.IO.IOException error)
              {
                  System.Console.WriteLine(error.Message);
              }
              catch (System.Exception other)
              {
                  System.Console.WriteLine(other.Message);
              }
          }
      }
      """;

  @Test
  @DisplayName("C#: a catch of IOException reaches the throw of an IOException, and only it")
  void csharpTheCatchMatchesTheThrownType(@TempDir Path directory) throws IOException {
    assumeTrue(CSharpSidecarClient.locateExecutable(null) != null, CSharpSidecarClient.NOT_BUILT);

    try (CSharpTacFrontend frontend = new CSharpTacFrontend()) {
      TacFunction function = parse(directory, "Handler.cs", CSHARP_SOURCE, frontend);

      assertTheCatchMatches(
          function,
          SourceLanguage.CSHARP,
          "System.IO.IOException",
          "System.IO.IOException",
          "System.Exception");
    }
  }

  // ---- PHP ------------------------------------------------------------------------------

  /** A PHP program whose try raises a RuntimeException and whose handlers catch it and its base. */
  private static final String PHP_SOURCE =
      String.join(
          "\n",
          "<?php",
          "namespace App;",
          "",
          "function handle(\\RuntimeException $failure): string {",
          "    try {",
          "        throw $failure;",
          "    } catch (\\RuntimeException $error) {",
          "        return $error->getMessage();",
          "    } catch (\\Exception $other) {",
          "        return $other->getMessage();",
          "    }",
          "}");

  @Test
  @DisplayName("PHP: a catch of RuntimeException reaches the throw of a RuntimeException")
  void phpTheCatchMatchesTheThrownType(@TempDir Path directory) throws IOException {
    assumeTrue(PhpSidecarClient.locateNode() != null, "the PHP front end needs Node");
    assumeTrue(PhpSidecarClient.locateScript(null) != null, "the PHP sidecar is not built");

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      TacFunction function = parse(directory, "app.php", PHP_SOURCE, frontend);

      assertTheCatchMatches(
          function, SourceLanguage.PHP, "\\RuntimeException", "\\RuntimeException", "\\Exception");
    }
  }

  // ---- Python ---------------------------------------------------------------------------

  /** A Python program whose try raises a ValueError and whose handlers catch it and its base. */
  private static final String PYTHON_SOURCE =
      String.join(
          "\n",
          "def handle(failure: ValueError):",
          "    try:",
          "        raise failure",
          "    except ValueError as error:",
          "        return str(error)",
          "    except Exception as other:",
          "        return str(other)");

  @Test
  @DisplayName("Python: a catch of ValueError reaches the throw of a ValueError, and only it")
  void pythonTheCatchMatchesTheThrownType(@TempDir Path directory) throws IOException {
    assumeTrue(PythonSidecarClient.locatePython() != null, "the Python front end needs Python 3");
    assumeTrue(PythonSidecarClient.locateScript(null) != null, "the Python sidecar is not built");

    try (PythonTacFrontend frontend = new PythonTacFrontend()) {
      TacFunction function = parse(directory, "app.py", PYTHON_SOURCE, frontend);

      assertTheCatchMatches(
          function, SourceLanguage.PYTHON3, "ValueError", "ValueError", "Exception");
    }
  }

  // ---- TypeScript -----------------------------------------------------------------------

  /** A TypeScript program whose try raises an Error. */
  private static final String TYPESCRIPT_SOURCE =
      String.join(
          "\n",
          "export function handle(failure: Error): string {",
          "  try {",
          "    throw failure;",
          "  } catch (error) {",
          "    return String(error);",
          "  }",
          "}");

  @Test
  @DisplayName("TypeScript: the throw carries the type it throws")
  void typescriptTheThrowCarriesItsType(@TempDir Path directory) throws IOException {
    assumeTrue(TsSidecarClient.locateNode() != null, "the TypeScript front end needs Node");
    assumeTrue(TsSidecarClient.locateScript(null) != null, "the TypeScript sidecar is not built");

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      TacFunction function = parse(directory, "app.ts", TYPESCRIPT_SOURCE, frontend);

      TacInstruction thrown = theThrow(function);
      assertThat(thrown.ctorType).as("a throw must name the type it throws").isNotNull();
      assertThat(thrown.ctorType.kind()).isEqualTo(TacType.Kind.CLASS);
      assertThat(thrown.ctorType.displayName()).isEqualTo("Error");

      // The catch clause of this language names no type, so the handler carries
      // none -- and the engine has nothing to compare the thrown type against.
      // That is a property of the language and of the engine's catalogue, and
      // the test says so instead of pretending the match happened.
      TacInstruction handler = theHandler(function);
      assertThat(namesNoClass(handler))
          .as("a catch clause of this language names no class")
          .isTrue();
      assertThat(handlersOf(function))
          .as("the program must declare the one handler the reading is about")
          .hasSize(1);
      assertThat(
              ExceptionTypeHierarchies.defaultMatcher()
                  .relation(SourceLanguage.TYPESCRIPT, "Error", null))
          .as("without a declared type on the handler the relation cannot be decided")
          .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
    }
  }

  // ---- ArkTS ----------------------------------------------------------------------------

  /** An ArkTS program whose try raises an Error. */
  private static final String ARKTS_SOURCE =
      String.join(
          "\n",
          "export function handle(failure: Error): string {",
          "  try {",
          "    throw failure;",
          "  } catch (error) {",
          "    return String(error);",
          "  }",
          "}");

  @Test
  @DisplayName("ArkTS: the throw carries the type it throws")
  void arktsTheThrowCarriesItsType(@TempDir Path directory) throws IOException {
    assumeTrue(ArktsSidecarClient.locateNode() != null, "the ArkTS front end needs Node");
    assumeTrue(ArktsSidecarClient.locateScript(null) != null, "the ArkTS sidecar is not built");

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      TacFunction function = parse(directory, "Index.ets", ARKTS_SOURCE, frontend);

      TacInstruction thrown = theThrow(function);
      assertThat(thrown.ctorType).as("a throw must name the type it throws").isNotNull();
      assertThat(thrown.ctorType.kind()).isEqualTo(TacType.Kind.CLASS);
      assertThat(thrown.ctorType.displayName()).isEqualTo("Error");

      TacInstruction handler = theHandler(function);
      assertThat(namesNoClass(handler))
          .as("a catch clause of this language names no class")
          .isTrue();
      assertThat(
              ExceptionTypeHierarchies.defaultMatcher()
                  .relation(SourceLanguage.ARKTS, "Error", null))
          .as("without a declared type on the handler the relation cannot be decided")
          .isEqualTo(ExceptionTypeMatcher.Relation.UNKNOWN);
      assertThat(handlersOf(function))
          .as("the program must declare the one handler the reading is about")
          .hasSize(1);
    }
  }

  // ---- the harness ----------------------------------------------------------------------

  /**
   * Parses a source with a real frontend, over the real wire.
   *
   * @param directory the directory the source is written into
   * @param fileName the file's name -- its extension is what the sidecar reads
   * @param source the source text
   * @param frontend the frontend under test
   * @return the function the source declares
   */
  private static TacFunction parse(
      Path directory, String fileName, String source, TacFrontend frontend) throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    return result.functions().stream()
        .filter(function -> function.name().equals("handle") || function.name().equals("Run"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the function under test is not in the stream"));
  }

  /**
   * Asserts that the throw of a known type reaches the handler that catches it, and only that one.
   *
   * @param function the function under test
   * @param language the language the function was written in
   * @param thrownTypeName the type the throw is expected to carry
   * @param matchingHandlerType the handler that catches it
   * @param shadowedHandlerType the handler that must NOT be reached
   */
  private static void assertTheCatchMatches(
      TacFunction function,
      SourceLanguage language,
      String thrownTypeName,
      String matchingHandlerType,
      String shadowedHandlerType) {

    TacInstruction thrown = theThrow(function);
    assertThat(thrown.ctorType).as("a throw must name the type it throws").isNotNull();
    assertThat(thrown.ctorType.kind())
        .as("only a class can be matched against a handler")
        .isEqualTo(TacType.Kind.CLASS);
    assertThat(thrown.ctorType.displayName()).isEqualTo(thrownTypeName);

    TacInstruction matching = theHandlerOf(function, matchingHandlerType);
    TacInstruction shadowed = theHandlerOf(function, shadowedHandlerType);

    // The COUNT, so that a reading says how much was measured: two handlers,
    // one of which the throw must not reach.
    assertThat(handlersOf(function))
        .as("the program must declare the two handlers the reading is about")
        .hasSize(2);

    assertThat(
            ExceptionTypeHierarchies.defaultMatcher()
                .relation(language, thrownTypeName, matchingHandlerType))
        .as("the handler that declares the thrown type must catch it")
        .isEqualTo(ExceptionTypeMatcher.Relation.CATCHES);

    TacCfg cfg = TacCfgBuilder.build(function);
    int throwBlock = blockOf(cfg, function, thrown);
    List<Integer> targets = cfg.exceptionSuccs(throwBlock);

    assertThat(targets)
        .as("the handler that catches the thrown type must be reached")
        .contains(blockOf(cfg, function, matching));
    assertThat(targets)
        .as("the handler that cannot catch the thrown type must not be reached")
        .doesNotContain(blockOf(cfg, function, shadowed));
    assertThat(targets)
        .as("the throw must reach exactly one handler: the one that catches it")
        .hasSize(1);
  }

  /** The function's catch handlers. */
  private static List<TacInstruction> handlersOf(TacFunction function) {
    List<TacInstruction> handlers = new java.util.ArrayList<>();
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CATCH_HANDLER) {
        handlers.add(instruction);
      }
    }
    return handlers;
  }

  /** The function's THROW, or a failure. */
  private static TacInstruction theThrow(TacFunction function) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.THROW) {
        return instruction;
      }
    }
    throw new AssertionError("the function must contain a THROW");
  }

  /** The function's first catch handler, or a failure. */
  private static TacInstruction theHandler(TacFunction function) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CATCH_HANDLER) {
        return instruction;
      }
    }
    throw new AssertionError("the function must contain a CATCH_HANDLER");
  }

  /**
   * Whether the instruction names no class at all.
   *
   * <p>A handler whose clause names no type carries no class: the wire carries no type, and the
   * Java side reads that as UNKNOWN. Either way there is no class to compare a thrown type against.
   */
  private static boolean namesNoClass(TacInstruction instruction) {
    return instruction.ctorType == null || instruction.ctorType.kind() != TacType.Kind.CLASS;
  }

  /** The catch handler that declares the given type, or a failure. */
  private static TacInstruction theHandlerOf(TacFunction function, String typeName) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CATCH_HANDLER
          && instruction.ctorType != null
          && typeName.equals(instruction.ctorType.displayName())) {
        return instruction;
      }
    }
    throw new AssertionError("no catch handler declares " + typeName);
  }

  /** The id of the block the instruction is in, or a failure. */
  private static int blockOf(TacCfg cfg, TacFunction function, TacInstruction instruction) {
    for (TacBasicBlock block : cfg.blocks()) {
      for (int index : block.indices()) {
        if (function.instructionAt(index) == instruction) {
          return block.id();
        }
      }
    }
    throw new AssertionError("the instruction is in no block");
  }
}
