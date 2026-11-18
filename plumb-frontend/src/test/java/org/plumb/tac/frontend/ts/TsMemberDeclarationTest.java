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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacMemberKind;

/**
 * The TypeScript frontend: a member's DECLARATION is recorded, whether or not it has a value yet.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>In this language an object is a NAMESPACE, so a class member is an entry of that namespace and
 * not a field. The TAC therefore has no instruction that says "this member exists": the declaration
 * is carried by the member annotations, and a rule that asks "is this member ever read?" has to
 * start from the declaration.
 *
 * <p>The declaration used to be recorded only for the members that had an initial value. A member
 * declared without one -- {@code private timeout: number;} -- is written later, in the constructor or
 * in a method, and until it is, the only thing the TAC knew about it was nothing at all. So the
 * member was invisible to every rule that asks about members, and the invisible ones are exactly the
 * ones a reader cannot check by reading the source.
 *
 * <p>A parameter property is a member too -- {@code constructor(private id: string)} declares one --
 * and it has no initial value either, because the value comes from the parameter.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and the compiler it imports, which npm installs. A machine without them
 * is missing an environment, not carrying a broken product -- the same reason the Go front end's
 * test skips when there is no Go toolchain. The message names the command that fixes it, so a skip
 * is a to-do and not a shrug.
 */
@DisplayName("TypeScript: a member's declaration is recorded")
class TsMemberDeclarationTest {

  /** A class with a member that has a value, one that has none, and a parameter property. */
  private static final String SOURCE =
      """
      export class Session {
        private ready: boolean = true;
        private timeout: number;
        public label: string;

        constructor(private id: string, timeout: number) {
          this.timeout = timeout;
        }

        run(): void {
          const state = this.ready;
        }
      }
      """;

  @Test
  @DisplayName("a member with no initial value is still declared")
  void aMemberWithNoInitialValueIsStillDeclared(@TempDir Path directory) throws IOException {
    Map<String, List<String>> declarations = declarationsOf(SOURCE, directory);

    assertThat(declarations)
        .as(
            "a member declared without a value is written later; the declaration is still a"
                + " declaration, and a rule that asks about the member has to start from it")
        .containsKey("timeout");
    assertThat(declarations.get("timeout"))
        .as("and the declaration says what the member is")
        .contains("private");
  }

  @Test
  @DisplayName("a member with an initial value keeps its declaration")
  void aMemberWithAnInitialValueKeepsItsDeclaration(@TempDir Path directory) throws IOException {
    Map<String, List<String>> declarations = declarationsOf(SOURCE, directory);

    assertThat(declarations).containsKey("ready");
    assertThat(declarations.get("ready")).contains("private");
  }

  @Test
  @DisplayName("a parameter property is declared as a member")
  void aParameterPropertyIsDeclaredAsAMember(@TempDir Path directory) throws IOException {
    Map<String, List<String>> declarations = declarationsOf(SOURCE, directory);

    assertThat(declarations)
        .as(
            "a parameter property declares a member: its value comes from the parameter, so it has"
                + " no initial value, and the declaration is the only trace of it")
        .containsKey("id");
    assertThat(declarations.get("id")).contains("private");
  }

  @Test
  @DisplayName("the declaration belongs to the class that declares the member")
  void theDeclarationBelongsToTheClassThatDeclaresTheMember(@TempDir Path directory)
      throws IOException {
    TacFunction owner = classOwnerOf(SOURCE, directory);

    assertThat(owner).as("the class must have a function of its own").isNotNull();
    assertThat(owner.enclosingClass())
        .as("the declaration names the class it belongs to, so two classes' members of the same name"
            + " are two members")
        .isEqualTo("Session");
    assertThat(owner.memberKind())
        .as("the class's own function is where the class's facts are recorded")
        .isIn(TacMemberKind.CONSTRUCTOR, TacMemberKind.FIELD_INITIALIZER);
  }

  // ---- helpers -------------------------------------------------------------------------------

  /** The member declarations of the source's class, by member name. */
  private static Map<String, List<String>> declarationsOf(String source, Path directory)
      throws IOException {
    TacFunction owner = classOwnerOf(source, directory);
    assertThat(owner).as("the class must have a function of its own").isNotNull();
    return owner.fieldAnnotations();
  }

  /** The function that carries the class's own facts. */
  private static TacFunction classOwnerOf(String source, Path directory) throws IOException {
    for (TacFunction function : functionsOf(source, directory)) {
      if (function.memberKind() == TacMemberKind.CONSTRUCTOR
          || function.memberKind() == TacMemberKind.FIELD_INITIALIZER) {
        return function;
      }
    }
    return null;
  }

  /** The functions of the source, translated through the real sidecar. */
  private static List<TacFunction> functionsOf(String source, Path directory) throws IOException {
    Path file = directory.resolve("app.ts");
    Files.writeString(file, source);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      return result.functions();
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
