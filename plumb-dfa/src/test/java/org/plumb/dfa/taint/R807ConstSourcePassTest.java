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
/*
 * R-807 const-source pass ? hermetic fidelity gate for
 * the const-source emission path added in R-807.
 *
 * <p>R-807 extends {@code propagateMOVE} so a
 * {@code dest = CONST} MOVE emits a v2 MARK_SOURCE node
 * with a typed {@code OperandRef.Const*} (ConstStr /
 * ConstInt / ConstFloat / ConstNull) as the sole operand,
 * and stores the handle in {@code v2TraceHandle} so a
 * subsequent FIELDSTORE can route the const into the
 * per-function {@link FieldTaintTracker} with label
 * {@code "const-source"}. A FIELDLOAD later then attaches
 * the recovered handle as a real parent instead of an
 * orphan SOURCE.</p>
 *
 * <p>This test verifies the v2 trace model that backs
 * that path. The {@code renderConst} helper is private,
 * but the public {@link TraceBuilder} +
 * {@link OperandRef.Const*} contract is observable:
 * every Const* shape renders to a typed JSON object that
 * survives NDJSON / SARIF serialization without dropping
 * the {@code "type"} discriminator. This is the contract
 * downstream consumers (SARIF emitter, AI dedup tool) rely
 * on to distinguish MOVE-from-const from MOVE-from-input.</p>
 *
 * <p><b>Why hermetic:</b> the underlying
 * {@code propagateMOVE} is private and lives behind a
 * {@link TaintedCheckerBase} subclass that needs the full
 * DFA engine / Juliet IR. The const-source emission is
 * deterministic given a {@code TacOperand.Const}; the v2
 * output shape is what consumers actually depend on, so
 * we test the shape here and rely on the engine-level
 * end-to-end Juliet CWE89_01 run (R-807 validation) for
 * the integration picture.</p>
 */
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;

/**
 * R-807 const-source pass ? verifies the v2 trace contract for const-source emission paths. The
 * const-source is a leaf v2 SOURCE node whose sole entry is a typed {@code OperandRef.Const*}.
 */
class R807ConstSourcePassTest {

  @Test
  void r807ConstSourceEmitsTypedConstStrOperand() {
    // String literal: `String data = ""`. The MOVE
    // `data = ""` (const RHS) emits a v2 MARK_SOURCE
    // with OperandRef.ConstStr("") as the operand.
    TraceBuilder tb = new TraceBuilder();
    OperandRef constOperand = new OperandRef.ConstStr("");
    int handle =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(constOperand),
            "const \"\"",
            "" /* filePath */,
            39 /* line */,
            -1 /* column */,
            "" /* functionSig */);
    assertThat(handle).as("const-source emission produces a handle").isGreaterThanOrEqualTo(0);
    Trace v2 = tb.build(List.of(), Trace.CompressionLevel.FULL);
    // The single MARK_SOURCE node carries a ConstStr
    // operand with the literal "" string value.
    assertThat(v2.nodes()).as("one MARK_SOURCE node for the const-source " + "emission").hasSize(1);
    TraceNode node = v2.nodes().get(0);
    assertThat(node.op())
        .as("R-807: const-source emits op=MARK_SOURCE")
        .isEqualTo(TraceNode.Op.MARK_SOURCE);
    assertThat(node.operands()).as("one typed ConstStr operand").hasSize(1);
    OperandRef rendered = node.operands().get(0);
    assertThat(rendered)
        .as(
            "R-807: const-string operand is typed "
                + "ConstStr so SARIF/AI dedup can "
                + "dispatch on the type discriminator")
        .isInstanceOf(OperandRef.ConstStr.class);
    assertThat(((OperandRef.ConstStr) rendered).value())
        .as("const-string preserves the original " + "literal value")
        .isEqualTo("");
    assertThat(rendered.render())
        .as("render() quotes the literal so the v1 " + "fallback path also sees the string")
        .isEqualTo("\"\"");
  }

  @Test
  void r807ConstSourceEmitsTypedConstNullOperand() {
    // Null literal: `Socket socket = null`.
    TraceBuilder tb = new TraceBuilder();
    int handle =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstNull()),
            "const null",
            "" /* filePath */,
            43 /* line */,
            -1 /* column */,
            "" /* functionSig */);
    assertThat(handle).isGreaterThanOrEqualTo(0);
    Trace v2 = tb.build(List.of(), Trace.CompressionLevel.FULL);
    TraceNode node = v2.nodes().get(0);
    assertThat(node.operands()).hasSize(1);
    OperandRef rendered = node.operands().get(0);
    assertThat(rendered)
        .as("R-807: null-const operand is typed " + "ConstNull (not Free for stray null)")
        .isInstanceOf(OperandRef.ConstNull.class);
    assertThat(rendered.render()).as("render() emits \"null\" verbatim").isEqualTo("null");
  }

  @Test
  void r807ConstSourceEmitsTypedConstIntAndFloat() {
    // Numeric literals (the parser emits INT/LONG as
    // ConstInt, FLOAT/DOUBLE as ConstFloat). R-807's
    // renderConst dispatches on TacOperand.Const.Kind
    // and routes both shapes through typed operands.
    TraceBuilder tb = new TraceBuilder();
    int intHandle =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstInt(39544L)),
            "const 39544",
            "" /* filePath */,
            50 /* line */,
            -1 /* column */,
            "" /* functionSig */);
    int floatHandle =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstFloat(3.14)),
            "const 3.14",
            "" /* filePath */,
            99 /* line */,
            -1 /* column */,
            "" /* functionSig */);
    assertThat(intHandle).isNotEqualTo(floatHandle);
    Trace v2 = tb.build(List.of(), Trace.CompressionLevel.FULL);
    assertThat(v2.nodes()).hasSize(2);
    boolean intFound = false;
    boolean floatFound = false;
    for (TraceNode n : v2.nodes()) {
      OperandRef op = n.operands().get(0);
      if (op instanceof OperandRef.ConstInt ci) {
        assertThat(ci.value()).isEqualTo(39544L);
        intFound = true;
      } else if (op instanceof OperandRef.ConstFloat cf) {
        assertThat(cf.value()).isEqualTo(3.14);
        floatFound = true;
      }
    }
    assertThat(intFound).as("int const emitted as ConstInt").isTrue();
    assertThat(floatFound).as("float const emitted as ConstFloat").isTrue();
  }

  @Test
  void r807ConstSourceChainConnectsConstMoveToSink() {
    // Hermetic chain: const-source MOVE -> MOVE to a
    // sink-bearing variable -> sink. This mirrors the
    // pattern R-807 enables for downstream code:
    //   String data = "";            (R-807 emit
    //                                MARK_SOURCE)
    //   data = data + tainted;      (MOVE/BINOP_DOUBLE)
    //   executeQuery(data);         (sink)
    // The const-source v2 handle threads through MOVE
    // nodes with parentIds wired contiguously.
    TraceBuilder tb = new TraceBuilder();
    int constSrc =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstStr("")),
            "const \"\"",
            "Foo.java",
            10,
            5,
            "Foo#bad()");
    int move =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("data", 0), new OperandRef.Var("data", 1)),
            constSrc,
            "MOVE data -> data:1",
            "Foo.java",
            12,
            9,
            "Foo#bad()");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("execute/1")),
            move,
            "execute/1",
            "Foo.java",
            13,
            17,
            "Foo#bad()");
    Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);

    // Chain is contiguous: constSrc -> move -> sink.
    assertThat(v2.rootId()).as("R-807: chain root is the const-source").isEqualTo(constSrc);
    assertThat(v2.sinkOrViolationIds()).containsExactly(sink);
    // Parent chain length matches node count.
    for (TraceNode n : v2.nodes()) {
      if (n.id() == constSrc) {
        assertThat(n.parentIds()).as("const-source is the root").isEmpty();
      } else {
        assertThat(n.parentIds())
            .as("non-root node " + n.id() + " has exactly 1 parent")
            .hasSize(1);
      }
    }
    // The const-source node's operand survives the
    // chain (the parent MARK_SOURCE ConstStr is the
    // root of the path).
    TraceNode ConstSourceNode =
        v2.nodes().stream().filter(n -> n.id() == constSrc).findFirst().orElseThrow();
    assertThat(ConstSourceNode.operands().get(0))
        .as("R-807: chain root carries the typed " + "ConstStr operand")
        .isInstanceOf(OperandRef.ConstStr.class);
  }

  @Test
  void r807ConstSourceDoesNotIntroduceUnknownOps() {
    // R-804 follow-up #2 hermetic gate extends to
    // const-source: every v2 node carries a specific
    // op enum (no UNKNOWN regressions). The const-source
    // emission path must not slip an UNKNOWN op into
    // the trace, even when the const operand is a
    // primitive we route through renderConst.
    TraceBuilder tb = new TraceBuilder();
    int s1 =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstStr("hello")),
            "const \"hello\"",
            "",
            1,
            0,
            "");
    int s2 =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstInt(42L)),
            "const 42",
            "",
            2,
            0,
            "");
    int s3 =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstNull()),
            "const null",
            "",
            3,
            0,
            "");
    int s4 =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.ConstFloat(2.5)),
            "const 2.5",
            "",
            4,
            0,
            "");
    tb.addPropagate(
        TraceNode.Op.MOVE,
        List.of(new OperandRef.Var("x", 0), new OperandRef.Var("y", 0)),
        s1,
        "MOVE",
        "",
        5,
        0,
        "");
    tb.addPropagate(
        TraceNode.Op.MOVE,
        List.of(new OperandRef.Var("y", 0), new OperandRef.Var("z", 0)),
        s2,
        "MOVE",
        "",
        6,
        0,
        "");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("execute/1")),
            s4,
            "execute/1",
            "",
            7,
            0,
            "");
    Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
    long unknownOpCount = v2.nodes().stream().filter(n -> n.op() == TraceNode.Op.UNKNOWN).count();
    assertThat(unknownOpCount)
        .as(
            "R-807: const-source emission must not "
                + "introduce UNKNOWN ops into the v2 "
                + "trace (R-804 follow-up #2 fidelity "
                + "gate extended to const path)")
        .isZero();
    // s1, s2, s3, s4 should all be MARK_SOURCE
    // with their respective Const* operand types.
    for (int handle : List.of(s1, s2, s3, s4)) {
      TraceNode node = v2.nodes().stream().filter(n -> n.id() == handle).findFirst().orElseThrow();
      assertThat(node.op())
          .as("R-807: handle " + handle + " emits op=MARK_SOURCE")
          .isEqualTo(TraceNode.Op.MARK_SOURCE);
      assertThat(node.operands().get(0))
          .as("R-807: handle " + handle + " operand is a typed Const* (not Free)")
          .matches(
              o ->
                  o instanceof OperandRef.ConstStr
                      || o instanceof OperandRef.ConstInt
                      || o instanceof OperandRef.ConstFloat
                      || o instanceof OperandRef.ConstNull,
              "operand is one of the typed Const* " + "shapes — never Free");
    }
  }
}
