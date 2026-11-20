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
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The TypeScript frontend: two modules that declare a class of the same name.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>The function table is keyed by the SIGNATURE. A signature that names its class by its simple
 * name is the same string in two files that each declare a class called {@code UserStore} -- so the
 * second one REPLACES the first, and one method body is gone from the program. There is no drop and
 * no problem to say so: the body simply is not there any more.
 *
 * <p>The same collision decides where a CALL goes. A call key is {@code owner.name/arity}, and a
 * key whose owner is a simple name addresses every method of that name in every class -- so the
 * call graph has to fall back on "the first same-named method it finds", which the engine's
 * schedule decides.
 *
 * <p>A class's identity is its MODULE plus its name, and the sidecar already said so for the
 * class's own function, its constructor and its accessors. The methods were the ones left out, and
 * this test pins that they now say it too.
 *
 * <p><b>Why this drives the real sidecar.</b> The module of a class is a fact the COMPILER knows
 * and the sidecar asks it for: the resolved signature names the declaration the call reaches, and
 * the declaration's file names the module. A test that only checked the mapping would pass if the
 * sidecar stopped asking -- and that is exactly the failure this is about.
 */
@DisplayName("TypeScript: two modules that declare the same class name keep their own members")
class TsModuleQualifiedOwnerTest {

  /** The class of the first module: the one the caller reaches. */
  private static final String STORE_A =
      """
      export class UserStore {
        run(s: string): string {
          return s;
        }
      }
      """;

  /** The class of the second module: the one the caller must not reach. */
  private static final String STORE_B =
      """
      export class UserStore {
        run(s: string): string {
          return "safe";
        }
      }
      """;

  /** The caller: it reaches the class of the FIRST module. */
  private static final String CALLER =
      """
      import { UserStore } from "./modA/store";

      export function go(userInput: string): void {
        const store = new UserStore();
        store.run(userInput);
      }
      """;

  @Test
  @DisplayName("two modules that declare the same class name do not share one member")
  void twoModulesThatDeclareTheSameClassNameDoNotShareOneMember(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> runSignatures = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("run".equals(function.name())) runSignatures.add(function.signature());
    }

    assertThat(runSignatures)
        .as(
            "the function table is keyed by the signature, so two members whose signatures are the"
                + " same string are ONE member -- and one of the two bodies would be gone from the"
                + " program, with no drop and no problem to say so")
        .hasSize(2)
        .doesNotHaveDuplicates();
    assertThat(runSignatures)
        .as("each signature names the module its class lives in")
        .containsExactlyInAnyOrder(
            "modA/store.UserStore.run(string):string", "modB/store.UserStore.run(string):string");
  }

  @Test
  @DisplayName("the class a member is declared in carries the module it lives in")
  void theClassAMemberIsDeclaredInCarriesTheModuleItLivesIn(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> enclosingClasses = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("run".equals(function.name()) && function.enclosingClass() != null) {
        enclosingClasses.add(function.enclosingClass());
      }
    }

    assertThat(enclosingClasses)
        .as("the declaring class is what tells two same-named classes apart")
        .containsExactlyInAnyOrder("modA/store.UserStore", "modB/store.UserStore");
  }

  @Test
  @DisplayName("a call names the class of the module it reaches")
  void aCallNamesTheClassOfTheModuleItReaches(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    TacFunction go =
        functions.stream()
            .filter(function -> "go".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the caller is not in the translation"));
    String callee =
        go.instructions().stream()
            .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
            .map(instruction -> instruction.calleeSig)
            .filter(key -> key != null && !key.isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError("the call is not in the translation"));

    assertThat(callee)
        .as(
            "the call key and the declaration have to name the same class: a key whose owner is a"
                + " simple name addresses every method of that name in every class, so the call"
                + " graph would answer with whichever of them it found first")
        .isEqualTo("modA/store.UserStore.run/1");
  }

  /** The three files of the project, translated through the real sidecar. */
  private static List<TacFunction> translate(Path directory) throws IOException {
    Path first = directory.resolve("modA").resolve("store.ts");
    Path second = directory.resolve("modB").resolve("store.ts");
    Path caller = directory.resolve("caller.ts");
    Files.createDirectories(first.getParent());
    Files.createDirectories(second.getParent());
    Files.writeString(first, STORE_A);
    Files.writeString(second, STORE_B);
    Files.writeString(caller, CALLER);

    // The source root is what makes the module path a PROJECT-wide identity. Without one every
    // file is its own project, and the module of a file is its own name -- which would leave two
    // same-named files in two directories indistinguishable. The scan always gives one
    // (FrontendRegistry builds the context), and so does this test.
    CompileContext context =
        new DefaultCompileContext(
            List.of(
                new CompileContext.Module("ts", directory, List.of(), List.of(), List.of(), null)));

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      List<TacFunction> functions = new ArrayList<>();
      functions.addAll(frontend.parse(STORE_A, first, context).functions());
      functions.addAll(frontend.parse(STORE_B, second, context).functions());
      functions.addAll(frontend.parse(CALLER, caller, context).functions());
      return functions;
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
