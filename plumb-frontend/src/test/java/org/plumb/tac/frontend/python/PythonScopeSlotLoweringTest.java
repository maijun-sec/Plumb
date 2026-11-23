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
package org.plumb.tac.frontend.python;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * The scopes that live across the functions, as the side car lowers them.
 *
 * <h2>Why a scope is a SLOT</h2>
 *
 * <p>A name belongs to exactly ONE scope, and the scope it belongs to decides where its value
 * lives. The scopes that outlive the code that writes into them are the module's own, a class
 * body's, and the scope of a function a nested function writes into by {@code nonlocal}.
 *
 * <p>The TAC has no scope of its own, so a scope's slot is a FIELD access on a synthetic owner: the
 * owner stands for the scope, and the field's name is the name inside it. The engine's
 * cross-function field tracker is the fact that carries a value from one function to another, so a
 * write and a read that name the same owner are the same location.
 *
 * <h2>Why the OWNER's NAME has to start with an upper-case letter</h2>
 *
 * <p>Because the engine records a field write at the class level only when the receiver looks like
 * a class name -- a name whose first character is upper case. A scope owner is exactly that: the
 * thing that owns the name. A lower-case owner would make every write to a scope invisible to the
 * tracker, and the value would stop at the function that wrote it.
 *
 * <h2>Why the owner carries the scope's IDENTITY</h2>
 *
 * <p>Because the tracker keys its entries on the receiver's TYPE, and the type is what keeps two
 * scopes two scopes: two modules with a global of the same name are two slots, and so are a
 * module's scope and a class's. The name of the scope is in the type, so a scope of one module
 * never collides with a scope of another.
 */
@DisplayName("Python: a name that lives in a scope is a slot, not a local")
class PythonScopeSlotLoweringTest {

  private static final String MODULE_GLOBAL =
      """
      import os

      CACHE = os.environ.get("CMD")


      def use():
          os.system(CACHE)
      """;

  private static final String GLOBAL_STATEMENT =
      """
      CACHE = 1


      def store():
          global CACHE
          CACHE = 2
      """;

  private static final String CLASS_ATTRIBUTE =
      """
      import os

      class Holder:
          value = os.environ.get("CMD")

          def use(self):
              os.system(Holder.value)
      """;

  private static final String NONLOCAL =
      """
      def outer():
          cache = 1

          def store():
              nonlocal cache
              cache = 2

          store()
          return cache
      """;

  private static final String SHADOWED =
      """
      CACHE = 1


      def use():
          CACHE = 2
          return CACHE
      """;

  @Test
  @DisplayName("a module's own name is a slot the module and its functions share")
  void aModulesOwnNameIsASlotTheModuleAndItsFunctionsShare(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, MODULE_GLOBAL);
    TacFunction module = PythonTestSupport.functionNamed(functions, "<module>");
    TacFunction use = PythonTestSupport.functionNamed(functions, "use");

    assertThat(module).as("the module's own code must be in the stream").isNotNull();
    assertThat(use).as("the module's function must be in the stream").isNotNull();

    TacInstruction write = PythonTestSupport.fieldStoreOf(module, "CACHE");
    TacInstruction read = PythonTestSupport.fieldLoadOf(use, "CACHE");
    assertThat(write)
        .as("a name the module binds lives in the module's scope, so the write lands there")
        .isNotNull();
    assertThat(read).as("the function reads that same name, so it reads the scope").isNotNull();

    assertThat(ownerNameOf(read))
        .as("the read and the write must name the same scope, or the module has two of them")
        .isEqualTo(ownerNameOf(write));
    assertThat(ownerTypeNameOf(read))
        .as("the owner carries the scope's identity, and that is what keeps two scopes apart")
        .isEqualTo(ownerTypeNameOf(write));
  }

  @Test
  @DisplayName("a scope's owner is named the way the engine's gate reads it")
  void aScopesOwnerIsNamedTheWayTheEnginesGateReadsIt(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, MODULE_GLOBAL);
    TacFunction module = PythonTestSupport.functionNamed(functions, "<module>");

    TacInstruction write = PythonTestSupport.fieldStoreOf(module, "CACHE");
    assertThat(write).as("the module's own name must be a slot").isNotNull();

    assertThat(ownerNameOf(write))
        .as(
            "the engine records a field write at the class level only when the receiver looks like"
                + " a class name, and a scope owner is the thing that owns the name")
        .hasSizeGreaterThan(0)
        .matches("^[A-Z].*");
    assertThat(ownerTypeOf(write).kind())
        .as("the owner's type is what the tracker keys on, so it has to be a class")
        .isEqualTo(TacType.Kind.CLASS);
  }

  @Test
  @DisplayName("a `global` name is written in the module's scope")
  void aGlobalNameIsWrittenInTheModulesScope(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, GLOBAL_STATEMENT);
    TacFunction module = PythonTestSupport.functionNamed(functions, "<module>");
    TacFunction store = PythonTestSupport.functionNamed(functions, "store");

    assertThat(store).as("the function must be in the stream").isNotNull();

    TacInstruction write = PythonTestSupport.fieldStoreOf(store, "CACHE");
    assertThat(write)
        .as("a `global` name belongs to the module, so the write lands in the module's scope")
        .isNotNull();
    assertThat(ownerTypeNameOf(write))
        .as("the module's scope is one scope, whichever function writes into it")
        .isEqualTo(ownerTypeNameOf(PythonTestSupport.fieldStoreOf(module, "CACHE")));
  }

  @Test
  @DisplayName("a class attribute lives in the class's scope")
  void aClassAttributeLivesInTheClasssScope(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, CLASS_ATTRIBUTE);
    TacFunction body = PythonTestSupport.functionNamed(functions, "<clinit>");
    TacFunction use = PythonTestSupport.functionNamed(functions, "use");

    assertThat(body).as("the class body must be in the stream").isNotNull();
    assertThat(use).as("the method must be in the stream").isNotNull();

    TacInstruction write = PythonTestSupport.fieldStoreOf(body, "value");
    TacInstruction read = PythonTestSupport.fieldLoadOf(use, "value");
    assertThat(write).as("a name a class body binds is an attribute of the class").isNotNull();
    assertThat(read)
        .as("the method reads the attribute, so it reads the class's scope")
        .isNotNull();
    assertThat(ownerTypeNameOf(read))
        .as("the read and the write must be the class's own scope")
        .isEqualTo(ownerTypeNameOf(write));
  }

  @Test
  @DisplayName("a `nonlocal` name is written in the scope of the function around it")
  void aNonlocalNameIsWrittenInTheScopeOfTheFunctionAroundIt(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, NONLOCAL);
    TacFunction outer = PythonTestSupport.functionNamed(functions, "outer");
    TacFunction store = PythonTestSupport.functionNamed(functions, "store");

    assertThat(outer).as("the outer function must be in the stream").isNotNull();
    assertThat(store).as("the inner function must be in the stream").isNotNull();

    TacInstruction outerWrite = PythonTestSupport.fieldStoreOf(outer, "cache");
    TacInstruction innerWrite = PythonTestSupport.fieldStoreOf(store, "cache");
    TacInstruction outerRead = PythonTestSupport.fieldLoadOf(outer, "cache");

    assertThat(outerWrite)
        .as("the name belongs to the outer function, so the outer function holds it in a slot")
        .isNotNull();
    assertThat(innerWrite)
        .as("a `nonlocal` write lands in the scope around the function, so it is a slot write")
        .isNotNull();
    assertThat(ownerTypeNameOf(innerWrite))
        .as("the inner write and the outer function must name the same scope")
        .isEqualTo(ownerTypeNameOf(outerWrite));
    assertThat(outerRead)
        .as("the outer function reads the name back out of that scope")
        .isNotNull();
    assertThat(ownerTypeNameOf(outerRead)).isEqualTo(ownerTypeNameOf(outerWrite));
  }

  @Test
  @DisplayName("two scopes are two scopes")
  void twoScopesAreTwoScopes(@TempDir Path directory) throws IOException {
    String source =
        """
        CACHE = 1


        class Holder:
            CACHE = 2
        """;
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, source);
    TacFunction module = PythonTestSupport.functionNamed(functions, "<module>");
    TacFunction body = PythonTestSupport.functionNamed(functions, "<clinit>");

    TacInstruction moduleWrite = PythonTestSupport.fieldStoreOf(module, "CACHE");
    TacInstruction classWrite = PythonTestSupport.fieldStoreOf(body, "CACHE");
    assertThat(moduleWrite).as("the module's own name must be a slot").isNotNull();
    assertThat(classWrite).as("the class body's own name must be a slot").isNotNull();
    assertThat(ownerTypeNameOf(classWrite))
        .as(
            "the module's scope and the class's scope are two scopes, and the tracker keys on the"
                + " owner's type, so they must not share one")
        .isNotEqualTo(ownerTypeNameOf(moduleWrite));
  }

  @Test
  @DisplayName("a local that shadows a module's name is a local")
  void aLocalThatShadowsAModulesNameIsALocal(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, SHADOWED);
    TacFunction use = PythonTestSupport.functionNamed(functions, "use");

    assertThat(use).as("the function must be in the stream").isNotNull();
    assertThat(PythonTestSupport.fieldStoreOf(use, "CACHE"))
        .as("a name the function assigns is the function's own, whatever the module calls it")
        .isNull();
    assertThat(PythonTestSupport.fieldLoadOf(use, "CACHE"))
        .as("so the read is a read of the local, and not of the module's scope")
        .isNull();
  }

  /** The owner's name, or null. */
  private static String ownerNameOf(TacInstruction instruction) {
    TacOperand.TacVar owner = PythonTestSupport.ownerOf(instruction);
    return owner == null ? null : owner.name();
  }

  /** The owner's type, or null. */
  private static TacType ownerTypeOf(TacInstruction instruction) {
    TacOperand.TacVar owner = PythonTestSupport.ownerOf(instruction);
    return owner == null ? null : owner.type();
  }

  /** The name of the owner's type, or null. */
  private static String ownerTypeNameOf(TacInstruction instruction) {
    TacType type = ownerTypeOf(instruction);
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return null;
    }
    return type.className();
  }
}
