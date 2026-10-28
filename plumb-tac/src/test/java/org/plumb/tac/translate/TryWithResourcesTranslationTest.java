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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.printer.TacPrinter;

/**
 * A try-with-resources' resource is a declaration, and it has to be in the IR.
 *
 * <p><b>Why this matters.</b> The try statement's resources were never translated at all. The
 * resource variable was therefore never added to the builder's locals, so every reference to it
 * inside the body fell through the name resolver's {@code this.<name>} fallback and became a
 * FIELDLOAD off {@code this} with an {@code UNKNOWN} type. The initializer was never translated
 * either, so no taint could reach the resource at all.
 *
 * <p>The observable effect was that {@code try (ObjectInputStream ois = new
 * ObjectInputStream(untrusted)) { ois.readObject(); }} was structurally incapable of producing a
 * CWE-502 finding, while the same code outside a try-with-resources did produce one. A mitigation
 * and a vulnerability that differ only in their braces are indistinguishable to the engine -- which
 * is exactly the kind of blindness a security tool cannot have.
 */
class TryWithResourcesTranslationTest {

  @Test
  void theResourceVariableIsALocalOfTheMethod() {
    org.plumb.tac.ir.TacProgram program =
        translate(
            """
            class T {
              void m(java.io.InputStream in) throws Exception {
                try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(in)) {
                  ois.readObject();
                }
              }
            }
            """);

    TacFunction m = method(program);
    boolean resourceIsLocal = m.locals().stream().anyMatch(v -> "ois".equals(v.name()));
    assertThat(resourceIsLocal)
        .as("the resource must be a local, not a field off this\n" + TacPrinter.dump(program))
        .isTrue();
  }

  @Test
  void theResourceInitializerIsTranslated() {
    org.plumb.tac.ir.TacProgram program =
        translate(
            """
            class T {
              void m(java.io.InputStream in) throws Exception {
                try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(in)) {
                  ois.readObject();
                }
              }
            }
            """);

    boolean hasConstructorCall =
        method(program).instructions().stream()
            .anyMatch(
                i ->
                    i.kind == TacInstruction.Kind.CALL
                        && i.calleeSig != null
                        && i.calleeSig.contains("ObjectInputStream"));
    assertThat(hasConstructorCall)
        .as("the resource's initializer must be translated\n" + TacPrinter.dump(program))
        .isTrue();
  }

  @Test
  void theResourceReferenceIsNotAFieldLoadOffThis() {
    // The exact shape of the defect: the body's reference to the resource became
    // `this:Probe.ois`, a FIELDLOAD off `this` with an UNKNOWN type.
    org.plumb.tac.ir.TacProgram program =
        translate(
            """
            class T {
              void m(java.io.InputStream in) throws Exception {
                try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(in)) {
                  ois.readObject();
                }
              }
            }
            """);

    boolean loadsOffThis =
        method(program).instructions().stream()
            .anyMatch(
                i ->
                    i.kind == TacInstruction.Kind.FIELDLOAD
                        && i.lhs1 instanceof TacOperand.TacVar v
                        && "this".equals(v.name())
                        && "ois".equals(i.fieldName));
    assertThat(loadsOffThis)
        .as("the resource must not be read as a field off this\n" + TacPrinter.dump(program))
        .isFalse();
  }

  @Test
  void theSinkReceivesTheResourceItself() {
    // The consequence that mattered: the readObject call's receiver must be the resource local,
    // so a taint on the initializer can reach it.
    org.plumb.tac.ir.TacProgram program =
        translate(
            """
            class T {
              void m(java.io.InputStream in) throws Exception {
                try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(in)) {
                  ois.readObject();
                }
              }
            }
            """);

    TacInstruction readObject =
        method(program).instructions().stream()
            .filter(
                i ->
                    i.kind == TacInstruction.Kind.CALL
                        && i.calleeSig != null
                        && i.calleeSig.contains("readObject"))
            .findFirst()
            .orElse(null);

    assertThat(readObject)
        .as("readObject must be present\n" + TacPrinter.dump(program))
        .isNotNull();
    assertThat(readObject.args)
        .as("the receiver must be the resource local\n" + TacPrinter.dump(program))
        .hasSize(1);
    assertThat(readObject.args.get(0))
        .as(
            "the receiver must be the resource local, not a this-field\n%s",
            TacPrinter.dump(program))
        .isInstanceOf(TacOperand.TacVar.class);
    TacOperand.TacVar receiver = (TacOperand.TacVar) readObject.args.get(0);
    assertThat(receiver.name()).isEqualTo("ois");
  }

  @Test
  void anAlreadyDeclaredResourceIsNotRedeclared() {
    // `try (existing)` has no initializer. It must not become a fresh local that shadows the
    // parameter, and it must not crash the translator.
    org.plumb.tac.ir.TacProgram program =
        translate(
            """
            class T {
              void m(java.io.InputStream in) throws Exception {
                try (in) {
                  in.read();
                }
              }
            }
            """);

    long inLocals = method(program).locals().stream().filter(v -> "in".equals(v.name())).count();
    assertThat(inLocals)
        .as(
            "the existing name is already a parameter; it is not redeclared\n%s",
            TacPrinter.dump(program))
        .isZero();
  }

  /**
   * The method under test. It is looked up by name, not by signature, because a method that
   * declares a checked exception has a different signature than one that does not.
   */
  private static TacFunction method(org.plumb.tac.ir.TacProgram program) {
    return program.functions().stream().filter(f -> "m".equals(f.name())).findFirst().orElse(null);
  }

  /** Translate a source snippet into the program's functions. */
  private static org.plumb.tac.ir.TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("T.java"), src);
    return new TacTranslator(ps).translate();
  }
}
