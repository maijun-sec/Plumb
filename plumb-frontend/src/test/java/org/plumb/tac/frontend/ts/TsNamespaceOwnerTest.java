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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.DefaultCompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The TypeScript frontend: a namespace is part of the identity of what it holds.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>A namespace is a SCOPE. A class declared in one is not the class of the same name declared at
 * the top level, and two namespaces of the same name in two files are not the same scope -- so the
 * namespace has to be part of the identity, the way the module is.
 *
 * <p>Without it, the function table (keyed by the signature) holds ONE member for two same-named
 * namespaces: the second replaces the first and one body is gone from the program, with no drop and
 * no problem to say so.
 *
 * <p><b>And the two ends of a call have to agree.</b> The declaration side knows the namespace
 * because it walks the namespace tree; the call side knows it because the compiler says which
 * namespaces the receiver's declaration lives in. When the two spell the class differently the call
 * graph cannot connect the call to the declaration, and the taint stops at the call.
 *
 * <p><b>Why this drives the real sidecar.</b> The namespace path of a receiver is a fact the
 * COMPILER knows. A test that only checked the mapping would pass if the sidecar stopped asking --
 * and that is exactly the failure this is about.
 */
@DisplayName("TypeScript: a namespace is part of the identity of what it holds")
class TsNamespaceOwnerTest {

  /** The namespace of the first module: the one the caller reaches. */
  private static final String STORE_A =
      """
      export namespace Utils {
        export const VERSION = "1";

        export function helper(input: string): string {
          return input;
        }

        export class Inner {
          run(s: string): string {
            return s;
          }
        }

        export namespace Deep {
          export function deep(s: string): string {
            return s;
          }
        }
      }
      """;

  /** The namespace of the second module: the one the caller must not reach. */
  private static final String STORE_B =
      """
      export namespace Utils {
        export function helper(input: string): string {
          return "safe";
        }
      }
      """;

  /** The caller: it reaches the namespace of the FIRST module. */
  private static final String CALLER =
      """
      import { Utils } from "./modA/store";

      export function go(userInput: string): void {
        Utils.helper(userInput);
        const inner = new Utils.Inner();
        inner.run(userInput);
        Utils.Deep.deep(userInput);
      }
      """;

  /** A file that declares only types: a type has no run-time effect at all. */
  private static final String TYPES =
      """
      export interface Shape {
        id: string;
      }

      export type Alias = string;
      """;

  @Test
  @DisplayName("two namespaces of the same name do not share one member")
  void twoNamespacesOfTheSameNameDoNotShareOneMember(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> helperSignatures = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("helper".equals(function.name())) helperSignatures.add(function.signature());
    }

    assertThat(helperSignatures)
        .as(
            "the function table is keyed by the signature, so two members whose signatures are the"
                + " same string are ONE member -- and one of the two bodies would be gone from the"
                + " program, with no drop and no problem to say so")
        .hasSize(2)
        .doesNotHaveDuplicates();
    assertThat(helperSignatures)
        .as("each signature names the namespace its member lives in")
        .containsExactlyInAnyOrder(
            "modA/store.Utils.helper(string):string", "modB/store.Utils.helper(string):string");
  }

  @Test
  @DisplayName("the class a namespace member is declared in carries the namespace")
  void theClassANamespaceMemberIsDeclaredInCarriesTheNamespace(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> enclosingClasses = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("helper".equals(function.name()) && function.enclosingClass() != null) {
        enclosingClasses.add(function.enclosingClass());
      }
    }

    assertThat(enclosingClasses)
        .as("the declaring class is what tells two same-named namespaces apart")
        .containsExactlyInAnyOrder("modA/store.Utils", "modB/store.Utils");
  }

  @Test
  @DisplayName("a call to a namespace member names the same class the declaration does")
  void aCallToANamespaceMemberNamesTheSameClassTheDeclarationDoes(@TempDir Path directory)
      throws IOException {
    String callee = calleeOf(directory, "helper");

    assertThat(callee)
        .as(
            "the call key and the declaration have to name the same class: a key whose owner is a"
                + " simple name addresses every method of that name in every namespace, so the"
                + " call graph would answer with whichever of them it found first")
        .isEqualTo("modA/store.Utils.helper/1");
  }

  @Test
  @DisplayName("a class in a namespace carries the namespace it lives in")
  void aClassInANamespaceCarriesTheNamespaceItLivesIn(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> runSignatures = new ArrayList<>();
    List<String> enclosingClasses = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("run".equals(function.name())) {
        runSignatures.add(function.signature());
        if (function.enclosingClass() != null) enclosingClasses.add(function.enclosingClass());
      }
    }

    assertThat(runSignatures)
        .as("a class in a namespace is not the class of the same name at the top level")
        .containsExactly("modA/store.Utils.Inner.run(string):string");
    assertThat(enclosingClasses)
        .as("the declaring class carries the namespace it lives in")
        .containsExactly("modA/store.Utils.Inner");
  }

  @Test
  @DisplayName("a call to a class in a namespace names the same class the declaration does")
  void aCallToAClassInANamespaceNamesTheSameClassTheDeclarationDoes(@TempDir Path directory)
      throws IOException {
    String callee = calleeOf(directory, "run");

    assertThat(callee)
        .as("the call key and the declaration have to name the same class")
        .isEqualTo("modA/store.Utils.Inner.run/1");
  }

  @Test
  @DisplayName("a nested namespace carries the whole path")
  void aNestedNamespaceCarriesTheWholePath(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> deepSignatures = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("deep".equals(function.name())) deepSignatures.add(function.signature());
    }

    assertThat(deepSignatures)
        .as("the namespace inside the namespace is part of the path")
        .containsExactly("modA/store.Utils.Deep.deep(string):string");
    assertThat(calleeOf(directory, "deep"))
        .as("the call key and the declaration have to name the same class")
        .isEqualTo("modA/store.Utils.Deep.deep/1");
  }

  @Test
  @DisplayName("a file that declares a type keeps its precision")
  void aFileThatDeclaresATypeKeepsItsPrecision(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("types.ts");
    Files.writeString(file, TYPES);

    CompileContext context =
        new DefaultCompileContext(
            List.of(
                new CompileContext.Module("ts", directory, List.of(), List.of(), List.of(), null)));

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(TYPES, file, context);

      assertThat(result.problems())
          .as("a type has no run-time effect at all, so there is nothing to report")
          .isEmpty();
      assertThat(result.precision())
          .as("reporting a type would downgrade every file that happens to declare an interface")
          .isEqualTo(IrPrecision.SOURCE_FULL);
    }
  }

  @Test
  @DisplayName("the namespace's own code is lowered into a stream of its own")
  void theNamespacesOwnCodeIsLoweredIntoAStreamOfItsOwn(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> signatures = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("__top".equals(function.name())) signatures.add(function.signature());
    }

    assertThat(signatures)
        .as(
            "the namespace's own code runs when the module that holds it is loaded, and it gets a"
                + " stream of its own: a stream of its own keeps that stream's signature unique")
        .contains("modA/store.Utils.__top()");
  }

  /** The three files of the project, translated through the real sidecar. */
  private static List<TacFunction> translate(Path directory) throws IOException {
    CompileContext context = writeProject(directory);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      List<TacFunction> functions = new ArrayList<>();
      functions.addAll(
          frontend
              .parse(STORE_A, directory.resolve("modA").resolve("store.ts"), context)
              .functions());
      functions.addAll(
          frontend
              .parse(STORE_B, directory.resolve("modB").resolve("store.ts"), context)
              .functions());
      functions.addAll(frontend.parse(CALLER, directory.resolve("caller.ts"), context).functions());
      return functions;
    }
  }

  /**
   * The callee key of the call to the method of the given name, in the caller.
   *
   * @param directory the project
   * @param method the method the call reaches
   * @return the callee key
   */
  private static String calleeOf(Path directory, String method) throws IOException {
    CompileContext context = writeProject(directory);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      for (TacFunction function :
          frontend.parse(CALLER, directory.resolve("caller.ts"), context).functions()) {
        if (!"go".equals(function.name())) continue;
        for (TacInstruction instruction : function.instructions()) {
          if (instruction.kind != TacInstruction.Kind.CALL) continue;
          String key = instruction.calleeSig;
          if (key != null && key.contains("." + method + "/")) return key;
        }
      }
    }
    throw new AssertionError("the call to " + method + " is not in the translation");
  }

  /**
   * The three files of the project, written.
   *
   * <p>The source root is what makes the module path a PROJECT-wide identity. Without one every
   * file is its own project, and the module of a file is its own name -- which would leave two
   * same-named files in two directories indistinguishable. The scan always gives one
   * (FrontendRegistry builds the context), and so does this test.
   *
   * @param directory the project
   * @return the compile context
   */
  private static CompileContext writeProject(Path directory) throws IOException {
    Path first = directory.resolve("modA").resolve("store.ts");
    Path second = directory.resolve("modB").resolve("store.ts");
    Files.createDirectories(first.getParent());
    Files.createDirectories(second.getParent());
    Files.writeString(first, STORE_A);
    Files.writeString(second, STORE_B);
    Files.writeString(directory.resolve("caller.ts"), CALLER);

    return new DefaultCompileContext(
        List.of(new CompileContext.Module("ts", directory, List.of(), List.of(), List.of(), null)));
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
