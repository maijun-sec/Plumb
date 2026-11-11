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
package org.plumb.dfa.cg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The global store and the merged program have to agree on the key a function is filed under.
 *
 * <p><b>Why this is worth a test.</b> The rule that decides a function's key -- "the enclosing
 * class and the signature, and the bare signature when there is no enclosing class" -- was written
 * down five times in this repository. Four of the five copies said the same thing. The fifth, in
 * the global store, returned an EMPTY string when the enclosing class was missing, and the
 * registration guards dropped every function that got that key.
 *
 * <p><b>What that cost.</b> The bytecode front end records no enclosing class for the functions it
 * lowers, so every function of a scanned artifact was dropped: the store's function list came back
 * empty, the call-graph builder -- which reads that list -- saw no function and therefore no call
 * site, and the cross-function analysis had no edges to follow. A scan of a JAR produced findings
 * inside a single method and nothing that crossed a method boundary.
 *
 * <p><b>What the test pins.</b> That a function without an enclosing class is still IN the store,
 * that the key the store uses is the key the merged program uses, and that the call graph built
 * over such a store sees the call inside it.
 */
@DisplayName("The global store and the merged program must agree on the key")
class GlobalStoreMergedKeyTest {

  /** The signature the bytecode front end writes: the class, the method, the declared arity. */
  private static final String BYTECODE_SIGNATURE = "org.example.Helper.help(1)";

  /** The callee signature the bytecode front end writes: the class, the method, the arity. */
  private static final String BYTECODE_CALLEE = "org.example.Helper.help/1";

  /**
   * A function the bytecode front end would produce: no enclosing class, the class named in the
   * signature, and one call inside it.
   */
  private static TacFunction bytecodeShapedFunction() {
    TacOperand.TacVar argument = new TacOperand.TacVar("@parameter-1", TacType.STRING, false);
    TacInstruction call =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig(BYTECODE_CALLEE)
            .args(List.<TacOperand>of(argument))
            .line(7)
            .build();
    return new TacFunction(
        "help",
        BYTECODE_SIGNATURE,
        TacType.VOID,
        List.of(argument),
        List.of(argument),
        List.of(call),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null);
  }

  /** The same function, but with an enclosing class -- the shape a source front end produces. */
  private static TacFunction sourceShapedFunction() {
    TacOperand.TacVar argument = new TacOperand.TacVar("@parameter-1", TacType.STRING, false);
    TacInstruction call =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig(BYTECODE_CALLEE)
            .args(List.<TacOperand>of(argument))
            .line(7)
            .build();
    return new TacFunction(
        "help",
        "help(String):void",
        TacType.VOID,
        List.of(argument),
        List.of(argument),
        List.of(call),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        "org.example.Helper");
  }

  /** A store holding the given function. */
  private static GlobalTACStore storeOf(TacFunction function) {
    TacProgram program = new TacProgram(Path.of("Helper.class"), "Helper");
    program.addFunctionByKey(MergedProgram.mergedKey(function), function);
    GlobalTACStore store = new GlobalTACStore();
    store.addProgram(program);
    store.freeze();
    return store;
  }

  @Test
  @DisplayName("a function without an enclosing class is still in the store")
  void aFunctionWithoutAnEnclosingClassIsStillInTheStore() {
    TacFunction function = bytecodeShapedFunction();
    assertThat(function.enclosingClass())
        .as("the bytecode front end records no enclosing class")
        .isNull();

    GlobalTACStore store = storeOf(function);

    assertThat(store.allFunctions())
        .as("the function must be visible to the call-graph builder")
        .containsExactly(function);
    assertThat(store.allFunctionsView())
        .as("the hot path reads the same index")
        .containsExactly(function);
  }

  @Test
  @DisplayName("the store files a function under the key the merged program uses")
  void theStoreFilesAFunctionUnderTheKeyTheMergedProgramUses() {
    TacFunction bytecode = bytecodeShapedFunction();
    TacFunction source = sourceShapedFunction();

    GlobalTACStore store = new GlobalTACStore();
    store.addProgram(programWith(bytecode));
    store.addProgram(programWith(source));
    store.freeze();

    assertThat(store.allSignatures())
        .as("the store's key space IS the merged program's key space")
        .containsExactlyInAnyOrder(
            MergedProgram.mergedKey(bytecode), MergedProgram.mergedKey(source));
  }

  @Test
  @DisplayName("the call graph sees the call inside a function without an enclosing class")
  void theCallGraphSeesTheCallInsideAFunctionWithoutAnEnclosingClass() {
    GlobalTACStore store = storeOf(bytecodeShapedFunction());

    CallGraph graph = new GlobalCallGraphBuilder(store).build();

    assertThat(graph.allCallSites())
        .as(
            "a call inside a function the store dropped would be an edge the analysis cannot"
                + " follow")
        .hasSize(1);
    assertThat(graph.allCallSites().get(0).callee().signature())
        .as("the callee is named the way the bytecode front end names it")
        .isEqualTo(BYTECODE_SIGNATURE);
  }

  @Test
  @DisplayName("a function with an enclosing class is filed under the class and the signature")
  void aFunctionWithAnEnclosingClassIsFiledUnderTheClassAndTheSignature() {
    TacFunction function = sourceShapedFunction();

    GlobalTACStore store = storeOf(function);

    assertThat(store.allSignatures())
        .as("two functions that share a name in different classes must not collapse into one")
        .containsExactly("org.example.Helper :: help(String):void");
  }

  /** A program holding the given function. */
  private static TacProgram programWith(TacFunction function) {
    TacProgram program = new TacProgram(Path.of("Helper.class"), "Helper");
    program.addFunctionByKey(MergedProgram.mergedKey(function), function);
    return program;
  }
}
