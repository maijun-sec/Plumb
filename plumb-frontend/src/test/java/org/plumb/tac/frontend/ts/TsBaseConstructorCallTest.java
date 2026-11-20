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
import org.plumb.tac.ir.TacOperand;

/**
 * The TypeScript frontend: a derived class calls its base constructor ONCE, with the arguments the
 * source passed.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>Before this, the TypeScript sidecar called the base constructor TWICE from a derived
 * constructor: once with a synthesised call that carried no arguments at all, and once from the
 * {@code super(...)} statement, which carried the source's arguments. The two calls disagreed about
 * the arity as well -- the synthesised one counted the parameters by scanning the FILE's own class
 * declarations, so a base class in another module counted as zero and the callee key named a
 * function that does not exist.
 *
 * <p><b>The call is made once.</b> A constructor that runs twice is not the constructor the source
 * describes, and the engine reads a call as an edge: two edges means the base's facts are folded in
 * twice, and the second edge is the one with the wrong shape.
 *
 * <p><b>The arguments are the source's.</b> The synthesised call passed {@code this} and nothing
 * else, so the value the caller passed never reached the base constructor's parameters. The source
 * says what it passes, and the TAC has to say the same thing.
 *
 * <p><b>The arity is the DECLARED one.</b> The compiler knows how many parameters the base
 * constructor declares, and it knows it across files. A count taken from the arguments that were
 * passed would be a different number whenever the source passed fewer arguments than the base
 * declares, and the callee key would then name a function that does not exist -- the same failure
 * the file scan had across files.
 *
 * <p><b>The shape of the statement does not matter.</b> A {@code super(...)} call is legal inside a
 * branch as well, and it is the base's call wherever it sits. The test that says so is the one that
 * has a {@code super(...)} inside an {@code if}: the call is still made once, and it still carries
 * the arguments.
 *
 * <h2>Why this drives the real sidecar</h2>
 *
 * <p>The compiler's tree is the fact here, and only the real compiler produces it. A test that
 * checked the mapping would pass if the sidecar stopped asking.
 */
@DisplayName(
    "TypeScript: a derived class calls its base constructor once, with the source's arguments")
class TsBaseConstructorCallTest {

  /** The base class and the derived class that calls it, in one module. */
  private static final String SAME_FILE =
      """
      export class Base {
        constructor(label: string, count: number) {
          this.label = label;
        }
      }

      export class Derived extends Base {
        constructor(input: string) {
          super(input, 7);
          this.extra = input;
        }
      }
      """;

  /** The base class, in a module of its own. */
  private static final String REMOTE_BASE =
      """
      export class RemoteBase {
        constructor(label: string, count: number) {
          this.label = label;
        }
      }
      """;

  /** The derived class, in another module. */
  private static final String REMOTE_DERIVED =
      """
      import { RemoteBase } from "./remote";

      export class RemoteDerived extends RemoteBase {
        constructor(input: string) {
          super(input, 7);
          this.extra = input;
        }
      }
      """;

  /**
   * A derived class that passes FEWER arguments than the base declares.
   *
   * <p>The base declares two parameters and the call passes one, so the arity of the call is two --
   * the base constructor's declared count -- and not one, which is what the arguments alone would
   * say. The key has to name the base constructor that actually exists.
   */
  private static final String FEWER_ARGUMENTS =
      """
      export class Base {
        constructor(label: string, count: number) {
          this.label = label;
        }
      }

      export class Derived extends Base {
        constructor(input: string) {
          super(input);
          this.extra = input;
        }
      }
      """;

  /**
   * A derived class whose {@code super(...)} call sits inside a branch.
   *
   * <p>The compiler accepts it: a class without initialised properties or parameter properties may
   * put the call anywhere. The base is called once either way, and the call carries the arguments
   * the source passed.
   */
  private static final String NESTED =
      """
      export class Base {
        constructor(label: string, count: number) {
          this.label = label;
        }
      }

      export class Nested extends Base {
        constructor(input: string, flag: boolean) {
          if (flag) {
            super(input, 7);
          }
          this.extra = input;
        }
      }
      """;

  @Test
  @DisplayName("the base constructor is called exactly once")
  void theBaseConstructorIsCalledExactlyOnce(@TempDir Path directory) throws IOException {
    List<TacInstruction> calls =
        callsToBaseConstructor(translate(directory, SAME_FILE), "Derived", "Base");

    assertThat(calls)
        .as(
            "the base constructor is called once: two calls mean the base's facts are folded in"
                + " twice, and the source describes one call")
        .hasSize(1);
  }

  @Test
  @DisplayName("the call carries the arguments the source passed")
  void theCallCarriesTheArgumentsTheSourcePassed(@TempDir Path directory) throws IOException {
    List<TacInstruction> calls =
        callsToBaseConstructor(translate(directory, SAME_FILE), "Derived", "Base");

    assertThat(calls).as("the base constructor is called").hasSize(1);
    TacInstruction call = calls.get(0);
    assertThat(call.args)
        .as(
            "the arguments are the ones the source passed: the synthesised call carried the"
                + " receiver alone, so the value the caller passed never reached the base"
                + " constructor's parameters")
        .hasSize(3);
    assertThat(nameOf(call.args.get(1)))
        .as("the first argument is the source's first argument")
        .isEqualTo("input");
    assertThat(constantOf(call.args.get(2)))
        .as("the second argument is the source's second argument")
        .isEqualTo(7.0d);
  }

  @Test
  @DisplayName("the receiver of the call is the object being constructed")
  void theReceiverOfTheCallIsTheObjectBeingConstructed(@TempDir Path directory) throws IOException {
    List<TacInstruction> calls =
        callsToBaseConstructor(translate(directory, SAME_FILE), "Derived", "Base");

    assertThat(calls).as("the base constructor is called").hasSize(1);
    assertThat(nameOf(calls.get(0).args.get(0)))
        .as("the base constructor runs on THIS object")
        .isEqualTo("this");
  }

  @Test
  @DisplayName("the arity of the call is the base constructor's declared count")
  void theArityOfTheCallIsTheBaseConstructorsDeclaredCount(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translate(directory, FEWER_ARGUMENTS);

    List<TacInstruction> calls = callsToBaseConstructor(functions, "Derived", "Base");
    assertThat(calls).as("the base constructor is called").hasSize(1);
    assertThat(calls.get(0).calleeSig)
        .as(
            "the key names the base constructor that exists: the base declares two parameters,"
                + " so the key is /2 even though one argument was passed")
        .isEqualTo("Base.<init>/2");
  }

  @Test
  @DisplayName("a base class in another module is reached with the right arity")
  void aBaseClassInAnotherModuleIsReachedWithTheRightArity(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translateBoth(directory);

    List<TacInstruction> calls = callsToBaseConstructor(functions, "RemoteDerived", "RemoteBase");
    assertThat(calls).as("the base constructor is called").hasSize(1);
    assertThat(calls.get(0).calleeSig)
        .as(
            "the key names the base constructor that exists: a count taken from the FILE's own"
                + " class declarations cannot see a base class in another module, and it answered"
                + " zero parameters there")
        .isEqualTo("RemoteBase.<init>/2");
  }

  @Test
  @DisplayName("a super call inside a branch is still called once, with its arguments")
  void aSuperCallInsideABranchIsStillCalledOnceWithItsArguments(@TempDir Path directory)
      throws IOException {
    List<TacInstruction> calls =
        callsToBaseConstructor(translate(directory, NESTED), "Nested", "Base");

    assertThat(calls)
        .as(
            "the base constructor is called once: a super call inside a branch is the base's call"
                + " too, and lowering it a second time would fold the base's facts in twice")
        .hasSize(1);
    assertThat(calls.get(0).args)
        .as(
            "the arguments are the ones the source passed: a search that only looks at the body's"
                + " direct statements does not find a super call inside a branch, and then the"
                + " call carries the receiver alone")
        .hasSize(3);
    assertThat(nameOf(calls.get(0).args.get(1)))
        .as("the first argument is the source's first argument")
        .isEqualTo("input");
    assertThat(constantOf(calls.get(0).args.get(2)))
        .as("the second argument is the source's second argument")
        .isEqualTo(7.0d);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * The calls to the base constructor, in the derived class's constructor.
   *
   * @param functions the translated functions
   * @param derived the derived class's simple name
   * @param base the base class's simple name
   * @return the calls
   */
  private static List<TacInstruction> callsToBaseConstructor(
      List<TacFunction> functions, String derived, String base) {
    List<TacInstruction> calls = new ArrayList<>();
    for (TacFunction function : functions) {
      if (!derived.equals(simpleNameOf(function.enclosingClass()))) {
        continue;
      }
      for (TacInstruction instruction : function.instructions()) {
        if (instruction.kind != TacInstruction.Kind.CALL) continue;
        String callee = instruction.calleeSig;
        if (callee == null || !callee.startsWith(base + ".<init>/")) continue;
        calls.add(instruction);
      }
    }
    return calls;
  }

  /**
   * The last dot-separated segment of a name.
   *
   * <p>The class's identity carries the module it lives in, so the enclosing class of a class in a
   * module is "modA/store.UserStore" -- and a test that compares the whole string has to know about
   * the module.
   *
   * @param qualified the qualified name
   * @return the simple name
   */
  private static String simpleNameOf(String qualified) {
    if (qualified == null) return null;
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? qualified : qualified.substring(dot + 1);
  }

  /** The name of a variable operand, or null when the operand is not one. */
  private static String nameOf(TacOperand operand) {
    if (!(operand instanceof TacOperand.TacVar variable)) return null;
    return variable.name();
  }

  /** The number a constant operand holds, or null when it is not one. */
  private static Double constantOf(TacOperand operand) {
    if (!(operand instanceof TacOperand.Const constant)) return null;
    if (!(constant.value() instanceof Number number)) return null;
    return number.doubleValue();
  }

  /** The files of the project, translated through the real sidecar. */
  private static List<TacFunction> translate(Path directory, String source) throws IOException {
    return translate(directory, source, "mod.ts");
  }

  /** The files of the project, translated through the real sidecar. */
  private static List<TacFunction> translate(Path directory, String source, String name)
      throws IOException {
    Path file = directory.resolve(name);
    Files.writeString(file, source);
    CompileContext context = writeProject(directory);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      return frontend.parse(source, file, context).functions();
    }
  }

  /**
   * The files of the project, translated through the real sidecar.
   *
   * <p>Both files are in the SAME project, because that is what makes the module path a
   * PROJECT-wide identity -- and what lets the compiler resolve a base class that lives in another
   * module of the project.
   *
   * @param directory the project
   * @return the functions of both files
   */
  private static List<TacFunction> translateBoth(Path directory) throws IOException {
    Files.writeString(directory.resolve("remote.ts"), REMOTE_BASE);
    Files.writeString(directory.resolve("derived.ts"), REMOTE_DERIVED);
    CompileContext context = writeProject(directory);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      List<TacFunction> functions = new ArrayList<>();
      functions.addAll(
          frontend.parse(REMOTE_BASE, directory.resolve("remote.ts"), context).functions());
      functions.addAll(
          frontend.parse(REMOTE_DERIVED, directory.resolve("derived.ts"), context).functions());
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
  private static CompileContext writeProject(Path directory) {
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
