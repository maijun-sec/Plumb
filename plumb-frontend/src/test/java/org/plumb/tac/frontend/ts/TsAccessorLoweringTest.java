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
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacMemberKind;

/**
 * The TypeScript frontend: an auto-accessor's getter and setter are TWO functions.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>An auto-accessor -- {@code accessor value: string = "seed";} -- is a getter and a setter, and
 * the front end lowers it into two functions of the class. The explicit pair -- {@code get plain()}
 * and {@code set plain()} -- is lowered the same way.
 *
 * <p>The function table is keyed by the SIGNATURE. Two functions that share one signature replace
 * each other, so of the two, one is gone from the program: the getter's body, or the setter's. The
 * explicit pair keeps the two apart, because the setter's signature carries the value it takes. The
 * auto-accessor used to give both of them the same signature, so one of the two was lost.
 *
 * <p>The assertions here are about the SHAPE of the two functions, not about the instructions they
 * carry: what has to hold is that the two signatures differ, and that the setter's signature names
 * its parameter.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and the compiler it imports, which npm installs. A machine without them
 * is missing an environment, not carrying a broken product -- the same reason the Go front end's
 * test skips when there is no Go toolchain. The message names the command that fixes it, so a skip
 * is a to-do and not a shrug.
 */
@DisplayName("TypeScript: an auto-accessor is a getter and a setter, and they are two functions")
class TsAccessorLoweringTest {

  /** A class with an auto-accessor, an explicit accessor pair, and a plain data property. */
  private static final String SOURCE =
      """
      export class Holder {
        accessor value: string = "seed";
        data: string = "plain";

        get plain(): string {
          return this.value;
        }

        set plain(v: string) {
          this.value = v;
        }

        read(): string {
          return this.value;
        }
      }
      """;

  @Test
  @DisplayName("an auto-accessor's getter and setter do not share a signature")
  void anAutoAccessorsGetterAndSetterDoNotShareASignature(@TempDir Path directory)
      throws IOException {
    TacFunction getter = accessorOf(SOURCE, directory, "value", TacMemberKind.GETTER);
    TacFunction setter = accessorOf(SOURCE, directory, "value", TacMemberKind.SETTER);

    assertThat(getter).as("the auto-accessor's getter must be in the program").isNotNull();
    assertThat(setter).as("the auto-accessor's setter must be in the program").isNotNull();
    assertThat(getter.signature())
        .as(
            "the function table is keyed by the signature, so a getter and a setter that share one"
                + " replace each other and one of the two is gone from the program")
        .isNotEqualTo(setter.signature());
  }

  @Test
  @DisplayName("the setter's signature names the value it takes")
  void theSettersSignatureNamesTheValueItTakes(@TempDir Path directory) throws IOException {
    TacFunction setter = accessorOf(SOURCE, directory, "value", TacMemberKind.SETTER);

    assertThat(setter).as("the auto-accessor's setter must be in the program").isNotNull();
    assertThat(parameterListOf(setter.signature()))
        .as(
            "the setter takes the value it is given, and the signature is the only thing that says"
                + " which of the two accessors a function is")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the auto-accessor keeps the two accessors apart the way the explicit pair does")
  void theAutoAccessorKeepsTheTwoAccessorsApartTheWayTheExplicitPairDoes(@TempDir Path directory)
      throws IOException {
    TacFunction autoGetter = accessorOf(SOURCE, directory, "value", TacMemberKind.GETTER);
    TacFunction autoSetter = accessorOf(SOURCE, directory, "value", TacMemberKind.SETTER);
    TacFunction plainGetter = accessorOf(SOURCE, directory, "plain", TacMemberKind.GETTER);
    TacFunction plainSetter = accessorOf(SOURCE, directory, "plain", TacMemberKind.SETTER);

    assertThat(autoGetter).as("the auto-accessor's getter must be in the program").isNotNull();
    assertThat(autoSetter).as("the auto-accessor's setter must be in the program").isNotNull();
    assertThat(plainGetter).as("the explicit getter must be in the program").isNotNull();
    assertThat(plainSetter).as("the explicit setter must be in the program").isNotNull();
    assertThat(parameterListOf(autoSetter.signature()))
        .as("the auto-accessor's setter takes the value, and its signature says so")
        .isEqualTo(parameterListOf(plainSetter.signature()));
  }

  @Test
  @DisplayName("a plain data property is neither a getter nor a setter")
  void aPlainDataPropertyIsNeitherAGetterNorASetter(@TempDir Path directory) throws IOException {
    List<TacFunction> accessors = accessorsNamed(SOURCE, directory, "data");

    assertThat(accessors)
        .as(
            "a property without an accessor is a plain member of the namespace, and it has no"
                + " accessor function of its own")
        .isEmpty();
  }

  // ---- helpers -------------------------------------------------------------------------------

  /**
   * The function of the given kind that the given member declares.
   *
   * <p>The member's NAME is not enough: the auto-accessor and the explicit accessor are two
   * different members, and finding the wrong one would make the assertion pass for the wrong
   * reason.
   */
  private static TacFunction accessorOf(
      String source, Path directory, String member, TacMemberKind kind) throws IOException {
    for (TacFunction function : accessorsNamed(source, directory, member)) {
      if (function.memberKind() == kind) {
        return function;
      }
    }
    return null;
  }

  /** The accessor functions of the given member. */
  private static List<TacFunction> accessorsNamed(String source, Path directory, String member)
      throws IOException {
    List<TacFunction> accessors = new java.util.ArrayList<>();
    for (TacFunction function : functionsOf(source, directory)) {
      if (function.name().equals(member)
          && (function.memberKind() == TacMemberKind.GETTER
              || function.memberKind() == TacMemberKind.SETTER)) {
        accessors.add(function);
      }
    }
    return accessors;
  }

  /**
   * The parameter list of a signature: the text between its parentheses.
   *
   * <p>The signature of a function is {@code owner.name(ParamTypes):Return}, and the parameter list
   * is what says how many values the function takes.
   */
  private static String parameterListOf(String signature) {
    if (signature == null) {
      return "";
    }
    int open = signature.indexOf('(');
    int close = signature.indexOf(')', open + 1);
    if (open < 0 || close < 0) {
      return "";
    }
    return signature.substring(open + 1, close);
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
