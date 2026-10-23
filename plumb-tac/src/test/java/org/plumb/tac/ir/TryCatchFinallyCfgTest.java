package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.translate.TacTranslator;

/**
 * verify the exception-flow edges emitted by {@link TacCfgBuilder} when a {@link TacFunction}
 * contains try / catch / finally regions registered via {@link TacFunction#tryRegions()}. The DFA
 * engine consumes {@link TacCfg#exceptionSuccs(int)} to propagate taint, null facts, and resource
 * tracking through catch handlers instead of treating the throw as a function exit.
 */
class TryCatchFinallyCfgTest {

  private TacFunction funcOf(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), src);
    return new TacTranslator(ps).translate().functions().get(0);
  }

  /** Resolve a label name to the block id that starts with it. */
  private int blockIdForLabel(TacCfg cfg, String labelPrefix) {
    for (TacBasicBlock b : cfg.blocks()) {
      // unused stub; use findBlockByLabelPrefix below
      if (cfg == null) break;
    }
    throw new IllegalStateException("not used; see findBlockByLabelPrefix");
  }

  /** Find a block whose first instruction is a LABEL with the given prefix. */
  private TacBasicBlock findBlockByLabelPrefix(TacCfg cfg, TacFunction fn, String labelPrefix) {
    for (TacBasicBlock b : cfg.blocks()) {
      int first = b.firstIndex();
      TacInstruction ins = fn.instructionAt(first);
      if (ins.kind == TacInstruction.Kind.LABEL
          && ins.label != null
          && ins.label.startsWith(labelPrefix)) {
        return b;
      }
    }
    return null;
  }

  @Test
  void singleCatchGivesExceptionEdgeFromTryBodyToCatchHandler() {
    String src =
        "class T {\n"
            + "  void m() {\n"
            + "    try {\n"
            + "      int x = 1;\n"
            + "      int y = x + 1;\n"
            + "    } catch (java.io.IOException e) {\n"
            + "      int z = 2;\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    TacFunction fn = funcOf(src);
    // The translator must have registered a try region.
    assertThat(fn.tryRegions()).hasSize(1);
    TacFunction.TryRegion region = fn.tryRegions().get(0);
    assertThat(region.catchLabels).hasSize(1);
    assertThat(region.finallyLabel).isNull();
    // Build the CFG and verify exception edges.
    TacCfg cfg = TacCfgBuilder.build(fn);
    TacBasicBlock catchBlock = findBlockByLabelPrefix(cfg, fn, "L_CATCH_");
    assertThat(catchBlock).as("catch handler block").isNotNull();
    int catchId = catchBlock.id();
    // Find a block in the try body (between region.tryStart and
    // region.tryEnd) and verify it has an exception-successor
    // pointing to the catch block.
    boolean anyTryBlockHasExcEdge = false;
    for (TacBasicBlock b : cfg.blocks()) {
      int first = b.firstIndex();
      int last = b.lastIndex();
      if (first >= region.tryStart && last <= region.tryEnd) {
        List<Integer> exc = cfg.exceptionSuccs(b.id());
        if (exc.contains(catchId)) {
          anyTryBlockHasExcEdge = true;
          break;
        }
      }
    }
    assertThat(anyTryBlockHasExcEdge)
        .as("at least one block in the try body should have an exception edge to the catch handler")
        .isTrue();
  }

  @Test
  void multiCatchGivesExceptionEdgeToAllHandlers() {
    String src =
        "class T {\n"
            + "  void m() {\n"
            + "    try {\n"
            + "      int x = 1;\n"
            + "    } catch (java.io.IOException a) {\n"
            + "      int z1 = 1;\n"
            + "    } catch (java.lang.IllegalStateException b) {\n"
            + "      int z2 = 2;\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    TacFunction fn = funcOf(src);
    assertThat(fn.tryRegions()).hasSize(1);
    TacFunction.TryRegion region = fn.tryRegions().get(0);
    assertThat(region.catchLabels).hasSize(2);
    TacCfg cfg = TacCfgBuilder.build(fn);
    Set<Integer> catchIds =
        region.catchLabels.stream()
            .map(label -> findBlockByLabelPrefix(cfg, fn, label))
            .filter(b -> b != null)
            .map(TacBasicBlock::id)
            .collect(Collectors.toSet());
    assertThat(catchIds).hasSize(2);
    // Every block in the try body should have exception
    // successors that are the union of both catch blocks.
    for (TacBasicBlock b : cfg.blocks()) {
      int first = b.firstIndex();
      int last = b.lastIndex();
      if (first >= region.tryStart && last <= region.tryEnd) {
        List<Integer> exc = cfg.exceptionSuccs(b.id());
        assertThat(exc).as("block " + b.id() + " exc-succs").containsAll(catchIds);
      }
    }
  }

  @Test
  void emptyRegionsListYieldsEmptyExceptionMap() {
    String src = "class T { void m() { int x = 1; int y = x + 1; } }";
    TacFunction fn = funcOf(src);
    assertThat(fn.tryRegions()).isEmpty();
    TacCfg cfg = TacCfgBuilder.build(fn);
    assertThat(cfg.exceptionSuccessors()).isEmpty();
  }

  @Test
  void tryFinallyRegionRecordsFinallyLabelButNoCatch() {
    String src =
        "class T {\n"
            + "  void m() {\n"
            + "    try {\n"
            + "      int x = 1;\n"
            + "    } finally {\n"
            + "      int y = 2;\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    TacFunction fn = funcOf(src);
    assertThat(fn.tryRegions()).hasSize(1);
    TacFunction.TryRegion region = fn.tryRegions().get(0);
    assertThat(region.catchLabels).isEmpty();
    assertThat(region.finallyLabel).isNotNull();
    assertThat(region.finallyStart).isGreaterThan(0);
  }

  @Test
  void tryRegionConstructorAcceptsAllFields() {
    // Direct unit test of the TryRegion data class so a
    // refactor that drops a field surfaces immediately.
    TacFunction.TryRegion r =
        new TacFunction.TryRegion(
            0, 5, 6, 10, List.of("L_CATCH_Foo", "L_CATCH_Bar"), "L_FINALLY", 11, 15);
    assertThat(r.tryStart).isEqualTo(0);
    assertThat(r.tryEnd).isEqualTo(5);
    assertThat(r.catchStart).isEqualTo(6);
    assertThat(r.catchEnd).isEqualTo(10);
    assertThat(r.catchLabels).containsExactly("L_CATCH_Foo", "L_CATCH_Bar");
    assertThat(r.finallyLabel).isEqualTo("L_FINALLY");
    assertThat(r.finallyStart).isEqualTo(11);
    assertThat(r.finallyEnd).isEqualTo(15);
    assertThat(r.hasFinally()).isTrue();
    assertThat(r.hasCatch()).isTrue();
  }

  @Test
  void tryRegionWithoutFinallyOrCatchReportsFlags() {
    TacFunction.TryRegion r = new TacFunction.TryRegion(0, 5, -1, -1, List.of(), null, -1, -1);
    assertThat(r.hasFinally()).isFalse();
    assertThat(r.hasCatch()).isFalse();
  }
}
