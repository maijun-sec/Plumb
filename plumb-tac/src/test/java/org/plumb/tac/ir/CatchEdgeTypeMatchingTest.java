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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.translate.TacTranslator;

/**
 * The catch edges a try block reaches, decided by the TYPE of the exception it raises.
 *
 * <p><b>Why this matters.</b> The dataflow engine reads the exception edges to decide which catch
 * parameter inherits the thrown value's taint: a handler that cannot receive the exception must not
 * inherit its taint, or a finding is reported on a flow the program does not have. Before the type
 * was on the instruction, every handler was reachable from every throw, so a second handler
 * inherited a taint that belonged to the first one.
 *
 * <p><b>Why the TAC is built by hand here.</b> The point of these tests is the EDGE, and the edge
 * depends on exactly which instructions share the throwing block. A source snippet would hide that:
 * the translator decides where the labels go, and a change in the translator would move the block
 * boundaries and change what these tests measure. The hand-built function puts the throw and the
 * call in one block on purpose.
 */
@DisplayName("TAC: the catch edges a typed exception reaches")
class CatchEdgeTypeMatchingTest {

  private static final TacType IO_EXCEPTION = TacType.ofClass("java.io.IOException");
  private static final TacType EXCEPTION = TacType.ofClass("java.lang.Exception");
  private static final TacType RUNTIME_EXCEPTION = TacType.ofClass("java.lang.RuntimeException");

  /**
   * A function whose try body raises one exception and whose catch handlers follow.
   *
   * <p>The shape is: {@code L_try}, the body (an optional call, then the throw), one block per
   * handler, and a common end. The try region covers the body; the catch range covers the handlers.
   */
  private static TacFunction functionOf(
      SourceLanguage language,
      TacType thrownType,
      boolean callInTryBody,
      List<TacType> handlerTypes) {
    List<TacInstruction> insns = new ArrayList<>();
    insns.add(label("L_try"));
    int tryStart = 0;
    if (callInTryBody) {
      insns.add(TacInstruction.builder(TacInstruction.Kind.CALL).calleeSig("app.Risky/0").build());
    }
    insns.add(
        TacInstruction.builder(TacInstruction.Kind.THROW)
            .lhs1(new TacOperand.TacVar("e", IO_EXCEPTION, false))
            .ctorType(thrownType)
            .build());
    int tryEnd = insns.size() - 1;
    int catchStart = insns.size();
    List<String> catchLabels = new ArrayList<>();
    for (int i = 0; i < handlerTypes.size(); i++) {
      String label = "L_CATCH_" + i;
      catchLabels.add(label);
      insns.add(label(label));
      insns.add(
          TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
              .dest(new TacOperand.TacVar("ex" + i, handlerTypes.get(i), false))
              .ctorType(handlerTypes.get(i))
              .build());
      insns.add(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(new TacOperand.TacVar("z" + i, TacType.INT, true))
              .lhs1(TacOperand.Const.ofInt(i))
              .build());
      insns.add(TacInstruction.builder(TacInstruction.Kind.JUMP).label("L_end").build());
    }
    int catchEnd = insns.size() - 1;
    insns.add(label("L_end"));
    insns.add(TacInstruction.builder(TacInstruction.Kind.RETURN).build());
    TacFunction.TryRegion region =
        new TacFunction.TryRegion(
            tryStart, tryEnd, catchStart, catchEnd, catchLabels, null, -1, -1);
    return new TacFunction(
        "m",
        "m()void",
        TacType.VOID,
        List.of(),
        List.of(),
        insns,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        "T",
        SourceOrigin.SOURCE,
        List.of(region),
        language,
        IrPrecision.SOURCE_FULL);
  }

  private static TacInstruction label(String name) {
    return TacInstruction.builder(TacInstruction.Kind.LABEL).label(name).build();
  }

  /** The id of the block that starts with the given label. */
  private static int blockOfLabel(TacCfg cfg, TacFunction fn, String label) {
    for (TacBasicBlock b : cfg.blocks()) {
      TacInstruction first = fn.instructionAt(b.firstIndex());
      if (first.kind == TacInstruction.Kind.LABEL && label.equals(first.label)) {
        return b.id();
      }
    }
    throw new AssertionError("no block starts with " + label);
  }

  /**
   * The id of the catch handler block that declares the given type.
   *
   * <p>The label a handler gets carries a counter, so the declared type is the stable way to name
   * one.
   */
  private static int blockOfHandlerType(TacCfg cfg, TacFunction fn, String typeName) {
    for (TacBasicBlock b : cfg.blocks()) {
      for (int i = b.firstIndex(); i <= b.lastIndex(); i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind == TacInstruction.Kind.CATCH_HANDLER
            && ins.ctorType != null
            && typeName.equals(ins.ctorType.displayName())) {
          return b.id();
        }
      }
    }
    throw new AssertionError("no catch handler declares " + typeName);
  }

  /** The exception edges of the try body, which is the first block. */
  private static List<Integer> tryBodyEdges(TacCfg cfg) {
    return cfg.exceptionSuccs(cfg.blocks().get(0).id());
  }

  @Test
  @DisplayName("a throw of a known type reaches only the handler that catches it")
  void aThrowOfAKnownTypeReachesOnlyTheHandlerThatCatchesIt() {
    TacFunction fn =
        functionOf(SourceLanguage.JAVA, IO_EXCEPTION, false, List.of(IO_EXCEPTION, EXCEPTION));
    TacCfg cfg = TacCfgBuilder.build(fn);
    int ioHandler = blockOfLabel(cfg, fn, "L_CATCH_0");
    int exceptionHandler = blockOfLabel(cfg, fn, "L_CATCH_1");
    assertThat(tryBodyEdges(cfg))
        .as("the IOException handler catches the IOException")
        .contains(ioHandler);
    assertThat(tryBodyEdges(cfg))
        .as("the Exception handler is shadowed: the IOException handler comes first")
        .doesNotContain(exceptionHandler);
  }

  @Test
  @DisplayName("a handler whose type is unrelated to the thrown type is not reached")
  void aHandlerWhoseTypeIsUnrelatedIsNotReached() {
    TacFunction fn =
        functionOf(
            SourceLanguage.JAVA, IO_EXCEPTION, false, List.of(RUNTIME_EXCEPTION, IO_EXCEPTION));
    TacCfg cfg = TacCfgBuilder.build(fn);
    int runtimeHandler = blockOfLabel(cfg, fn, "L_CATCH_0");
    int ioHandler = blockOfLabel(cfg, fn, "L_CATCH_1");
    assertThat(tryBodyEdges(cfg))
        .as("an IOException never reaches a RuntimeException handler")
        .doesNotContain(runtimeHandler);
    assertThat(tryBodyEdges(cfg)).as("the IOException handler is reached").contains(ioHandler);
  }

  @Test
  @DisplayName("a throw without a type reaches every handler")
  void aThrowWithoutATypeReachesEveryHandler() {
    TacFunction fn = functionOf(SourceLanguage.JAVA, null, false, List.of(IO_EXCEPTION, EXCEPTION));
    TacCfg cfg = TacCfgBuilder.build(fn);
    assertThat(tryBodyEdges(cfg))
        .as("an untyped throw keeps every handler reachable")
        .contains(blockOfLabel(cfg, fn, "L_CATCH_0"), blockOfLabel(cfg, fn, "L_CATCH_1"));
  }

  @Test
  @DisplayName("a call in the try body keeps every handler reachable")
  void aCallInTheTryBodyKeepsEveryHandlerReachable() {
    TacFunction fn =
        functionOf(SourceLanguage.JAVA, IO_EXCEPTION, true, List.of(IO_EXCEPTION, EXCEPTION));
    TacCfg cfg = TacCfgBuilder.build(fn);
    assertThat(tryBodyEdges(cfg))
        .as("the call may raise anything, so no handler may be ruled out")
        .contains(blockOfLabel(cfg, fn, "L_CATCH_0"), blockOfLabel(cfg, fn, "L_CATCH_1"));
  }

  @Test
  @DisplayName("a handler of a subtype of the thrown type stays reachable")
  void aHandlerOfASubtypeStaysReachable() {
    TacFunction fn =
        functionOf(SourceLanguage.JAVA, EXCEPTION, false, List.of(IO_EXCEPTION, EXCEPTION));
    TacCfg cfg = TacCfgBuilder.build(fn);
    assertThat(tryBodyEdges(cfg))
        .as("the runtime value may be an IOException, so that handler may catch it")
        .contains(blockOfLabel(cfg, fn, "L_CATCH_0"), blockOfLabel(cfg, fn, "L_CATCH_1"));
  }

  @Test
  @DisplayName("a matcher that knows nothing keeps every handler reachable")
  void aMatcherThatKnowsNothingKeepsEveryHandlerReachable() {
    TacFunction fn =
        functionOf(SourceLanguage.JAVA, IO_EXCEPTION, false, List.of(IO_EXCEPTION, EXCEPTION));
    TacCfg cfg = TacCfgBuilder.build(fn, ExceptionTypeMatcher.UNKNOWN_MATCHER);
    assertThat(tryBodyEdges(cfg))
        .as("without platform knowledge the edges stay the over-approximation")
        .contains(blockOfLabel(cfg, fn, "L_CATCH_0"), blockOfLabel(cfg, fn, "L_CATCH_1"));
  }

  @Test
  @DisplayName("a language with multiple inheritance keeps an unrelated handler reachable")
  void aLanguageWithMultipleInheritanceKeepsAnUnrelatedHandlerReachable() {
    TacFunction fn =
        functionOf(
            SourceLanguage.PYTHON3,
            TacType.ofClass("ValueError"),
            false,
            List.of(TacType.ofClass("TypeError"), TacType.ofClass("Exception")));
    TacCfg cfg = TacCfgBuilder.build(fn);
    assertThat(tryBodyEdges(cfg))
        .as("two unrelated Python exceptions can share a subclass, so both stay reachable")
        .contains(blockOfLabel(cfg, fn, "L_CATCH_0"), blockOfLabel(cfg, fn, "L_CATCH_1"));
  }

  @Test
  @DisplayName("the source lane carries the thrown type into the catch edges")
  void theSourceLaneCarriesTheThrownTypeIntoTheCatchEdges() {
    String source =
        "class T {\n"
            + "  void m(java.io.IOException e) {\n"
            + "    try {\n"
            + "      throw e;\n"
            + "    } catch (java.io.IOException a) {\n"
            + "      int x = 1;\n"
            + "    } catch (java.lang.Exception b) {\n"
            + "      int y = 2;\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Test.java"), source);
    TacFunction fn = new TacTranslator(parsed).translate().functions().get(0);
    TacCfg cfg = TacCfgBuilder.build(fn);
    int ioHandler = blockOfHandlerType(cfg, fn, "java.io.IOException");
    int exceptionHandler = blockOfHandlerType(cfg, fn, "java.lang.Exception");
    assertThat(tryBodyEdges(cfg))
        .as("the IOException handler catches the IOException")
        .contains(ioHandler);
    assertThat(tryBodyEdges(cfg))
        .as("the Exception handler is shadowed by the IOException handler")
        .doesNotContain(exceptionHandler);
  }
}
