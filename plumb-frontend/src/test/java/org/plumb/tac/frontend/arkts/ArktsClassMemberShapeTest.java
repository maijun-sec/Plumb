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
package org.plumb.tac.frontend.arkts;

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
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacMemberKind;
import org.plumb.tac.ir.TacOperand;

/**
 * The ArkTS frontend: a struct's member is ONE location.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>In this language an object is a NAMESPACE: {@code this.x} and {@code this["x"]} are the same
 * location, and the location's identity is the pair (object, key). That is what the engine's keyed
 * namespace carries.
 *
 * <p>A member's DECLARATION used to break that rule. It was lowered as a field write, while every
 * READ of the member was a keyed container read. So one location had two mechanisms, and the two
 * never met: a value written by a declaration never reached the place that reads the member, and
 * nothing said so.
 *
 * <p>The assertions here are about the SHAPE, and the shape is a pair: the receiver and the key. A
 * declaration and a read of the same member have to agree on BOTH, or they are two locations wearing
 * one name.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and the compiler it imports, which npm installs. A machine without them
 * is missing an environment, not carrying a broken product. The message names the command that
 * fixes it, so a skip is a to-do and not a shrug.
 */
@DisplayName("ArkTS: a struct's member is one location")
class ArktsClassMemberShapeTest {

  /** A struct with a state member, a private member and a method that reads both. */
  private static final String SOURCE =
      """
      @Entry
      @Component
      struct ProfilePage {
        @State message: string = 'hello'
        private secret: string = 'hidden'

        build() {
          Column() {
            Text(this.message)
          }
        }

        render(input: string): string {
          this.message = input;
          return this.message + this.secret;
        }
      }
      """;

  @Test
  @DisplayName("a member's declaration is a keyed store on the instance")
  void aMembersDeclarationIsAKeyedStoreOnTheInstance(@TempDir Path directory) throws IOException {
    TacInstruction declaration = declarationOf(SOURCE, directory, "message");

    assertThat(declaration)
        .as(
            "the declaration of a member must be a store, so that the value has a place -- and it"
                + " must be a KEYED store, because a read of the member is one")
        .isNotNull();
    assertThat(receiverNameOf(declaration))
        .as("a member belongs to the instance, so the declaration writes the instance")
        .isEqualTo("this");
  }

  @Test
  @DisplayName("a member's declaration and its read are the same location")
  void aMembersDeclarationAndItsReadAreTheSameLocation(@TempDir Path directory) throws IOException {
    TacInstruction declaration = declarationOf(SOURCE, directory, "message");
    TacInstruction read = loadWithKey(instructionsOf(SOURCE, directory), "message");

    assertThat(declaration).as("the member must be declared").isNotNull();
    assertThat(read).as("the member must be read").isNotNull();
    assertThat(receiverNameOf(read))
        .as("the read and the declaration must name the same receiver")
        .isEqualTo(receiverNameOf(declaration));
    assertThat(keyOf(read))
        .as("the read and the declaration must name the same key")
        .isEqualTo(keyOf(declaration));
  }

  @Test
  @DisplayName("a member is never stored as a field")
  void aMemberIsNeverStoredAsAField(@TempDir Path directory) throws IOException {
    List<TacInstruction> all = instructionsOf(SOURCE, directory);

    assertThat(all)
        .as(
            "in this language a property is an entry of a namespace, so a member must not become a"
                + " field access -- the engine's field rules are for the languages whose objects"
                + " are records")
        .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDSTORE)
        .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);
  }

  // ---- helpers -------------------------------------------------------------------------------

  /** Every instruction of the translated program. */
  private static List<TacInstruction> instructionsOf(String source, Path directory)
      throws IOException {
    List<TacInstruction> all = new ArrayList<>();
    for (TacFunction function : functionsOf(source, directory)) {
      all.addAll(function.instructions());
    }
    return all;
  }

  /** The functions of the source, translated through the real sidecar. */
  private static List<TacFunction> functionsOf(String source, Path directory) throws IOException {
    Path file = directory.resolve("Page.ets");
    Files.writeString(file, source);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      return result.functions();
    }
  }

  /** The first store whose key is the given name. */
  private static TacInstruction storeWithKey(List<TacInstruction> instructions, String key) {
    return instructions.stream()
        .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
        .filter(i -> key.equals(keyOf(i)))
        .findFirst()
        .orElse(null);
  }

  /**
   * The store that DECLARES the member.
   *
   * <p>The declaration lives in the class's own initialiser, and only there: an assignment in a
   * method writes the same member, and finding that one instead would make the assertion pass for
   * the wrong reason -- which is exactly what a mutation test is there to catch.
   */
  private static TacInstruction declarationOf(String source, Path directory, String key)
      throws IOException {
    for (TacFunction function : functionsOf(source, directory)) {
      if (function.memberKind() != TacMemberKind.FIELD_INITIALIZER) {
        continue;
      }
      TacInstruction store = storeWithKey(function.instructions(), key);
      if (store != null) {
        return store;
      }
    }
    return null;
  }

  /** The first load whose key is the given name. */
  private static TacInstruction loadWithKey(List<TacInstruction> instructions, String key) {
    return instructions.stream()
        .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
        .filter(i -> key.equals(keyOf(i)))
        .findFirst()
        .orElse(null);
  }

  /** The key of an instruction, when it has one. */
  private static String keyOf(TacInstruction instruction) {
    if (!(instruction.lhs2 instanceof TacOperand.Const constant)) {
      return null;
    }
    if (constant.kind() != TacOperand.Const.Kind.STRING) {
      return null;
    }
    return String.valueOf(constant.value());
  }

  /** The name of the receiver of an instruction, when it has one. */
  private static String receiverNameOf(TacInstruction instruction) {
    if (instruction.lhs1 instanceof TacOperand.TacVar variable) {
      return variable.name();
    }
    return null;
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
    if (ArktsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = ArktsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }
}
