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
import org.plumb.tac.ir.TacInstruction;

/**
 * The TypeScript frontend: the owner it gives to a call whose receiver is {@code this}.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A call key is {@code owner.name/arity}. The owner is what tells two methods of the same name
 * apart, and the call graph resolves a call to the function whose OWN SIGNATURE carries that owner.
 * So a call key without an owner does not merely lose a label: it names every method of that name
 * in every class, and the graph has to fall back on "the first same-named method it finds".
 *
 * <p>{@code this.m()} used to produce exactly such a key. The owner was asked of the compiler's own
 * type of the receiver, and the {@code this} type is not an object type -- the class-name lookup
 * drops every type that is not one -- so the answer was "no owner" for every {@code this} call in
 * the program. The receiver was right there in the source and the key said nothing about it.
 *
 * <p>The assertions here are about SHAPES: the key carries the class the method is written in, and
 * that class is the one the callee's own signature names -- otherwise the key still cannot be
 * resolved.
 */
@DisplayName("TypeScript: the owner it gives to a call whose receiver is this")
class TsReceiverOwnerTest {

  @Test
  @DisplayName("a call on this names the class the method is written in")
  void aCallOnThisNamesTheClassTheMethodIsWrittenIn(@TempDir Path directory) throws IOException {
    String source =
        """
        export class Owner {
          helper(): string {
            return "x";
          }

          run(): void {
            this.helper();
          }
        }
        """;
    TacFunction run = translate(source, directory, "app.ts", "run");

    assertThat(callKeyOf(run))
        .as(
            "the receiver is `this`, so the owner is the class the call is written in -- without"
                + " it the key names every method called `helper` in every class.  The owner is the"
                + " class's IDENTITY, so the module it lives in is part of it")
        .isEqualTo("app.Owner.helper/0");
  }

  @Test
  @DisplayName("the owner of a call on this is the class the callee declares itself in")
  void theOwnerOfACallOnThisIsTheClassTheCalleeDeclaresItselfIn(@TempDir Path directory)
      throws IOException {
    String source =
        """
        export class Owner {
          helper(): string {
            return "x";
          }

          run(): void {
            this.helper();
          }
        }
        """;
    TacProgramView view = translateAll(source, directory, "app.ts");

    String owner = ownerOf(view.callKeyOf("run"));
    String declared = ownerOf(view.signatureOf("helper"));
    assertThat(owner)
        .as(
            "the call key and the callee's own signature have to name the same class: that is"
                + " what lets the call graph resolve the call to THAT function")
        .isEqualTo(declared);
  }

  @Test
  @DisplayName("a call on another object still names the class of that object")
  void aCallOnAnotherObjectStillNamesTheClassOfThatObject(@TempDir Path directory)
      throws IOException {
    String source =
        """
        export class Other {
          helper(): string {
            return "x";
          }
        }

        export class Owner {
          run(other: Other): void {
            other.helper();
          }
        }
        """;
    TacFunction run = translate(source, directory, "app.ts", "run");

    assertThat(callKeyOf(run))
        .as(
            "the receiver is an object of a known class, so the owner is that class -- and the"
                + " owner is the class's IDENTITY, so the module it lives in is part of it")
        .isEqualTo("app.Other.helper/0");
  }

  @Test
  @DisplayName("a call on super names the base class")
  void aCallOnSuperNamesTheBaseClass(@TempDir Path directory) throws IOException {
    String source =
        """
        export class Base {
          helper(): string {
            return "x";
          }
        }

        export class Derived extends Base {
          run(): void {
            super.helper();
          }
        }
        """;
    TacFunction run = translate(source, directory, "app.ts", "run");

    assertThat(callKeyOf(run))
        .as("the receiver is the base object, so the owner is the base class")
        .isEqualTo("Base.helper/0");
  }

  /** The owner part of a call key: everything before the method name and the arity. */
  private static String ownerOf(String callKey) {
    int slash = callKey.indexOf('/');
    String qualified = slash < 0 ? callKey : callKey.substring(0, slash);
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? "" : qualified.substring(0, dot);
  }

  /** The call key of the first CALL in the function. */
  private static String callKeyOf(TacFunction function) {
    return function.instructions().stream()
        .filter(i -> i.kind == TacInstruction.Kind.CALL)
        .map(i -> i.calleeSig)
        .filter(key -> key != null && !key.isEmpty())
        .findFirst()
        .orElseThrow(() -> new AssertionError("the call is not in the stream"));
  }

  /** The function with the given name, translated through the real sidecar. */
  private static TacFunction translate(
      String source, Path directory, String fileName, String functionName) throws IOException {
    TacProgramView view = translateAll(source, directory, fileName);
    return view.function(functionName);
  }

  /** Every function of the source, translated through the real sidecar. */
  private static TacProgramView translateAll(String source, Path directory, String fileName)
      throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      return new TacProgramView(result.functions());
    }
  }

  /** The functions of one translation, addressed by name. */
  private static final class TacProgramView {

    private final List<TacFunction> functions;

    TacProgramView(List<TacFunction> functions) {
      this.functions = functions;
    }

    TacFunction function(String name) {
      return functions.stream()
          .filter(f -> name.equals(f.name()))
          .findFirst()
          .orElseThrow(() -> new AssertionError("the function " + name + " is not in the stream"));
    }

    String signatureOf(String name) {
      return function(name).signature();
    }

    String callKeyOf(String name) {
      return function(name).instructions().stream()
          .filter(i -> i.kind == TacInstruction.Kind.CALL)
          .map(i -> i.calleeSig)
          .filter(key -> key != null && !key.isEmpty())
          .findFirst()
          .orElseThrow(() -> new AssertionError("the call is not in the stream"));
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
