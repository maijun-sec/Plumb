package org.plumb.dfa.checkers.controlflow;

import org.plumb.rules.RuleDef;
import java.util.HashSet;
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
 * CWE-754 / CWE-595 checks for {@code obj.equals(...)} on the unified DFA engine. Two distinct
 * bugs:
 *
 * <ol>
 *   <li><b>{@code obj.equals(null)} (CWE-754).</b> Always returns {@code false} ? the result cannot
 *       be {@code true} for any non-null receiver. The caller almost certainly meant the inverse:
 *       {@code obj == null} or {@code null == obj}. Catches the literal {@code null} argument at
 *       the TAC level (a {@link TacOperand.Const} with kind {@code NULL}).
 *   <li><b>{@code obj.equals(arg)} where {@code obj} is known to be null (CWE-595 / CWE-476
 *       precursor).</b> The call will throw a {@link NullPointerException} at run time. The DFA
 *       tracks which local vars are assigned the literal {@code null} (or transitively inherit that
 *       fact) and fires when one of them is used as the receiver of an {@code equals} call.
 * </ol>
 *
 * <p><b>Why DFA, not AST.</b> Both checks need to correlate a {@code CALL.equals(...)} with the
 * literal / tracked-null state of its receiver and argument. AST can find {@code x.equals(null)}
 * via a syntactic pattern, but cannot follow the null-state across statements: {@code String s =
 * null; ... s.equals("x");} is a runtime NPE that AST-level pattern matching misses. The DFA's
 * per-function null tracker catches the propagated null.
 *
 * <p>The companion {@code EqualsAvoidNullCheck} in {@code plumb-style} covers the inverse pattern
 * (literal string on the left side of {@code .equals}) at the AST level. We don't duplicate that
 * here.
 */
public final class EqualsCalledOnNullChecker implements DfaChecker {

  /** Per-function: vars that are (or transitively derive from) null. */
  private final Set<String> knownNull = new HashSet<>();

  @Override
  public String name() {
    return "EqualsCalledOnNull";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    knownNull.clear();
    // Parameters are conservatively "may be
    // null" — we don't add them to knownNull
    // (which is "definitely null"). The
    // companion NullDerefChecker covers the
    // may-be-null case with a different
    // (CWE-476) finding.
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    switch (ins.kind) {
      case MOVE -> {
        // Track `dest = null` (literal) and
        // `dest = src` where src is
        // known-null.
        if (ins.dest instanceof TacOperand.TacVar dest) {
          if (ins.lhs1 instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.NULL) {
            knownNull.add(dest.name());
          } else if (ins.lhs1 instanceof TacOperand.TacVar src && knownNull.contains(src.name())) {
            knownNull.add(dest.name());
          }
        }
      }
      case CALL -> {
        if (ins.calleeSig == null) return;
        if (!isEqualsCall(ins.calleeSig)) return;
        if (ins.args == null || ins.args.isEmpty()) return;
        // args[0] is the receiver (thiscall
        // convention; ExpressionTranslator
        // prepends the receiver).
        TacOperand recv = ins.args.get(0);
        // 1. obj.equals(null) — args[1] is
        //    the literal null.
        if (ins.args.size() >= 2) {
          TacOperand arg = ins.args.get(1);
          if (arg instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.NULL) {
            emit(
                ctx,
                ins,
                fn,
                "CWE-754",
                "obj.equals(null) always returns false — " + "use `obj == null` or invert the test",
                "equals-on-null-arg");
            return;
          }
        }
        // 2. obj.equals(arg) where obj is
        //    known null — CWE-595 NPE
        //    precursor.
        if (recv instanceof TacOperand.TacVar recvVar && knownNull.contains(recvVar.name())) {
          emit(
              ctx,
              ins,
              fn,
              "CWE-595",
              ".equals() called on "
                  + recvVar.name()
                  + " which is known to be null — NPE at run time",
              "equals-on-known-null-receiver: " + recvVar.name());
        }
      }
      default -> {
        /* ignore */
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
      String cwe,
      String message,
      String flow) {
    String name = "EqualsCalledOnNull";
    String ruleId = cwe.toLowerCase().replace('-', '.');
    // R-812 v2 trace: single-node VIOLATION
    // with NULL_DEREF_GENERIC op. The flow
    // argument encodes "receiver <var> known
    // null"; pass that as a Free operand so
    // the AI dedup layer can see the receiver
    // name without regex-parsing the legacy
    // flow string.
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.NULL_DEREF_GENERIC,
        flow,
        name,
        "plumb." + ruleId,
        message,
        RuleDef.Severity.MEDIUM,
        cwe,
        flow);
  }

  private static boolean isEqualsCall(String sig) {
    if (sig == null) return false;
    int lastDot = sig.lastIndexOf('.');
    int hash = sig.lastIndexOf('#');
    int sep = Math.max(lastDot, hash);
    String tail = sep < 0 ? sig : sig.substring(sep + 1);
    int slash = tail.indexOf('/');
    String method = slash < 0 ? tail : tail.substring(0, slash);
    return "equals".equals(method);
  }
}
