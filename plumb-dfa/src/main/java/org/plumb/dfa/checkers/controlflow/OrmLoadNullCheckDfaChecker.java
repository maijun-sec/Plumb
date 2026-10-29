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
 * DFA-based version of the ORM_LOAD_NULL_CHECK defect class (CWE-252). Where the AST checker {@code
 * org.plumb.style.quality.OrmLoadNullCheckCheck} only looks at the statement immediately after the
 * load call, the DFA version tracks the result of a load call and checks every later reference in
 * the same function.
 *
 * <p><b>What the DFA version catches that AST misses.</b>
 *
 * <ul>
 *   <li>The null check is several statements after the load (with assignments in between). The AST
 *       version requires the null check on the next statement; the DFA version handles arbitrary
 *       intervening code.
 *   <li>The load result is aliased through one or more variables before the null check. The AST
 *       version tracks the original variable name; the DFA version follows the alias chain via
 *       {@code MOVE}.
 * </ul>
 *
 * <p><b>Detection strategy.</b>
 *
 * <ol>
 *   <li>On a {@code CALL} to {@code load} / {@code getReference} / {@code getOne} / {@code
 *       loadById}, mark the dest {@link TacOperand.TacVar} as "from-load".
 *   <li>Propagate "from-load" through {@code MOVE} (aliasing).
 *   <li>At {@code BRANCH} (if-statement), inspect the condition: if the condition is a {@code ==
 *       null} or {@code != null} comparison against any "from-load" var, fire on the load call
 *       site.
 * </ol>
 */
public final class OrmLoadNullCheckDfaChecker implements DfaChecker {

  private static final Set<String> LOAD_METHODS =
      Set.of("load", "getReference", "loadById", "getOne");

  /** Per-function: var name → "from-load" flag. */
  private Map<String, Boolean> fromLoadVars;

  /** Per-function: var name → the load call's instruction. */
  private Map<String, TacInstruction> loadCallSite;

  /**
   * v2 trace state. Per-function map from "from-load" var name to its v2 handle -- the load call's
   * {@code CALL_INSTANCE} source node, propagated through alias MOVEs. Lets the final {@code
   * LOAD_NOT_NULL} violation fire wire its parent edge back to the load call site.
   */
  private Map<String, Integer> v2HandleByVar;

  private TraceBuilder v2;
  private final java.util.List<Integer> sinkHandles = new java.util.ArrayList<>();

  @Override
  public String name() {
    return "orm-load-null-check-dfa";
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
        "org.plumb.dfa.checkers.controlflow.OrmLoadNullCheckDfaChecker",
        "DFA-based ORM_LOAD_NULL_CHECK");
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    fromLoadVars = new HashMap<>();
    loadCallSite = new HashMap<>();
    v2HandleByVar = new HashMap<>();
    v2 = new TraceBuilder();
    sinkHandles.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind == TacInstruction.Kind.CALL
        && ins.calleeSig != null
        && isLoadCall(ins.calleeSig)
        && ins.dest != null
        && ins.dest instanceof TacOperand.TacVar) {
      String dest = ((TacOperand.TacVar) ins.dest).name();
      fromLoadVars.put(dest, true);
      loadCallSite.put(dest, ins);
      // emit CALL_INSTANCE source for
      // the load call. The dest var's v2
      // handle points back to this node.
      int handle =
          v2.addSource(
              TraceNode.Op.CALL_INSTANCE,
              List.of(new OperandRef.Var(dest, 0), new OperandRef.CallRef(ins.calleeSig)),
              "JPA load: " + ins.calleeSig,
              "",
              ins.line,
              ins.column,
              fn.signature());
      v2HandleByVar.put(dest, handle);
      return;
    }
    // Aliasing.
    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.lhs1 != null
        && ins.lhs1 instanceof TacOperand.TacVar
        && ins.dest != null
        && ins.dest instanceof TacOperand.TacVar) {
      String from = ((TacOperand.TacVar) ins.lhs1).name();
      String to = ((TacOperand.TacVar) ins.dest).name();
      if (fromLoadVars.containsKey(from)) {
        fromLoadVars.put(to, true);
        loadCallSite.put(to, loadCallSite.get(from));
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
    // For each "from-load" var, find every BRANCH
    // instruction that null-compares against it.
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.BRANCH) continue;
      if (ins.lhs1 == null || ins.lhs2 == null) continue;
      if (ins.binop == null) continue;
      if (ins.binop != org.plumb.tac.ir.BinaryOperator.EQ
          && ins.binop != org.plumb.tac.ir.BinaryOperator.NE) continue;
      String l = ins.lhs1.toString();
      String r = ins.lhs2.toString();
      // var == null  /  null == var
      // Match the variable side against fromLoadVars
      // and the other side is a null literal.
      // (We use the AST text because the operand
      // is a NameExpr and our "from-load" key is
      // the var name.)
      String lBare = bareName(l);
      String rBare = bareName(r);
      String varSide = null;
      String otherBare = null;
      if (fromLoadVars.containsKey(lBare)) {
        varSide = lBare;
        otherBare = rBare;
      } else if (fromLoadVars.containsKey(rBare)) {
        varSide = rBare;
        otherBare = lBare;
      }
      boolean isNullCompare = varSide != null && "null".equals(otherBare);
      if (!isNullCompare) continue;
      TacInstruction origin = loadCallSite.get(varSide);
      if (origin == null) continue;
      // emit LOAD_NOT_NULL violation
      // with the null-check's BRANCH as the
      // line (semantically the dead check
      // site) and the load call as the
      // v2 source parent.
      Integer parent = v2HandleByVar.get(varSide);
      int handle =
          v2.addViolation(
              TraceNode.Op.LOAD_NOT_NULL,
              List.of(new OperandRef.Var(varSide, 0)),
              parent == null ? java.util.List.of() : java.util.List.of(parent),
              "null check on line "
                  + ins.line
                  + " is dead code: "
                  + varSide
                  + " never null after JPA load",
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
              "ORM_LOAD_NULL_CHECK (DFA): variable '"
                  + varSide
                  + "' is the result of a JPA load method"
                  + " (load / getReference / getOne) which never"
                  + " returns null — the null check on line "
                  + ins.line
                  + " is dead code",
              RuleDef.Severity.LOW,
              "CWE-252",
              "",
              origin.line,
              origin.column,
              varSide + " in " + fn.signature(),
              trace);
      ctx.reporter().reportWithTrace(f, trace);
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static boolean isLoadCall(String calleeSig) {
    int hash = calleeSig.indexOf('#');
    if (hash < 0) return false;
    String after = calleeSig.substring(hash + 1);
    int paren = after.indexOf('(');
    String name = paren < 0 ? after : after.substring(0, paren);
    return LOAD_METHODS.contains(name);
  }

  /** Strip "name:type" or "type#name" to bare variable name. */
  private static String bareName(String operandText) {
    if (operandText == null) return "";
    int colon = operandText.indexOf(':');
    if (colon >= 0) return operandText.substring(0, colon);
    int hash = operandText.indexOf('#');
    if (hash >= 0) return operandText.substring(0, hash);
    return operandText;
  }
}
