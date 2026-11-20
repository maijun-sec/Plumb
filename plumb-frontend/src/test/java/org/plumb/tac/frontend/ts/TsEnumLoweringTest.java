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
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The TypeScript frontend: an enum is lowered into the entries of its own object.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>Before this, the TypeScript sidecar had NO enum lowering at all: an enum statement reached the
 * catch-all branch, reported UNHANDLED_NODE, and the whole file's precision dropped. The enum's
 * members -- and every sink in them -- were not in the TAC at all.
 *
 * <p><b>The write and the read have to be the same mechanism.</b> An enum member is an entry of the
 * enum's own namespace, exactly like a static field of a class is an entry of the class object. So
 * the member is written with a keyed store whose key is the member's name, and the read {@code
 * Colour.RED} looks in the same place. A lowering that writes the members somewhere else -- as
 * locals of a synthetic function, say -- puts the two halves in two different mechanisms, and they
 * can never meet.
 *
 * <p><b>The value follows the PREVIOUS member.</b> A member without an initialiser takes the value
 * after the previous member's value, not after the number of the members: in {@code A = 5, B} the
 * value of B is 6.
 *
 * <p><b>And nothing is invented.</b> When the compiler's own syntax tree is cut off, the member at
 * the cut is the head of what was lost: it gets no value, and the loss is reported.
 *
 * <h2>Why this drives the real sidecar</h2>
 *
 * <p>The compiler's tree is the fact here, and only the real compiler produces it. A test that
 * checked the mapping would pass if the sidecar stopped asking.
 */
@DisplayName("TypeScript: an enum is lowered into the entries of its own object")
class TsEnumLoweringTest {

  /** The enums of the first module: the one the caller reaches. */
  private static final String STORE_A =
      """
      export enum Plain {
        A,
        B,
      }

      export enum Mixed {
        A = 5,
        B,
      }
      """;

  /** The enum of the second module: the one the caller must not reach. */
  private static final String STORE_B =
      """
      export enum Plain {
        A,
        B,
      }
      """;

  /** An enum inside a namespace: its identity carries the namespace. */
  private static final String NAMESPACED =
      """
      export namespace Holder {
        export enum Inner {
          A,
          B,
        }
      }
      """;

  /** The caller: it reads the members of the enums. */
  private static final String CALLER =
      """
      import { Plain, Mixed } from "./modA/store";
      import { Holder } from "./ns";

      export function go(cmd: string): number {
        return Plain.A + Mixed.B + Holder.Inner.A + cmd.length;
      }
      """;

  /** An enum whose first member's value is computed: the next one cannot be counted. */
  private static final String COMPUTED =
      """
      export enum Computed {
        A = 1 + 1,
        B,
      }
      """;

  /**
   * An enum that holds a METHOD.
   *
   * <p>The compiler's syntax tree for this one is cut off: the enum's own text ends at the method's
   * name, and the method comes back as a stray statement of the file.
   */
  private static final String TRUNCATED =
      """
      export enum With {
        A = 1;

        run(s: string): string {
          return s;
        }
      }
      """;

  @Test
  @DisplayName("an enum member is an entry of the enum object")
  void anEnumMemberIsAnEntryOfTheEnumObject(@TempDir Path directory) throws IOException {
    TacFunction plain = functionNamed(translate(directory), "Plain");

    TacInstruction store = storeWithKey(plain, "A");

    assertThat(store)
        .as(
            "the member has to be written as a keyed store on the enum object: the read"
                + " `Plain.A` is a keyed load on the same object, and two different"
                + " mechanisms never meet")
        .isNotNull();
    assertThat(objectOf(store))
        .as("the member is written on the enum, not on the module and not on a local")
        .isEqualTo("Plain");
    assertThat(valueOf(store))
        .as("the first member of an enum without values is zero")
        .isEqualTo(0.0d);
  }

  @Test
  @DisplayName("the read of a member uses the same mechanism as the write")
  void theReadOfAMemberUsesTheSameMechanismAsTheWrite(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    TacInstruction write = storeWithKey(functionNamed(functions, "Plain"), "A");
    TacInstruction read = loadWithKey(functionNamed(functions, "go"), "A", "Plain");

    assertThat(write).as("the write of a member is a keyed store").isNotNull();
    assertThat(read).as("the read of a member is a keyed load").isNotNull();
    assertThat(objectOf(read))
        .as("the read has to name the same object the write names, or the value cannot reach it")
        .isEqualTo(objectOf(write));
  }

  @Test
  @DisplayName("a member without a value takes the value after the previous one")
  void aMemberWithoutAValueTakesTheValueAfterThePreviousOne(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory);

    TacFunction mixed = functionNamed(functions, "Mixed");

    assertThat(valueOf(storeWithKey(mixed, "A")))
        .as("the member with a value keeps it")
        .isEqualTo(5.0d);
    assertThat(valueOf(storeWithKey(mixed, "B")))
        .as("the member after a value takes the value after THAT value, not after zero")
        .isEqualTo(6.0d);
    assertThat(valueOf(storeWithKey(functionNamed(functions, "Plain"), "B")))
        .as("two members without values are numbered from zero")
        .isEqualTo(1.0d);
  }

  @Test
  @DisplayName("a file that declares an enum keeps its precision")
  void aFileThatDeclaresAnEnumKeepsItsPrecision(@TempDir Path directory) throws IOException {
    CompileContext context = writeProject(directory);
    Path file = directory.resolve("modA").resolve("store.ts");

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.StructuredParseResult result = frontend.parseStructured(STORE_A, file, context);

      assertThat(result.problems())
          .as(
              "an enum is lowered, so there is nothing to report about it -- and the STRUCTURED"
                  + " problems are the ones that say so: the file-level list is empty whether the"
                  + " enum was lowered or not")
          .isEmpty();
      assertThat(result.precision())
          .as("a file whose only declaration is an enum is analysed in full")
          .isEqualTo(IrPrecision.SOURCE_FULL);
    }
  }

  @Test
  @DisplayName("an enum inside a namespace carries the namespace")
  void anEnumInsideANamespaceCarriesTheNamespace(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    TacFunction inner = functionNamed(functions, "Holder.Inner");

    assertThat(inner.signature())
        .as("the identity of an enum is the module, the namespaces and the name")
        .isEqualTo("ns.Holder.Inner()");
    assertThat(inner.enclosingClass())
        .as("the declaring class is what tells two same-named enums apart")
        .isEqualTo("ns.Holder.Inner");
  }

  @Test
  @DisplayName("an enum in each of two modules does not share its members")
  void anEnumInEachOfTwoModulesDoesNotShareItsMembers(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translate(directory);

    List<String> signatures = new ArrayList<>();
    for (TacFunction function : functions) {
      if ("Plain".equals(function.name())) signatures.add(function.signature());
    }

    assertThat(signatures)
        .as(
            "the function table is keyed by the signature, so two members whose signatures are the"
                + " same string are ONE member -- and one of the two bodies would be gone from the"
                + " program, with no drop and no problem to say so")
        .containsExactlyInAnyOrder("modA/store.Plain()", "modB/store.Plain()");
  }

  @Test
  @DisplayName("the member the compiler cut off gets no value")
  void theMemberTheCompilerCutOffGetsNoValue(@TempDir Path directory) throws IOException {
    TacFunction with = functionNamed(translate(directory), "With");

    assertThat(storeWithKey(with, "A")).as("the member before the cut is a member").isNotNull();
    assertThat(storeWithKey(with, "run"))
        .as(
            "the member at the cut is the head of what the compiler lost, so it is not a"
                + " constant: giving it a value would state a number the source does not say")
        .isNull();
  }

  @Test
  @DisplayName("the loss in an enum the compiler cut off is reported")
  void theLossInAnEnumTheCompilerCutOffIsReported(@TempDir Path directory) throws IOException {
    CompileContext context = writeProject(directory);
    Path file = directory.resolve("truncated.ts");

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.StructuredParseResult result = frontend.parseStructured(TRUNCATED, file, context);

      assertThat(result.problems())
          .as(
              "the loss has to be reported, or the reader is told the enum was analysed -- and the"
                  + " report has to name the ENUM, because the file holds another loss as well")
          .anyMatch(problem -> "EnumDeclaration".equals(problem.nodeClass()));
      assertThat(result.precision())
          .as("an enum the compiler cut short is not analysed in full")
          .isEqualTo(IrPrecision.SOURCE_LOWERED);
    }
  }

  @Test
  @DisplayName("the value of a member after a computed value is not invented")
  void theValueOfAMemberAfterAComputedValueIsNotInvented(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory);

    TacFunction computed = functionNamed(functions, "Computed");

    TacInstruction store = storeWithKey(computed, "B");

    assertThat(store).as("the member is still written, with an unknown value").isNotNull();
    assertThat(store.dest)
        .as("the value after a computed value is not knowable, and it is not invented")
        .isNotInstanceOf(TacOperand.Const.class);
    assertThat(computed.drops())
        .as("the unknown value is recorded, and a VALUE loss does not lower the precision")
        .anyMatch(
            drop ->
                drop.impact() == TacDrop.Impact.VALUE
                    && drop.construct().equals("ts.enum.memberValueUnknown"));
    assertThat(computed.irPrecision())
        .as("a VALUE drop leaves the precision alone")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** The function of the given name, or null when there is no such function. */
  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) return function;
    }
    throw new AssertionError("the function '" + name + "' is not in the translation");
  }

  /** The store whose key is the given name, or null when there is no such store. */
  private static TacInstruction storeWithKey(TacFunction function, String key) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYSTORE && key.equals(keyOf(instruction))) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * The load of the given key from the given object, or null when there is no such load.
   *
   * <p>The object is part of the question: a namespaced member is read in two loads -- the
   * namespace object, then the member's own object -- so a search by key alone can land on the
   * wrong one.
   */
  private static TacInstruction loadWithKey(TacFunction function, String key, String object) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.ARRAYLOAD) continue;
      if (!key.equals(keyOf(instruction))) continue;
      if (!object.equals(objectOf(instruction))) continue;
      return instruction;
    }
    return null;
  }

  /** The key of a keyed access, or null when the access has no constant key. */
  private static String keyOf(TacInstruction instruction) {
    if (!(instruction.lhs2 instanceof TacOperand.Const constant)) return null;
    if (constant.kind() != TacOperand.Const.Kind.STRING) return null;
    return String.valueOf(constant.value());
  }

  /** The name of the object a keyed access touches, or null when it has no variable. */
  private static String objectOf(TacInstruction instruction) {
    if (!(instruction.lhs1 instanceof TacOperand.TacVar variable)) return null;
    return variable.name();
  }

  /** The number a store writes, or null when the value is not a constant. */
  private static Double valueOf(TacInstruction instruction) {
    if (instruction == null) return null;
    if (!(instruction.dest instanceof TacOperand.Const constant)) return null;
    if (!(constant.value() instanceof Number number)) return null;
    return number.doubleValue();
  }

  /** The files of the project, translated through the real sidecar. */
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
      functions.addAll(frontend.parse(NAMESPACED, directory.resolve("ns.ts"), context).functions());
      functions.addAll(frontend.parse(CALLER, directory.resolve("caller.ts"), context).functions());
      functions.addAll(
          frontend.parse(COMPUTED, directory.resolve("computed.ts"), context).functions());
      functions.addAll(
          frontend.parse(TRUNCATED, directory.resolve("truncated.ts"), context).functions());
      return functions;
    }
  }

  /**
   * The files of the project, written.
   *
   * <p>The source root is what makes the module path a PROJECT-wide identity. Without one every
   * file is its own project, and the module of a file is its own name -- which would leave two
   * same-named files in two directories indistinguishable.
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
    Files.writeString(directory.resolve("ns.ts"), NAMESPACED);
    Files.writeString(directory.resolve("caller.ts"), CALLER);
    Files.writeString(directory.resolve("computed.ts"), COMPUTED);
    Files.writeString(directory.resolve("truncated.ts"), TRUNCATED);

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
