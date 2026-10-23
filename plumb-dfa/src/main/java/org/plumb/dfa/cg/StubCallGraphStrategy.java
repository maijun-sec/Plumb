package org.plumb.dfa.cg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * Stub call-graph strategy.
 *
 * <p>Records only direct (static) calls. Virtual calls are not resolved. Useful for very large
 * projects where CHA / RTA would be too slow, and for unit tests that need a small, fast graph.
 *
 * <p>The resulting graph is missing most indirect-call targets, so checkers that rely on complete
 * virtual-dispatch resolution should use {@link ChaCallGraphStrategy} or {@link
 * RtaCallGraphStrategy} instead.
 */
public final class StubCallGraphStrategy implements CallGraphStrategy {

  private final TacProgram program;

  public StubCallGraphStrategy(TacProgram program) {
    this.program = program;
  }

  @Override
  public String name() {
    return "stub";
  }

  @Override
  public CallGraph build() {
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    Map<FunctionNode, List<CallSite>> forward = new HashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      FunctionNode caller = new FunctionNode(fn.signature());
      nodes.add(caller);
      forward.computeIfAbsent(caller, k -> new ArrayList<>());
      int n = fn.instructions().size();
      for (int i = 0; i < n; i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        if (ins.calleeSig == null || ins.calleeSig.isEmpty()) continue;
        FunctionNode callee = new FunctionNode(ins.calleeSig);
        nodes.add(callee);
        forward.computeIfAbsent(callee, k -> new ArrayList<>());
        reverse.computeIfAbsent(callee, k -> new ArrayList<>());
        CallSite cs = new CallSite(caller, callee, i, ins.line, CallSite.Kind.DIRECT);
        forward.get(caller).add(cs);
        reverse.get(callee).add(cs);
      }
    }
    return CallGraphBuilder.buildFromMaps(nodes, forward, reverse);
  }
}
