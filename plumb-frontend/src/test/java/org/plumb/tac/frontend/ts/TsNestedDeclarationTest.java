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
package org.plumb.tac.frontend.ts;

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
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The TypeScript frontend: a declaration inside a function is a function of its own.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A class declared inside a method body is a class of its own, and its members are functions of
 * the program. The sidecar lifts them out of the method they are written in, and it used to lose
 * them on the way: the functions were pushed into the list of pending functions AFTER that list had
 * already been replaced, so the push landed in an array nobody read any more. The class was in the
 * source, its methods were in the source, and the program had neither -- and nothing said so.
 *
 * <p>The second case is the other end of the same trade. A class whose body holds a nested class is
 * a class the COMPILER loses: the syntax tree it hands over stops at the nested class, and the
 * members that come after it are not in the class at all. The sidecar used to report that loss --
 * the class's own function carried the problem and the file's precision dropped -- because there was
 * nothing better to do. There is now: the sidecar takes the nested class out of the text the
 * compiler reads, so the compiler builds the outer class whole, and the members that come after the
 * nested class are members of their class again.
 *
 * <p>The middle case is the one that decides HOW the repair is recognised. A class that is cut off
 * right after a member still ends with a closing brace, so its own text looks complete; only the
 * positions say otherwise -- a class the compiler has finished ends AFTER its members, because the
 * closing brace of the class comes after the last one. And the repair has to find the class's REAL
 * body, which the compiler's node cannot say, because the node ends where the truncation starts.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and the compiler it imports, which npm installs. A machine without them
 * is missing an environment, not carrying a broken product -- the same reason the Go front end's
 * test skips when there is no Go toolchain. The message names the command that fixes it, so a skip
 * is a to-do and not a shrug.
 */
@DisplayName("TypeScript: a declaration inside a function is a function of its own")
class TsNestedDeclarationTest {

  /** A class declared inside a method body. */
  private static final String NESTED_IN_METHOD =
      """
      export class Outer {
        run(): void {
          class Inner {
            go(): void {
              const value = 1;
            }
          }
          const made = new Inner();
          made.go();
        }
      }
      """;

  /** A function declared inside a function body. */
  private static final String NESTED_FUNCTION =
      """
      export function outer(cmd: string): void {
        function inner(value: string): string {
          return value + cmd;
        }
        const out = inner("x");
      }
      """;

  /** A class whose body holds a nested class. */
  private static final String NESTED_IN_BODY =
      """
      export class Holder {
        class BodyInner {
          go(): void {
            const value = 1;
          }
        }
      }
      """;

  /** A class whose body holds a nested class BETWEEN two of its own methods. */
  private static final String NESTED_IN_MIDDLE =
      """
      export class Holder {
        before(): void {
          const value = 1;
        }
        class BodyInner {
          go(): void {
            const value = 2;
          }
        }
        after(): void {
          const value = 3;
        }
      }
      """;

  /** A class whose body holds a nested class and declares its own constructor. */
  private static final String NESTED_WITH_CONSTRUCTOR =
      """
      export class Holder {
        constructor() {
          const value = 1;
        }
        class BodyInner {
          go(): void {
            const value = 2;
          }
        }
      }
      """;

  /** The same class without a nested class: the control. */
  private static final String WITHOUT_NESTED =
      """
      export class Control {
        plain(): void {
          const value = 1;
        }
      }
      """;

  /** A class whose body holds a nested class and a method with a sink after it. */
  private static final String NESTED_THEN_SINK =
      """
      export class Holder {
        class BodyInner {
          go(): void {
            const value = 1;
          }
        }
        plain(cmd: string): void {
          eval(cmd);
        }
      }
      """;

  @Test
  @DisplayName("a class declared inside a method is in the program")
  void aClassDeclaredInsideAMethodIsInTheProgram(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_IN_METHOD, directory);

    assertThat(functions)
        .as(
            "a class declared inside a method body is a class of its own, and its methods are"
                + " functions of the program -- a class that is lifted out of the method must not be"
                + " lifted out of the program as well")
        .anyMatch(function -> "go".equals(function.name()) && function.enclosingClass() != null
            && function.enclosingClass().endsWith("Inner"));
  }

  @Test
  @DisplayName("a function declared inside a function is in the program")
  void aFunctionDeclaredInsideAFunctionIsInTheProgram(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_FUNCTION, directory);

    assertThat(functions)
        .as(
            "a function declared inside a function body is a function of its own -- a function that"
                + " is lifted out of the function it is written in must not be lifted out of the"
                + " program as well")
        .anyMatch(function -> "inner".equals(function.name()));
  }

  @Test
  @DisplayName("a class whose body holds a nested class is analysed whole")
  void aClassWhoseBodyHoldsANestedClassIsAnalysedWhole(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = parse(NESTED_IN_BODY, directory);

    assertThat(result.precision())
        .as(
            "the compiler's syntax tree stops this class at the nested class, so the repair takes"
                + " the nested class out of the text the compiler reads -- and with the class whole,"
                + " there is nothing left to report")
        .isEqualTo(IrPrecision.SOURCE_FULL);
    assertThat(result.functions())
        .as("the nested class is a class of its own, and its method is a function of the program")
        .anyMatch(function -> "go".equals(function.name()));
  }

  @Test
  @DisplayName("a class whose body holds a nested class between two methods keeps both methods")
  void aClassWhoseBodyHoldsANestedClassBetweenTwoMethodsKeepsBothMethods(
      @TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = parse(NESTED_IN_MIDDLE, directory);

    assertThat(result.functions())
        .as(
            "the compiler stops this class at the nested class, so the method after it came back as"
                + " a fragment of the FILE -- the repair has to put it back into the class it was"
                + " written in")
        .anyMatch(
            function ->
                "after".equals(function.name())
                    && function.enclosingClass() != null
                    && function.enclosingClass().endsWith("Holder"));
  }

  @Test
  @DisplayName("a class with a constructor whose body holds a nested class keeps the constructor")
  void aClassWithAConstructorWhoseBodyHoldsANestedClassKeepsTheConstructor(@TempDir Path directory)
      throws IOException {
    TacFrontend.ParseResult result = parse(NESTED_WITH_CONSTRUCTOR, directory);

    assertThat(result.functions())
        .as(
            "a class that declares its own constructor has THAT constructor as its own function --"
                + " and the repair must not lose it")
        .anyMatch(function -> "<init>".equals(function.name()));
    assertThat(result.precision())
        .as("with the class whole, the file keeps its full precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a class without a nested class is analysed whole")
  void aClassWithoutANestedClassIsAnalysedWhole(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = parse(WITHOUT_NESTED, directory);

    assertThat(result.precision())
        .as(
            "the control: a class whose body holds no nested class is in the tree whole, so its file"
                + " keeps its full precision -- without this, the cases above would pass for the wrong"
                + " reason")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a member after a nested class is a member of its class, not a call of the module")
  void aMemberAfterANestedClassIsAMemberOfItsClass(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_THEN_SINK, directory);

    assertThat(functions)
        .as(
            "the member after the nested class used to come back as a fragment of the FILE, and a"
                + " fragment was lowered as MODULE-level code -- so the program grew a function the"
                + " source does not have, and a call that names a method the source does not have"
                + " there")
        .noneMatch(
            function ->
                function.instructions().stream()
                    .anyMatch(
                        instruction ->
                            instruction.kind == TacInstruction.Kind.CALL
                                && instruction.calleeSig != null
                                && instruction.calleeSig.startsWith("plain/")));
  }

  @Test
  @DisplayName("the sink in a member after a nested class is a call")
  void theSinkInAMemberAfterANestedClassIsACall(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_THEN_SINK, directory);

    assertThat(functions)
        .as(
            "a fragment has no call expression inside it -- the member's body is not a tree the"
                + " compiler gave over -- so the sink in it was not a call at all, and a sink that is"
                + " not a call cannot be matched by any rule")
        .anyMatch(
            function ->
                function.instructions().stream()
                    .anyMatch(
                        instruction ->
                            instruction.kind == TacInstruction.Kind.CALL
                                && instruction.calleeSig != null
                                && instruction.calleeSig.contains("eval")));
  }

  @Test
  @DisplayName("the repair does not move the sink: it stays on the line the source has it on")
  void theRepairDoesNotMoveTheSink(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_THEN_SINK, directory);
    int sinkLine = lineOf(NESTED_THEN_SINK, "eval(cmd);");

    assertThat(functions)
        .as(
            "the repair takes the nested class out of the text the compiler reads -- and it has to"
                + " keep that text's LENGTH and its LINE COUNT, or every position below the nested"
                + " class moves and the finding points at a line the reader cannot see")
        .anyMatch(
            function ->
                function.instructions().stream()
                    .anyMatch(
                        instruction ->
                            instruction.kind == TacInstruction.Kind.CALL
                                && instruction.calleeSig != null
                                && instruction.calleeSig.contains("eval")
                                && instruction.line == sinkLine));
  }

  // ---- helpers -------------------------------------------------------------------------------

  /**
   * The one-based line of the source line that holds a piece of text.
   *
   * <p>The expected line is derived from the source rather than written down, so that the
   * assertion says what it means -- the lowering and the repair agree about where the sink is --
   * and does not become a number that has to be edited when the fixture changes.
   */
  private static int lineOf(String source, String needle) {
    List<String> lines = source.lines().toList();
    for (int i = 0; i < lines.size(); i += 1) {
      if (lines.get(i).contains(needle)) {
        return i + 1;
      }
    }
    throw new IllegalArgumentException("the source has no line that holds " + needle);
  }

  /** The functions of the source, translated through the real sidecar. */
  private static List<TacFunction> functionsOf(String source, Path directory) throws IOException {
    return parse(source, directory).functions();
  }

  /** The parse of the source, through the real sidecar. */
  private static TacFrontend.ParseResult parse(String source, Path directory) throws IOException {
    Path file = directory.resolve("app.ts");
    Files.writeString(file, source);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      return frontend.parse(source, file, null);
    }
  }

  /**
   * Whether the sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the compiler the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean sidecarAvailable() {
    if (TsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = TsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("typescript"));
  }
}
