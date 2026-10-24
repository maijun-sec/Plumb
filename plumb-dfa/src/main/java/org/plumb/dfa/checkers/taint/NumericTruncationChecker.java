package org.plumb.dfa.checkers.taint;

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;

/**
 * R-? — NumericTruncation checker (CWE-197).
 *
 * <p>Casts a tainted int / long / double into a narrower type (byte / short / char) silently
 * truncates high bits, producing an attacker-controllable value. The fix is to validate the range
 * first (e.g. {@code if (data >= Byte.MIN_VALUE && data <= Byte.MAX_VALUE) ...}).
 *
 * <p>Built on the unified {@link TaintedCheckerBase} taint machinery: a var is tainted when it
 * flows from an input-source call (e.g. {@code readLine}, {@code parseInt}) via MOVE / BINOP /
 * CALL. When a {@code CAST} UNARYOP has a tainted source AND narrows the type, fire. Vars validated
 * by an {@code if (v >= LO && v <= HI)} range-check on the same cast type are suppressed (Juliet
 * goodB2G pattern).
 */
public final class NumericTruncationChecker extends TaintedCheckerBase {

  public NumericTruncationChecker() {
    super(
        new TaintRule(
            "dfa-taint-numeric-truncation",
            BuiltinFactKinds.UNTRUSTED_SOURCE,
            "numeric.cast.sinks",
            "CWE-197",
            "MEDIUM",
            TaintDomain.NumericTaint.INSTANCE));
  }

  /** Per-function: vars validated by a range-check of any kind. */
  private final Set<String> validatedVars = new HashSet<>();

  /** Per-function: dedup keys to avoid double-firing. */
  private final Set<String> reported = new HashSet<>();

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    validatedVars.clear();
    reported.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // 1. Track range-checks for the suppression set.
    //    A pattern like `if (v >= -128 && v <= 127)` (and
    //    its IR form: BRANCH on `v >= -128` followed by
    //    BRANCH on `v <= 127`) marks `v` as validated.
    if (ins.kind == TacInstruction.Kind.BINOP
        && isRangeBound(ins.binop)
        && ins.lhs1 instanceof TacOperand.TacVar v) {
      validatedVars.add(v.name());
      return;
    }
    if (ins.kind != TacInstruction.Kind.UNARYOP) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    if (ins.unop != UnaryOperator.CAST) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    if (ins.castType == null
        || ins.dest == null
        || !(ins.dest instanceof TacOperand.TacVar)
        || ins.lhs1 == null
        || !(ins.lhs1 instanceof TacOperand.TacVar src)) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    if (!taintedVars.containsKey(src.name())) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    if (validatedVars.contains(src.name())) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    if (!isNarrowing(src.type(), ins.castType)) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    String key = "cwe197|" + ins.line + "|" + ins.castType;
    if (!reported.add(key)) {
      super.checkInstruction(ins, fn, ctx);
      return;
    }
    emit(ins, fn, ctx, src);
  }

  private static boolean isRangeBound(BinaryOperator op) {
    return op == BinaryOperator.GE
        || op == BinaryOperator.LE
        || op == BinaryOperator.GT
        || op == BinaryOperator.LT
        || op == BinaryOperator.EQ
        || op == BinaryOperator.NE;
  }

  /**
   * Return true if {@code from -> to} is a narrowing cast (wider source type → narrower target
   * type).
   */
  private static boolean isNarrowing(TacType from, TacType to) {
    if (from == null || to == null) return false;
    int fromBits = bitWidth(from);
    int toBits = bitWidth(to);
    return toBits < fromBits;
  }

  private static int bitWidth(TacType t) {
    if (t == null) return 0;
    return switch (t.kind()) {
      case LONG -> 64;
      case INT, BOOLEAN -> 32;
      case CHAR -> 16;
      case SHORT -> 16;
      case BYTE -> 8;
      case FLOAT -> 32;
      case DOUBLE -> 64;
      default -> 0;
    };
  }

  private void emit(
      TacInstruction ins, TacFunction fn, AnalysisContext ctx, TacOperand.TacVar src) {
    Finding f =
        Finding.violation(
            "dfa-taint-numeric-truncation",
            "NumericTruncation",
            "Narrow cast of tainted value '"
                + src.name()
                + "' from "
                + src.type()
                + " to "
                + ins.castType
                + " may silently truncate attacker-controllable bits (CWE-197). "
                + "Validate the range first.",
            RuleDef.Severity.MEDIUM,
            "CWE-197",
            ins.line,
            ins.column,
            fn.signature(),
            "numeric-truncation: " + src.name());
    ctx.reporter().report(f);
  }
}
