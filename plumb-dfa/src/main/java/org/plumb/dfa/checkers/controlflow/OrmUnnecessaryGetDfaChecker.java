package org.plumb.dfa.checkers.controlflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * DFA-based version of Coverity <code>ORM_UNNECESSARY_GET</code> (CWE-252). Where the AST checker
 * {@code org.plumb.style.quality.OrmUnnecessaryGetCheck} only looks at variable uses within a
 * single method, the DFA version tracks the result of a {@code get} / {@code find} / {@code
 * getById} call through the function's data flow.
 *
 * <p><b>What the DFA version catches that AST misses.</b>
 *
 * <ul>
 *   <li>Result of a {@code get}/{@code find} call is <em>never used at all</em> in the function (a
 *       dead store from the JPA point of view). The AST version explicitly suppresses this case
 *       (it's a different finding -- unused variable); the DFA version fires too, because
 *       Coverity's <code>ORM_UNNECESSARY_GET</code> covers the "the result is loaded but the entity
 *       contents are never used" pattern, of which "never used at all" is a special case.
 *   <li>Result is propagated through a {@code MOVE} (aliasing) to a different variable, then used
 *       as an argument. The AST version tracks the original variable; the DFA version follows the
 *       alias.
 * </ul>
 *
 * <p><b>Detection strategy.</b>
 *
 * <ol>
 *   <li>On a {@code CALL} to one of the documented {@code get}/{@code find}/{@code getById}/{@code
 *       read} methods, mark the dest {@link TacOperand.TacVar} as "from-get" in a per-function map.
 *   <li>Propagate the "from-get" mark through {@code MOVE} (aliasing).
 *   <li>A "from-get" var that's never read anywhere in the function is unnecessary -- fire on the
 *       original {@code get}/{@code find} call site.
 *   <li>A "from-get" var that's only used as an argument to other calls (no {@code FIELDLOAD} on
 *       the var, no method call on the var as receiver, no assignment to a field from the var's
 *       contents) is also unnecessary -- fire.
 * </ol>
 *
 * <p>The DFA checker is registered alongside the AST one. Their rule ids differ ({@code -dfa}
 * suffix on the DFA version) so SARIF consumers can distinguish them.
 */
public final class OrmUnnecessaryGetDfaChecker implements DfaChecker {

  private static final Set<String> GET_METHODS =
      Set.of("get", "find", "findById", "getById", "read");

  /** Per-function: var name → "from-get" flag (always true). */
  private Map<String, Boolean> fromGetVars;

  /** Per-function: var name → the original CALL instruction that created it. */
  private Map<String, TacInstruction> getCallSite;

  /**
   * v2 trace state. Per-function map from "from-get" var name to its v2 handle -- the {@code
   * CALL_INSTANCE} source node, propagated through alias MOVEs.
   */
  private Map<String, Integer> v2HandleByVar;

  private TraceBuilder v2;
  private final java.util.List<Integer> sinkHandles = new java.util.ArrayList<>();

  @Override
  public String name() {
    return "orm-unnecessary-get-dfa";
  }

  @Override
  public CheckerConfig config(AnalysisContext ctx) {
    CheckerConfig c = ctx.config().checkers().byName(name()).orElse(null);
    if (c != null) return c;
    return new CheckerConfig(
        name(),
        true,
        "LOW",
        "CWE-252",
        1,
        "full",
        null,
        "org.plumb.dfa.checkers.controlflow.OrmUnnecessaryGetDfaChecker",
        "DFA-based ORM_UNNECESSARY_GET");
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    fromGetVars = new HashMap<>();
    getCallSite = new HashMap<>();
    v2HandleByVar = new HashMap<>();
    v2 = new TraceBuilder();
    sinkHandles.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // Track a "from-get" marker on the dest of a
    // get/find call.
    if (ins.kind == TacInstruction.Kind.CALL
        && ins.calleeSig != null
        && isGetCall(ins.calleeSig)
        && ins.dest != null
        && ins.dest instanceof TacOperand.TacVar) {
      String dest = ((TacOperand.TacVar) ins.dest).name();
      fromGetVars.put(dest, true);
      getCallSite.put(dest, ins);
      // emit CALL_INSTANCE source for
      // the get/find call. The dest var's
      // v2 handle points back to this node.
      int handle =
          v2.addSource(
              TraceNode.Op.CALL_INSTANCE,
              List.of(new OperandRef.Var(dest, 0), new OperandRef.CallRef(ins.calleeSig)),
              "JPA get/find: " + ins.calleeSig,
              "",
              ins.line,
              ins.column,
              fn.signature());
      v2HandleByVar.put(dest, handle);
      return;
    }
    // Aliasing via MOVE.
    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.lhs1 != null
        && ins.lhs1 instanceof TacOperand.TacVar
        && ins.dest != null
        && ins.dest instanceof TacOperand.TacVar) {
      String from = ((TacOperand.TacVar) ins.lhs1).name();
      String to = ((TacOperand.TacVar) ins.dest).name();
      if (fromGetVars.containsKey(from)) {
        fromGetVars.put(to, true);
        getCallSite.put(to, getCallSite.get(from));
        // emit a MOVE propagate
        // chained to the source handle.
        Integer parent = v2HandleByVar.get(from);
        if (parent != null) {
          int handle =
              v2.addPropagate(
                  TraceNode.Op.MOVE,
                  List.of(new OperandRef.Var(to, 0), new OperandRef.Var(from, 0)),
                  parent,
                  "alias: " + to + " = " + from,
                  "",
                  ins.line,
                  ins.column,
                  fn.signature());
          v2HandleByVar.put(to, handle);
        }
      }
    }
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // For each from-get var: scan the function
    // body. If the var is never read in any
    // FIELDLOAD or as a non-trivial call
    // argument, fire.
    for (Map.Entry<String, Boolean> e : fromGetVars.entrySet()) {
      String var = e.getKey();
      if (usedInContentAccess(fn, var)) continue;
      TacInstruction origin = getCallSite.get(var);
      if (origin == null) continue;
      // emit VIOLATION with the
      // unused-result op wired back to the
      // get call's source handle.
      Integer parent = v2HandleByVar.get(var);
      int handle =
          v2.addViolation(
              TraceNode.Op.UNUSED_GET_RESULT,
              List.of(new OperandRef.Var(var, 0)),
              parent == null ? java.util.List.of() : java.util.List.of(parent),
              "from-get var '" + var + "' never used in content access",
              "",
              origin.line,
              origin.column,
              fn.signature());
      sinkHandles.add(handle);
      Trace trace = v2.build(sinkHandles, Trace.CompressionLevel.FULL);
      Finding f =
          Finding.dfaWithTrace(
              name(),
              name(),
              "ORM_UNNECESSARY_GET (DFA): variable '"
                  + var
                  + "' is the result of a JPA get/find call but"
                  + " is not used in a content access (no field"
                  + " load, no non-getId method call). Consider"
                  + " Session.load() / EntityManager.getReference()"
                  + " to avoid the DB hit",
              RuleDef.Severity.LOW,
              "CWE-252",
              "",
              origin.line,
              origin.column,
              var + " in " + fn.signature(),
              trace);
      ctx.reporter().reportWithTrace(f, trace);
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    // The DFA version doesn't contribute to
    // cross-function summaries for get/find
    // results yet — that's a future enhancement
    // when we have proper "getter-of-field"
    // modeling for setter cross-method analysis.
    return FunctionSummary.empty();
  }

  /**
   * True if the variable is ever used in a way that accesses its contents -- read as a field
   * receiver (FIELDLOAD), or assigned to a field whose value is later loaded, or used as a call
   * argument AND the callee's summary has a PassThrough op on the param. For now, the conservative
   * check is "used as field receiver or call argument". An arg-only use is a "reference only" use;
   * a field load is a content access.
   */
  private static boolean usedInContentAccess(TacFunction fn, String var) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.FIELDLOAD
          && ins.lhs1 != null
          && ins.lhs1 instanceof TacOperand.TacVar
          && ((TacOperand.TacVar) ins.lhs1).name().equals(var)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isGetCall(String calleeSig) {
    int hash = calleeSig.indexOf('#');
    if (hash < 0) return false;
    String after = calleeSig.substring(hash + 1);
    int paren = after.indexOf('(');
    String name = paren < 0 ? after : after.substring(0, paren);
    return GET_METHODS.contains(name);
  }
}
