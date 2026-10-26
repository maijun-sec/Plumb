package org.plumb.dfa.checkers.controlflow;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * DFA-based version of Coverity <code>SERVLET_ATOMICITY</code> (CWE-362). Where the AST checker
 * {@code org.plumb.style.concurrency.ServletAtomicityCheck} only looks at {@code getAttribute} /
 * {@code setAttribute} pairs within a single method, the DFA version tracks those calls across
 * methods.
 *
 * <p><b>What the DFA version catches that AST misses.</b>
 *
 * <ul>
 *   <li>The {@code getAttribute("k")} is in {@code doGet} and the {@code setAttribute("k", ...)} is
 *       in {@code doPost} -- different methods on the same servlet. The AST version only checks
 *       per-method, so it misses this race entirely.
 * </ul>
 *
 * <p><b>Detection strategy.</b>
 *
 * <ol>
 *   <li>For every class (in any file in the program), collect every {@code
 *       session.getAttribute("k")} and {@code session.setAttribute("k", ...)} call. The key {@code
 *       "k"} is the first argument (must be a string literal for the checker to fire -- runtime
 *       keys can't be analysed statically).
 *   <li>For each key with at least one {@code get} and at least one {@code set} across the program,
 *       fire once.
 * </ol>
 *
 * <p>The DFA checker doesn't attempt to verify synchronization -- the AST checker does that for
 * in-method accesses. The DFA version is purely the "is the same attribute touched from multiple
 * methods" signal, which the AST can't see.
 */
public final class ServletAtomicityDfaChecker implements DfaChecker {

  /** key → set of method signatures that did a getAttribute(key). */
  private final Map<String, Set<String>> getSites = new LinkedHashMap<>();

  /** key → set of method signatures that did a setAttribute(key, ...). */
  private final Map<String, Set<String>> setSites = new LinkedHashMap<>();

  @Override
  public String name() {
    return "servlet-atomicity-dfa";
  }

  @Override
  public CheckerConfig config(AnalysisContext ctx) {
    CheckerConfig c = ctx.config().checkers().byName(name()).orElse(null);
    if (c != null) return c;
    return new CheckerConfig(
        name(),
        true,
        "HIGH",
        "CWE-362",
        1,
        "full",
        null,
        "org.plumb.dfa.checkers.controlflow.ServletAtomicityDfaChecker",
        "DFA-based SERVLET_ATOMICITY");
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind != TacInstruction.Kind.CALL) return;
    if (ins.calleeSig == null) return;
    if (ins.args == null || ins.args.isEmpty()) return;
    String method = methodName(ins.calleeSig);
    if (!"getAttribute".equals(method) && !"setAttribute".equals(method)) return;
    if (!(ins.args.get(0) instanceof TacOperand.Const)) return;
    TacOperand.Const first = (TacOperand.Const) ins.args.get(0);
    if (!"STRING".equals(first.kind().name())) return;
    String attrName = String.valueOf(first.value());
    if ("getAttribute".equals(method)) {
      getSites.computeIfAbsent(attrName, k -> new LinkedHashSet<>()).add(fn.signature());
    } else {
      setSites.computeIfAbsent(attrName, k -> new LinkedHashSet<>()).add(fn.signature());
    }
  }

  @Override
  public void finish(AnalysisContext ctx) {
    for (String key : getSites.keySet()) {
      Set<String> gets = getSites.get(key);
      Set<String> sets = setSites.getOrDefault(key, Set.of());
      Set<String> all = new HashSet<>();
      all.addAll(gets);
      all.addAll(sets);
      // For cross-method atomicity we need at
      // least TWO different functions, AND at
      // least one get + one set. A single-method
      // get+set is the AST's job.
      if (all.size() < 2) continue;
      if (gets.isEmpty() || sets.isEmpty()) continue;
      // v2 trace: cross-method summary
      // shape. The two method sets
      // (get-sites, set-sites) are the parents
      // of a single VIOLATION node. Each
      // method's signature is exposed as a
      // Free operand so the AI dedup layer
      // can see "which methods race on this
      // attribute" without regex-parsing
      // the legacy flow string.
      TraceBuilder b = new TraceBuilder();
      java.util.List<OperandRef> ops = new java.util.ArrayList<>(2);
      ops.add(new OperandRef.Free("attr:" + key));
      ops.add(new OperandRef.Free("getMethods:" + gets.size() + ",setMethods:" + sets.size()));
      int handle =
          b.addViolation(
              TraceNode.Op.SUMMARY_SINK,
              ops,
              List.of(),
              "attribute '" + key + "' raced across " + all.size() + " method(s)",
              "",
              -1,
              -1,
              "");
      Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
      Finding f =
          Finding.dfaWithTrace(
              name(),
              name(),
              "SERVLET_ATOMICITY (DFA): attribute '"
                  + key
                  + "' is read by "
                  + gets.size()
                  + " method(s) and written by "
                  + sets.size()
                  + " method(s) — concurrent requests can race on"
                  + " the read-modify-write",
              RuleDef.Severity.HIGH,
              "CWE-362",
              "",
              -1,
              -1,
              key + " (read by " + gets + ", written by " + sets + ")",
              v2);
      ctx.reporter().reportWithTrace(f, v2);
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static String methodName(String calleeSig) {
    int hash = calleeSig.indexOf('#');
    if (hash < 0) return "";
    String after = calleeSig.substring(hash + 1);
    int paren = after.indexOf('(');
    return paren < 0 ? after : after.substring(0, paren);
  }
}
