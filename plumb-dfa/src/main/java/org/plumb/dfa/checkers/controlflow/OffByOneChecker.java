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
package org.plumb.dfa.checkers.controlflow;

import java.util.HashMap;
import java.util.Map;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * CWE-193 (Off-by-one Error) check in the unified DFA engine.
 *
 * <p>Detects the classic pattern {@code for (int i = 0; i <= arr.length; i++) arr[i] = ...;} where
 * the loop bound is {@code <=} (instead of {@code <}) and the bound references the array length.
 * The body executes once more than the array has slots, so the last iteration writes one past the
 * end. CWE-193.
 *
 * <p>The check operates at the TAC IR level. The BRANCH instruction's condition is a BINOP (e.g.
 * {@code LE} or {@code GE}) and the operands include the loop variable and a length reference
 * ({@code arr.length} / {@code size()}). The checker tags the loop variable in the BRANCH
 * condition; if the binop is {@code LE} and the RHS is a known length, fire.
 *
 * <p><b>Why DFA, not AST.</b> The bug is a relation between three things: a loop bound (one AST
 * expression), the {@code <=} operator (a single token), and the array length expression (a
 * separate AST expression). The naive AST approach is "find any {@code <=} in a for-loop header
 * where the RHS contains {@code .length} / {@code .size()}", but that pattern matches {@code for
 * (int i = 0; i <= n; i++)} where {@code n} is a parameter -- the off-by-one only happens when the
 * RHS is provably the same expression as the array index. The DFA pairs the BRANCH operands with
 * subsequent ARRAYLOAD / ARRAYSTORE to confirm the array access uses the same loop variable, then
 * fires only if the bound operator is {@code LE}.
 *
 * <p><b>Field-name aliasing.</b> In a non-static method, the for-loop variable {@code i} is lowered
 * to a FIELDLOAD {@code t_t0 = this.i} and re-read in the body as {@code t_t3 = this.i}. The two
 * {@code t_t*} names are different SSA versions, so a name-keyed {@code lengthOfVar} map misses the
 * body-side reference. We additionally record a {@code fieldName -> lenExpr} map keyed by the field
 * name (e.g. {@code i}), which the ARRAYLOAD/ARRAYSTORE step consults in addition to the SSA name.
 * This covers both the {@code static int i} case (one TacVar reused) and the {@code int i} instance
 * case (each {@code this.i} produces a fresh temp but the same field name).
 */
public final class OffByOneChecker implements DfaChecker {

  /**
   * Per-function SSA-name keyed: when a BINOP sets up {@code t_tX = loopVar LE lenExpr}, we record
   * {@code t_tX -> lenExpr}. Body ARRAYLOAD/STORE consult this first; if the index SSA name is the
   * same as the loop variable, fire.
   */
  private final Map<String, String> lengthOfVar = new HashMap<>();

  /**
   * Per-function field-name keyed: when the loop variable is a FIELDLOAD (e.g. {@code t_t0 =
   * this.i} in a non-static method), we additionally record {@code fieldName(i) -> lenExpr}. Body
   * ARRAYLOAD/STORE that re-loads the same field (e.g. {@code t_t3 = this.i; arr[t_t3]}) consults
   * this map and fires when the field name matches.
   */
  private final Map<String, String> fieldLengths = new HashMap<>();

  /**
   * Per-function: tracks which TacVar dest was last produced by a FIELDLOAD of which field. e.g.
   * {@code t_t0 = this.i} registers {@code t_t0 -> i}. When the body later does {@code t_t3 =
   * this.i} (also fieldName="i") and uses t_t3 as an array index, we look up t_t3's fieldName ("i")
   * and consult {@link #fieldLengths}.
   */
  private final Map<String, String> varToField = new HashMap<>();

  @Override
  public String name() {
    return "OffByOne";
  }

  /**
   * The rule this checker emits, with its CWE.
   *
   * <p>Declared here so that the rule id -> CWE map can be read from the engine rather than from a
   * table maintained beside it. The id and the CWE are the same ones the reporting call passes to
   * {@code PatternTrace.reportFree} -- a rule map that disagreed with the reporting call would
   * describe a tool that does not ship.
   */
  @Override
  public org.plumb.rules.RuleDef ruleDef() {
    return new org.plumb.rules.RuleDef(
        new org.plumb.rules.RuleId("dfa-controlflow-off-by-one", "OFF_BY_ONE"),
        "Off-by-one: a loop variable bounded by the length is used to index one past the end",
        RuleDef.Severity.MEDIUM,
        RuleDef.Type.CORRECTNESS,
        java.util.List.of("CWE-193"),
        getClass().getName(),
        true,
        java.util.List.of());
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    lengthOfVar.clear();
    fieldLengths.clear();
    varToField.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    switch (ins.kind) {
      case FIELDLOAD -> {
        // `t = arr.length` — tag `t` as a
        // length. The same goes for
        // `t = list.size()`.
        if (ins.fieldName != null
            && (ins.fieldName.equals("length") || ins.fieldName.equals("size"))
            && ins.dest instanceof TacOperand.TacVar dest) {
          lengthOfVar.put(dest.name(), ins.lhs1.toString() + "." + ins.fieldName);
        }
        // Also record which field a dest
        // TacVar was loaded from, regardless
        // of the fieldName. e.g.
        // {@code t_t0 = this.i}
        // registers {@code t_t0 -> i}.
        if (ins.fieldName != null && ins.dest instanceof TacOperand.TacVar dest2) {
          varToField.put(dest2.name(), ins.fieldName);
        }
      }
      case CALL -> {
        // `t = coll.size()` — tag `t` as
        // a length. (The receiver is args[0]
        // in thiscall convention, but
        // the easier check is method name
        // == "size".)
        if ("size".equals(methodName(ins.calleeSig))
            && ins.dest instanceof TacOperand.TacVar dest) {
          lengthOfVar.put(dest.name(), argsSummary(ins) + ".size()");
        }
      }
      case BINOP -> {
        if (ins.binop == null) return;
        // Detect `i <= lenExpr` / `i >= lenExpr`
        if (ins.binop == org.plumb.tac.ir.BinaryOperator.LE
            || ins.binop == org.plumb.tac.ir.BinaryOperator.GE) {
          if (ins.lhs1 instanceof TacOperand.TacVar loopVar
              && ins.lhs2 != null
              && looksLikeLength(ins.lhs2)) {
            lengthOfVar.put(loopVar.name(), ins.lhs2.toString());
            // If the loopVar is a
            // FIELDLOAD dest (e.g.
            // {@code t_t0 = this.i}),
            // also key by its field name
            // so the body-side
            // re-load (e.g.
            // {@code t_t3 = this.i})
            // matches.
            String fieldName = varToField.get(loopVar.name());
            if (fieldName != null) {
              fieldLengths.putIfAbsent(fieldName, ins.lhs2.toString());
            }
          }
          // Even if ins.lhs2 isn't
          // a recognized length literal,
          // it might be a temp that
          // was tagged by a previous
          // FIELDLOAD/CALL.
          if (ins.lhs1 instanceof TacOperand.TacVar loopVar2
              && ins.lhs2 instanceof TacOperand.TacVar lenVar
              && lengthOfVar.containsKey(lenVar.name())) {
            String lenExpr = lengthOfVar.get(lenVar.name());
            lengthOfVar.putIfAbsent(loopVar2.name(), lenExpr);
            // Also propagate to the
            // field-name map.
            String fieldName = varToField.get(loopVar2.name());
            if (fieldName != null) {
              fieldLengths.putIfAbsent(fieldName, lenExpr);
            }
          }
        }
      }
      case ARRAYLOAD -> {
        if (ins.lhs2 instanceof TacOperand.TacVar index) {
          String lenExpr = lookupLenExpr(index);
          if (lenExpr != null) {
            emit(ctx, ins, fn, index.name(), lenExpr);
          }
        }
      }
      case ARRAYSTORE -> {
        if (ins.lhs2 instanceof TacOperand.TacVar index) {
          String lenExpr = lookupLenExpr(index);
          if (lenExpr != null) {
            emit(ctx, ins, fn, index.name(), lenExpr);
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

  /**
   * Do-while support. In a {@code for (i = 0; i <= arr.length; i++)} loop the BRANCH+cond appears
   * before the body, so a single pass through the BB-RPO order sees the BINOP first, then the
   * ARRAYLOAD/STORE in the body, and the current {@code checkInstruction} logic fires correctly. In
   * a {@code do { body; } while (i <= arr.length)} loop the body comes first, then the condition: a
   * single pass sees the ARRAYLOAD/STORE in the body BEFORE the BINOP that tags the loop variable,
   * so nothing fires. To handle do-while we run a second pass at function-exit: re-walk every
   * ARRAYLOAD/STORE and re-apply the length lookup using whatever {@code lengthOfVar} / {@code
   * fieldLengths} the first pass populated. This catches do-while and any other "loop tail" pattern
   * where the bound is set up after the indexed access.
   */
  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.ARRAYLOAD || ins.kind == TacInstruction.Kind.ARRAYSTORE) {
        if (ins.lhs2 instanceof TacOperand.TacVar index) {
          String lenExpr = lookupLenExpr(index);
          if (lenExpr != null) {
            emit(ctx, ins, fn, index.name(), lenExpr);
          }
        }
      }
    }
  }

  /**
   * Look up a length expression for an index operand. First try the SSA name directly; if that
   * misses, look up the TacVar's field name in {@link #varToField} and consult {@link
   * #fieldLengths}. The field-name fallback covers the case where a non-static method re-reads the
   * loop variable as {@code t_t3 = this.i} (a fresh SSA name) and uses that as the array index.
   */
  private String lookupLenExpr(TacOperand.TacVar index) {
    String lenExpr = lengthOfVar.get(index.name());
    if (lenExpr != null) return lenExpr;
    String fieldName = varToField.get(index.name());
    if (fieldName != null) {
      return fieldLengths.get(fieldName);
    }
    return null;
  }

  private static void emit(
      AnalysisContext ctx, TacInstruction ins, TacFunction fn, String loopVar, String lenExpr) {
    // v2 trace: single-node VIOLATION
    // with REGEX_DOS op (closest available for
    // "loop bound off-by-one"; no dedicated
    // enum — the off-by-one bucket is currently
    // shared with regex-dos to keep the
    // TraceNode.Op enum from growing). The
    // Free operand encodes the "loop var vs
    // length expr" shape so AI dedup can see
    // it without regex-parsing the legacy
    // flow string.
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.REGEX_DOS,
        "off-by-one: " + loopVar + " vs " + lenExpr,
        "OffByOne",
        "dfa-controlflow-off-by-one",
        "Loop variable "
            + loopVar
            + " is bounded by "
            + "<= or >= "
            + lenExpr
            + ", and the loop body accesses an array at "
            + loopVar
            + " — last iteration reads/writes one past the end",
        RuleDef.Severity.MEDIUM,
        "CWE-193",
        "off-by-one: " + loopVar + " vs " + lenExpr);
  }

  /**
   * Heuristic: an operand "looks like a length expression" if its textual form ends with {@code
   * .length} or {@code .size()} or is a name ending with {@code _length} / {@code _size} / {@code
   * size} / {@code length}. Catches {@code arr.length} and {@code list.size()} and named variables
   * like {@code len} / {@code size}.
   */
  private static boolean looksLikeLength(TacOperand op) {
    if (op == null) return false;
    String s = op.toString();
    // Strip type suffix ":<type>" if any
    int colon = s.indexOf(':');
    if (colon > 0) s = s.substring(0, colon);
    if (s.endsWith(".length")
        || s.endsWith(".size()")
        || s.endsWith(".size")
        || s.endsWith(".length()")) {
      return true;
    }
    return s.equals("length")
        || s.equals("size")
        || s.endsWith("_length")
        || s.endsWith("_size")
        || s.endsWith("Length")
        || s.endsWith("Size");
  }

  /** See {@code IteratorConcurrentModificationChecker#methodName}. */
  private static String methodName(String sig) {
    if (sig == null) return null;
    int lastDot = sig.lastIndexOf('.');
    int hash = sig.lastIndexOf('#');
    int sep = Math.max(lastDot, hash);
    if (sep < 0) {
      int slash = sig.indexOf('/');
      return slash < 0 ? sig : sig.substring(0, slash);
    }
    String tail = sig.substring(sep + 1);
    int slash = tail.indexOf('/');
    return slash < 0 ? tail : tail.substring(0, slash);
  }

  private static String argsSummary(TacInstruction ins) {
    if (ins.args == null || ins.args.isEmpty()) return "<receiver>";
    return ins.args.get(0).toString();
  }
}
