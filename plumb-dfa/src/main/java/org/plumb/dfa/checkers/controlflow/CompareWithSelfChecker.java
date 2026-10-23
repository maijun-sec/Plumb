package org.plumb.dfa.checkers.controlflow;

import org.plumb.rules.RuleDef;
import java.util.HashMap;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * CWE-570 (Expression is Always False) and CWE-571 (Expression is Always True) in the unified DFA
 * engine.
 *
 * <p>Detects the classic <em>compare-with-self</em> pattern:
 *
 * <ul>
 *   <li>{@code if (x == x) ...} ? always true (CWE-571). The body executes unconditionally. CWE-561
 *       (Dead Code) is a related but distinct finding for the same pattern.
 *   <li>{@code if (x != x) ...} ? always false (CWE-570). The body is unreachable.
 * </ul>
 *
 * <p>The DFA tracks per-function alias classes (the same mechanism as {@link
 * CollectionSelfReferenceChecker}): after {@code a = x; b = x}, both {@code a} and {@code b} are in
 * the same class, so {@code a == b} is provably true. The check also fires on the simpler syntactic
 * case {@code x == x} (both sides are literally the same var).
 *
 * <p><b>Why DFA, not AST.</b> A pure AST pass catches {@code x == x} but misses {@code a == b}
 * where {@code a} and {@code b} were assigned the same value. The DFA's alias-class tracking makes
 * this precise.
 */
public final class CompareWithSelfChecker implements DfaChecker {

  private final java.util.Map<String, java.util.Set<String>> aliasClasses = new HashMap<>();

  @Override
  public String name() {
    return "CompareWithSelf";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    aliasClasses.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    switch (ins.kind) {
      case MOVE -> {
        if (ins.dest instanceof TacOperand.TacVar dest
            && ins.lhs1 instanceof TacOperand.TacVar src) {
          java.util.Set<String> srcClass = aliasClasses.get(src.name());
          java.util.Set<String> destClass = aliasClasses.get(dest.name());
          if (srcClass == null) {
            srcClass = new java.util.HashSet<>(java.util.List.of(src.name()));
            aliasClasses.put(src.name(), srcClass);
          }
          if (destClass == null) {
            destClass = new java.util.HashSet<>(java.util.List.of(dest.name()));
            aliasClasses.put(dest.name(), destClass);
          }
          java.util.Set<String> merged = new java.util.HashSet<>(srcClass);
          merged.addAll(destClass);
          for (String n : merged) {
            aliasClasses.put(n, merged);
          }
        }
      }
      case BINOP -> {
        if (ins.binop == null) return;
        if (ins.binop != BinaryOperator.EQ && ins.binop != BinaryOperator.NE) return;
        if (!(ins.lhs1 instanceof TacOperand.TacVar lhs)) return;
        if (!(ins.lhs2 instanceof TacOperand.TacVar rhs)) return;
        // Are lhs and rhs in the same alias
        // class? If yes, EQ is always true
        // and NE is always false.
        java.util.Set<String> lhsClass = aliasClasses.get(lhs.name());
        java.util.Set<String> rhsClass = aliasClasses.get(rhs.name());
        if (lhsClass == null) {
          lhsClass = java.util.Set.of(lhs.name());
          aliasClasses.put(lhs.name(), lhsClass);
        }
        if (rhsClass == null) {
          rhsClass = java.util.Set.of(rhs.name());
          aliasClasses.put(rhs.name(), rhsClass);
        }
        if (!java.util.Collections.disjoint(lhsClass, rhsClass)) {
          if (ins.binop == BinaryOperator.EQ) {
            emit(
                ctx,
                ins,
                fn,
                "CWE-571",
                "Comparison "
                    + lhs.name()
                    + " == "
                    + rhs.name()
                    + " is always true — both sides are the same value",
                "compare-with-self: " + lhs + " == " + rhs);
          } else {
            emit(
                ctx,
                ins,
                fn,
                "CWE-570",
                "Comparison "
                    + lhs.name()
                    + " != "
                    + rhs.name()
                    + " is always false — both sides are the same value",
                "compare-with-self: " + lhs + " != " + rhs);
          }
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
    String ruleId = cwe.toLowerCase().replace('-', '.');
    // R-812 v2 trace: single-node VIOLATION
    // with COMPARE_WITH_SELF op. The "x == x"
    // pattern is captured by the op enum +
    // the comparison's operand shape (encoded
    // in the legacy flow string → Free operand).
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.COMPARE_WITH_SELF,
        flow,
        "CompareWithSelf",
        "plumb." + ruleId,
        message,
        RuleDef.Severity.LOW,
        cwe,
        flow);
  }
}
