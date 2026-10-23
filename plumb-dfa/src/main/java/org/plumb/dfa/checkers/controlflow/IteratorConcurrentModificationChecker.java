package org.plumb.dfa.checkers.controlflow;

import org.plumb.rules.RuleDef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * CWE-820 (Missing Lock / Concurrent Modification) check in the unified DFA engine.
 *
 * <p>Tracks, per function, the set of collection <em>source variables</em> that have an outstanding
 * iterator, and fires a finding when one of those source variables is mutated ({@code add}/{@code
 * remove}/{@code clear}/{@code addAll}/{@code removeAll}/{@code retainAll}) while the iterator is
 * still in scope. This is the classic "for-each loop that modifies the list"
 * ConcurrentModificationException source.
 *
 * <p><b>Why DFA, not AST.</b> AST can see the syntactic structure of an enhanced-for loop, but the
 * iterator-mutation relationship crosses scopes (e.g. an iterator obtained in a helper, a mutation
 * in another statement) and depends on aliasing (e.g. two locals that hold the same collection).
 * The IR's per-function data flow tracks the aliasing fact: after {@code t = list.iterator()},
 * {@code list} has an open iterator, and any later {@code list.add/remove/clear/...} on the same
 * alias is a CME candidate.
 *
 * <p><b>Limits.</b> The per-function view catches intra-procedural CMEs. Cross-function (caller
 * passes a collection, callee iterates + mutates) is not detected here; the per-function summary
 * could carry a "may-iterate" fact, but doing so would require plumbing through {@link
 * #summarizeFunction}, which is left for a later round. CWE-820 in a single function is already the
 * bulk of the real-world noise.
 */
public final class IteratorConcurrentModificationChecker implements DfaChecker {

  /** Per source-var: list of CALL instructions that opened an iterator on it. */
  private final Map<String, TacInstruction> openIterators = new HashMap<>();

  /** Per source-var: list of CALL instructions that already fired (dedup). */
  private final Set<String> alreadyFired = new HashSet<>();

  @Override
  public String name() {
    return "IteratorConcurrentModification";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    openIterators.clear();
    alreadyFired.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind != TacInstruction.Kind.CALL) return;
    if (ins.calleeSig == null) return;

    if (isIteratorCall(ins.calleeSig)) {
      // CALL shape: t = list.iterator() — args[0] is the
      // collection (the receiver is prepended to args
      // by ExpressionTranslator.translateCall, line 267).
      if (!ins.args.isEmpty() && ins.args.get(0) instanceof TacOperand.TacVar src) {
        openIterators.putIfAbsent(src.name(), ins);
      }
      return;
    }

    if (isMutatingCall(ins.calleeSig) && !ins.args.isEmpty()) {
      TacOperand first = ins.args.get(0);
      if (first instanceof TacOperand.TacVar src
          && openIterators.containsKey(src.name())
          && alreadyFired.add(src.name())) {
        emit(
            ctx,
            ins,
            fn,
            src.name(),
            ins.calleeSig + " while an iterator over " + src.name() + " is still in scope");
      }
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static void emit(
      AnalysisContext ctx,
      TacInstruction ins,
      TacFunction fn,
      String collectionName,
      String detail) {
    // R-812 v2 trace: single-node VIOLATION
    // with ITERATOR_CME op + a Free operand
    // encoding the "cme: <detail>" shape so
    // the AI dedup layer can see the collection
    // name + mutation op without regex-parsing
    // the legacy flow string.
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.ITERATOR_CME,
        "cme: " + detail,
        "IteratorConcurrentModification",
        "dfa-controlflow-iterator-concurrent-modification",
        "Collection "
            + collectionName
            + " is mutated via "
            + detail
            + " — ConcurrentModificationException at run time",
        RuleDef.Severity.MEDIUM,
        "CWE-820",
        "cme: " + detail);
  }

  /**
   * Is {@code calleeSig} an iterator-opening call? The signature formats produced by the translator
   * are:
   *
   * <ul>
   *   <li>{@code "iterator/0"} ? for-each loop expansion (see {@code
   *       StatementTranslator.translateForEach}, line 278). The iterable is the only arg.
   *   <li>{@code "{var}.iterator/0"} or {@code "{var}.listIterator/0"} ? explicit {@code
   *       list.iterator()} call. The variable name is the receiver prefix.
   *   <li>{@code "{class}#iterator"} or {@code "{class}#iterator/0"} ? older translations that used
   *       the FQ class name.
   * </ul>
   *
   * <p>We accept any of these and only inspect the method-name segment.
   */
  private static boolean isIteratorCall(String sig) {
    if (sig == null) return false;
    String method = methodName(sig);
    return "iterator".equals(method) || "listIterator".equals(method);
  }

  /**
   * Is {@code calleeSig} a mutating call on a collection (add / remove / clear / addAll / removeAll
   * / retainAll / put / putIfAbsent)? Returns false for read-only calls (get / contains / size /
   * isEmpty).
   */
  private static boolean isMutatingCall(String sig) {
    if (sig == null) return false;
    String method = methodName(sig);
    switch (method) {
      case "add":
      case "remove":
      case "clear":
      case "addAll":
      case "removeAll":
      case "retainAll":
      case "put":
      case "putIfAbsent":
      case "removeIf":
      case "replaceAll":
        return true;
      default:
        return false;
    }
  }

  /**
   * Extract the method name from a callee signature. Accepts both {@code
   * "{receiver}.{method}/{arity}"} (translator default) and {@code "{class}#{method}/{arity}"}
   * (older) forms. Returns {@code null} if neither delimiter is present.
   */
  private static String methodName(String sig) {
    int lastDot = sig.lastIndexOf('.');
    int hash = sig.lastIndexOf('#');
    int sep = Math.max(lastDot, hash);
    if (sep < 0) {
      // Bare "iterator" / "iterator/0" — method name
      // is the whole prefix.
      int slash = sig.indexOf('/');
      return slash < 0 ? sig : sig.substring(0, slash);
    }
    String tail = sig.substring(sep + 1);
    int slash = tail.indexOf('/');
    return slash < 0 ? tail : tail.substring(0, slash);
  }
}
