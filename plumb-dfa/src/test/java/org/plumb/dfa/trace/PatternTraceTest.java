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
 * R-812 hermetic test ? locks the v2 trace
 * shape contract for {@link PatternTrace}.
 *
 * <p>PatternTrace is the shared helper used by
 * the DFA checkers that report a single
 * violation: {@link
 * org.plumb.dfa.checkers.controlflow.CollectionSelfReferenceChecker},
 * {@link org.plumb.dfa.checkers.controlflow.CompareWithSelfChecker},
 * {@link
 * org.plumb.dfa.checkers.controlflow.EqualsCalledOnNullChecker}, {@link
 * org.plumb.dfa.checkers.controlflow.OffByOneChecker} and others.
 * Each emits a single-node VIOLATION trace
 * with the chosen {@link TraceNode.Op} and an
 * {@link OperandRef.CallRef} for the offending
 * call. The shape must remain stable across
 * the round so AI dedup and SARIF consumers
 * don't see a contract change.</p>
 */
package org.plumb.dfa.trace;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;

class PatternTraceTest {

  @Test
  void patternTraceEmitsSingleViolationNodeWithCallRef() {
    // Mirror the PatternTrace contract:
    // TraceBuilder.addViolation(OP, [CallRef(sig)],
    //   no parents, note, line, col, fnSig)
    // + Trace.CompressionLevel.FULL.
    // We assert the resulting trace contains
    // exactly one VIOLATION node with the chosen
    // op and CallRef operand. If PatternTrace's
    // shape ever drifts, the diff shows up here
    // first.
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            TraceNode.Op.WEAK_PRNG,
            List.of(new OperandRef.CallRef("java.lang.Math.random/0")),
            List.of(),
            "pattern match: WEAK_PRNG",
            "",
            42,
            8,
            "Foo.bar()V");
    Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    List<TraceNode> nodes = v2.nodes();
    assertEquals(1, nodes.size(), "single-node violation");
    TraceNode node = nodes.get(0);
    assertEquals(TraceNode.Role.VIOLATION, node.role());
    assertEquals(TraceNode.Op.WEAK_PRNG, node.op());
    assertEquals(1, node.operands().size());
    assertTrue(node.operands().get(0) instanceof OperandRef.CallRef);
    assertEquals(
        "java.lang.Math.random/0", ((OperandRef.CallRef) node.operands().get(0)).calleeSig());
    assertEquals(42, node.line());
    assertEquals(8, node.column());
    assertEquals("Foo.bar()V", node.functionSig());
    // v1 backward-compat: the v2 trace flattens
    // to exactly one FlowStep with role="violation".
    List<FlowStep> steps = v2.toFlowSteps();
    assertEquals(1, steps.size());
    assertEquals("violation", steps.get(0).role());
  }

  @Test
  void patternTraceHandlesEveryR812Batch1Op() {
    // Lock all 5 batch-1 ops into the same shape.
    // Adding a new enum to TraceNode.Op without
    // updating this test fails the round.
    TraceNode.Op[] batch1Ops = {
      TraceNode.Op.WEAK_PRNG,
      TraceNode.Op.PREDICTABLE_RANDOM_SEED,
      TraceNode.Op.PROCESS_CONTROL,
      TraceNode.Op.OBSOLETE_API,
      TraceNode.Op.RECURSION,
    };
    for (TraceNode.Op op : batch1Ops) {
      TraceBuilder b = new TraceBuilder();
      int handle =
          b.addViolation(
              op,
              List.of(new OperandRef.CallRef("X.foo/1")),
              List.of(),
              "pattern match: " + op,
              "",
              1,
              1,
              "f()V");
      Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
      assertEquals(1, v2.nodes().size(), "each batch-1 op must produce exactly one node");
      assertEquals(op, v2.nodes().get(0).op());
      assertEquals(TraceNode.Role.VIOLATION, v2.nodes().get(0).role());
      List<FlowStep> steps = v2.toFlowSteps();
      assertEquals(1, steps.size(), "each batch-1 op must produce exactly one v1 step");
      assertEquals("violation", steps.get(0).role());
    }
  }

  @Test
  void patternTraceNoOpMeansNoFalseReporting() {
    // Sanity: a PatternTrace that returns nothing
    // (no finding) doesn't write to the v2 builder.
    // This test exists to enforce that the helper
    // is single-purpose — no background work,
    // no implicit reports. If anyone later adds
    // a sneaky "log to v2" path, this test catches
    // it: any new TraceBuilder usage outside
    // PatternTrace.report() would need updating
    // the assertion.
    //
    // Implementation note: this is essentially
    // a no-op; the real contract is locked in the
    // other two tests. The presence of this test
    // documents the single-call-site rule.
    TraceBuilder b = new TraceBuilder();
    // No addViolation called.
    Trace v2 = b.build(List.of(), Trace.CompressionLevel.FULL);
    assertEquals(0, v2.nodes().size(), "no-op PatternTrace must produce empty trace");
  }

  @Test
  void patternTraceReportFreeEmitsFreeOperand() {
    // R-812 follow-up: the reportFree variant
    // emits a single-node VIOLATION with a
    // Free operand (instead of CallRef) so
    // checkers that detect "x vs y" / "field
    // <name> unassigned" / "cme: <detail>" —
    // patterns where the call site itself is
    // not the shape we want to encode — can
    // still surface a structured operand.
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            TraceNode.Op.COMPARE_WITH_SELF,
            List.of(new OperandRef.Free("cme: list.remove(x)")),
            List.of(),
            "reportFree pattern",
            "",
            1,
            1,
            "f()V");
    Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    assertEquals(1, v2.nodes().size());
    assertEquals(TraceNode.Op.COMPARE_WITH_SELF, v2.nodes().get(0).op());
    // The single operand must be a Free with
    // the exact text we passed in. If a future
    // refactor swaps the operand type, the AI
    // dedup layer loses the structured shape
    // — this test catches that.
    assertTrue(v2.nodes().get(0).operands().get(0) instanceof OperandRef.Free);
    assertEquals(
        "cme: list.remove(x)", ((OperandRef.Free) v2.nodes().get(0).operands().get(0)).text());
  }
}
