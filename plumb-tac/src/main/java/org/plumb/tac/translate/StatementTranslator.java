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
package org.plumb.tac.translate;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.UnparsableStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaParser-statement → TAC translator. Each call mutates the supplied {@link TacBuilder}; loops
 * and conditionals cooperate via shared break / continue label stacks carried on the builder-local
 * state.
 */
public final class StatementTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(StatementTranslator.class);

  private final TacTranslator owner;

  /**
   * The breakable statements currently open, innermost LAST.
   *
   * <p>Java's rule for an unlabelled {@code break} is "the innermost enclosing switch, while, do or
   * for statement" -- and the innermost is decided by NESTING, not by kind. A {@code break} inside
   * a loop that is itself inside a switch case leaves the LOOP; a {@code break} directly in a
   * switch case inside a loop leaves the SWITCH. A single "the switch wins" rule gets the first
   * case wrong, and the wrong target means the jump skipped the wrong statements.
   *
   * <p>So the targets live on a STACK, and {@code break} takes the top of it.
   *
   * <p>The switch entry is also what makes {@code break;} inside a case body jump past the switch.
   * Switch statements are lowered to an if/else-if chain (see {@link #translateSwitch}), and {@code
   * break} inside a case body must emit a {@code JUMP} to the switch-end label, otherwise the body
   * of subsequent cases is unreachable in TAC and downstream analyzers miss findings whose source
   * is on a path past the first matching case (e.g. Juliet CWE191 Variant 15: {@code
   * switch(6){...}} then {@code switch(7){...}}).
   */
  private final ThreadLocal<Deque<BreakTarget>> breakTargets =
      ThreadLocal.withInitial(ArrayDeque::new);

  /**
   * Break / continue targets registered for LABELED statements.
   *
   * <p>Java lets a {@code break} or {@code continue} name a label instead of the innermost
   * enclosing loop: {@code break outer;} leaves the loop labelled {@code outer}, not the one
   * written immediately around the {@code break}. Without this table a labelled break jumped to the
   * INNERMOST loop's end -- the wrong loop, so the jump skipped the wrong statements and the
   * remainder of the outer loop still ran.
   *
   * <p>The value is the target pair: the label to jump to on {@code break}, and the label to jump
   * to on {@code continue}. A label on a statement that is not a loop has no continue target,
   * because Java forbids {@code continue label;} unless the label names a loop.
   */
  private final ThreadLocal<Map<String, BreakTarget>> labeledTargets =
      ThreadLocal.withInitial(LinkedHashMap::new);

  /**
   * The label of the statement currently being translated, when it was introduced by a {@code
   * LabeledStmt}.
   *
   * <p>The loop translators consume it to register their own targets under that label. It is
   * cleared as soon as a loop takes it, so a nested loop does not steal the outer loop's label.
   */
  private final ThreadLocal<String> pendingLabel = new ThreadLocal<>();

  StatementTranslator(TacTranslator owner) {
    this.owner = owner;
  }

  public void translateBlock(BlockStmt block, TacBuilder b) {
    for (Statement s : block.getStatements()) {
      translate(s, b);
    }
  }

  public void translate(Statement s, TacBuilder b) {
    if (s == null) return;
    if (s instanceof BlockStmt bs) {
      translateBlock(bs, b);
      return;
    }
    if (s instanceof ExpressionStmt es) {
      translateExprStmt(es, b);
      return;
    }
    if (s instanceof com.github.javaparser.ast.stmt.LocalClassDeclarationStmt lcds) {
      // A LOCAL class: a class declared inside a method body.
      //
      // Its methods are real code and can hold a vulnerability, so they are lowered
      // into their own functions. The class is named after the enclosing method, which
      // is how the JVM names a local class (Outer$1Local) and how the translator names
      // nested types, so the two do not collide.
      owner.translateLocalType(lcds.getClassDeclaration(), b);
      return;
    }
    if (s instanceof com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt lrds) {
      // A LOCAL record: the record analogue of a local class.
      owner.translateLocalType(lrds.getRecordDeclaration(), b);
      return;
    }
    if (s instanceof IfStmt is) {
      translateIf(is, b);
      return;
    }
    if (s instanceof WhileStmt ws) {
      translateWhile(ws, b);
      return;
    }
    if (s instanceof DoStmt ds) {
      translateDo(ds, b);
      return;
    }
    if (s instanceof ForStmt fs) {
      translateFor(fs, b);
      return;
    }
    if (s instanceof ForEachStmt fes) {
      translateForEach(fes, b);
      return;
    }
    if (s instanceof ReturnStmt rs) {
      translateReturn(rs, b);
      return;
    }
    if (s instanceof ThrowStmt ts) {
      translateThrow(ts, b);
      return;
    }
    if (s instanceof BreakStmt bs) {
      translateBreak(bs, b);
      return;
    }
    if (s instanceof ContinueStmt cs) {
      translateContinue(cs, b);
      return;
    }
    if (s instanceof TryStmt ts) {
      translateTry(ts, b);
      return;
    }
    if (s instanceof SwitchStmt ss) {
      translateSwitch(ss, b);
      return;
    }
    if (s instanceof LabeledStmt ls) {
      translateLabeled(ls, b);
      return;
    }
    if (s instanceof SynchronizedStmt syn) {
      translateSynchronized(syn, b);
      return;
    }
    if (s instanceof AssertStmt as) {
      translateAssert(as, b);
      return;
    }
    if (s instanceof EmptyStmt) {
      /* noop */
      return;
    }
    if (s instanceof YieldStmt ys) {
      // A `yield v;` hands a value to the enclosing switch EXPRESSION.
      //
      // The switch expression's lowering allocates a temp and publishes it as the yield
      // target for the duration of its arms. So the yield is a MOVE of the yielded value
      // into that temp -- which means the value's taint reaches the switch expression's
      // result, and every taint checker downstream sees it.
      //
      // Before this was wired, the yield's value was evaluated and discarded, so a switch
      // expression like `int n = switch (x) { case 1 -> tainted(); default -> 0; };` lost
      // the taint entirely: the finding was simply missing. That is the worst kind of bug
      // for a taint engine -- a silent false negative.
      ExpressionResult yielded = owner.exprs().translate(ys.getExpression(), b);
      TacOperand.TacVar target = owner.exprs().yieldTarget();
      if (target != null) {
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(target)
                .lhs1(yielded.operand())
                .line(TacTranslator.nodeLine(ys))
                .build());
        // The yield LEAVES the switch expression. Without the jump the
        // execution fell into the next arm, so every arm ran and the
        // expression's value was whichever arm wrote last.
        String endLabel = owner.exprs().yieldEndLabel();
        if (endLabel != null) {
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.JUMP)
                  .label(endLabel)
                  .line(TacTranslator.nodeLine(ys))
                  .build());
        }
      } else {
        // No switch expression in scope. Java rejects this, so the only way to reach it is
        // a `yield` inside a switch STATEMENT -- where it is also a compile error. The
        // value is still evaluated (so its side effects are preserved), but there is
        // nowhere to put it.
        LOG.debug("yield outside a switch expression: value evaluated but not assigned");
      }
      return;
    }
    if (s instanceof ExplicitConstructorInvocationStmt ecis) {
      // super(...) / this(...) — emit a CALL with the synthetic name.
      List<TacOperand> args = new ArrayList<>();
      for (Expression a : ecis.getArguments()) {
        args.add(owner.exprs().translate(a, b).operand());
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.CALL)
              .calleeSig((ecis.isThis() ? "this" : "super") + ".<init>/" + args.size())
              .args(args)
              .line(TacTranslator.nodeLine(ecis))
              .build());
      return;
    }
    if (s instanceof UnparsableStmt us) {
      // A statement JavaParser could not parse.
      //
      // WHY THIS IS ITS OWN CASE. An unparsable statement means the SOURCE is
      // broken -- the parser recovered and produced a placeholder for code it
      // could not read. That is a different fact from "the frontend does not
      // support this construct": the first is a defect in the input, the second
      // is a limitation of the tool. Conflating them would let a consumer tell
      // "this file has broken code" from "this file uses a construct I do not
      // handle" -- and those need different responses.
      //
      // It is reported as a PARSE_PROBLEM rather than an UNHANDLED_NODE, and it
      // is reported on the FUNCTION, so the problem channel says "this function's
      // source is incomplete" instead of implying the translator tried and gave up.
      b.reportParseProblem(
          "UnparsableStmt: the parser could not read this statement",
          TacTranslator.nodeLine(us),
          TacTranslator.nodeColumn(us));
      return;
    }
    // Unhandled: report the gap.
    //
    // A statement we cannot model is worse than an expression we cannot model: the statement's
    // effect on the data flow is simply absent, so a tainted value assigned inside it is lost
    // with no trace. Recording it on the IR lets the caller see the analysis is incomplete.
    String nodeClass = s.getClass().getSimpleName();
    b.reportUnhandled(nodeClass, TacTranslator.nodeLine(s), TacTranslator.nodeColumn(s));
    LOG.debug("Unhandled statement: {} at line {}", nodeClass, TacTranslator.nodeLine(s));
  }

  // ------------------------------------------------------------------

  private void translateExprStmt(ExpressionStmt es, TacBuilder b) {
    Expression e = es.getExpression();
    if (e instanceof VariableDeclarationExpr vde) {
      translateVarDecl(vde, b);
      return;
    }
    owner.exprs().translate(e, b);
  }

  /**
   * Lower a variable declaration into locals plus the assignments that initialise them.
   *
   * <p>Package-visible because a {@code VariableDeclarationExpr} is an EXPRESSION in the AST, and
   * it can appear where an expression is expected -- not only as a statement. The expression path
   * in {@link ExpressionTranslator} routes here so that a declaration reached through an expression
   * is lowered exactly like one reached through a statement, instead of silently becoming null.
   */
  void translateVarDecl(VariableDeclarationExpr vde, TacBuilder b) {
    for (var declarator : vde.getVariables()) {
      // The declared type is the whole type, not its element type. `getElementType()` returns
      // `int` for `int[] array`, which made every array local look like a scalar and hid the
      // fact that the local holds an array -- so `array.length` was not recognisable as a bound.
      TacType t = TypeMapper.map(declarator.getType());
      TacOperand.TacVar v = new TacOperand.TacVar(declarator.getNameAsString(), t, false);
      b.locals().add(v);
      if (declarator.getInitializer().isPresent()) {
        Expression initializer = declarator.getInitializer().get();
        // When the initializer is a FieldAccessExpr,
        // emit the FIELDLOAD with the local as dest
        // directly (not a fresh temp + MOVE). This makes
        // the local the canonical "holder" of the field
        // read, and the per-function field-expr cache
        // links the FieldAccessExpr's text to this local
        // so a later chained write
        // (e.g. {@code o.a.b = X;}) can reuse the same
        // local as its FIELDSTORE's lhs1, aligning the
        // chain walk's field-slot lookups
        // ({@code fieldMap[local][b]}) with where the
        // write actually lands. Without this, the chain
        // walk would query {@code fieldMap[local][b]} but
        // the write would have gone to a fresh temp's
        // slot — the taint would be invisible.
        if (initializer instanceof com.github.javaparser.ast.expr.FieldAccessExpr faeInit) {
          // A scope that names a TYPE is a STATIC field read: `System.out`,
          // `Integer.MAX_VALUE`. Reading it as an instance field produced a
          // ghost FIELDLOAD off `this` -- `this.System.out` -- and the static
          // field's owner was nowhere in the TAC.
          String scopeFqn = owner.exprs().typeScopeFqn(faeInit.getScope(), b);
          if (scopeFqn != null) {
            TacOperand receiver = owner.exprs().classIdentityReceiver(scopeFqn, b);
            if (receiver == null) {
              receiver = TacOperand.Const.ofString(scopeFqn);
            }
            b.emit(
                TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
                    .dest(v)
                    .lhs1(receiver)
                    .fieldName(faeInit.getNameAsString())
                    .ownerType(scopeFqn)
                    .line(TacTranslator.nodeLine(declarator))
                    .build());
          } else {
            ExpressionResult scope = owner.exprs().translate(faeInit.getScope(), b);
            b.emit(
                TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
                    .dest(v)
                    .lhs1(scope.operand())
                    .fieldName(faeInit.getNameAsString())
                    .ownerType(owner.exprs().receiverTypeName(faeInit.getScope(), scope))
                    .line(TacTranslator.nodeLine(declarator))
                    .build());
          }
          // Cache the mapping so a later chained
          // write can find this local as the
          // FIELDSTORE's lhs1.
          b.fieldExprCache().put(faeInit.toString(), v);
        } else {
          ExpressionResult init = owner.exprs().translate(initializer, b);
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.MOVE)
                  .dest(v)
                  .lhs1(init.operand())
                  .line(TacTranslator.nodeLine(declarator))
                  .build());
          // If the initializer carries a lambda binding,
          // register it on the program so the taint
          // engine can find the lambda body when the user
          // later invokes {@code v.apply(...)}.
          if (init.lambdaSignature() != null) {
            owner.bindLambda(v.name(), init.lambdaSignature());
          }
          // A method reference is the same kind of deferred
          // target, except that the target is an existing method
          // rather than a synthetic lambda body. The binding lets
          // the application site be rewritten into the call it
          // actually is.
          if (init.deferredTarget() != null) {
            owner.bindDeferredTarget(v.name(), init.deferredTarget());
          }
        }
      }
    }
  }

  /**
   * Open a breakable statement.
   *
   * <p>If the statement is DIRECTLY labelled, its target is registered under that label so a {@code
   * break L;} / {@code continue L;} can find it. The pending label is consumed here: a loop nested
   * inside the labelled loop must not register itself under the same name.
   *
   * @param loop true for a loop, false for a switch
   * @param breakLabel the label a {@code break} jumps to
   * @param continueLabel the label a {@code continue} jumps to, or null for a switch (Java never
   *     lets a {@code continue} target a switch)
   * @return the target that was in scope before, to be passed back to {@link #restoreTarget}
   */
  private BreakTarget pushTarget(boolean loop, String breakLabel, String continueLabel) {
    Deque<BreakTarget> stack = breakTargets.get();
    BreakTarget previous = stack.peek();
    BreakTarget target = new BreakTarget(loop, breakLabel, continueLabel);
    stack.push(target);
    String label = pendingLabel.get();
    if (label != null) {
      pendingLabel.remove();
      labeledTargets.get().put(label, target);
    }
    return previous;
  }

  /** Close the innermost breakable statement and restore the previous target. */
  private void restoreTarget(BreakTarget previous) {
    Deque<BreakTarget> stack = breakTargets.get();
    if (!stack.isEmpty()) stack.pop();
    if (previous != null) stack.push(previous);
  }

  private void translateIf(IfStmt is, TacBuilder b) {
    ExpressionResult cond = owner.exprs().translate(is.getCondition(), b);
    String lThen = b.newLabel("L_THEN");
    String lElse = b.newLabel("L_ELSE");
    String lEnd = b.newLabel("L_END_IF");
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond.operand())
            .trueLabel(lThen)
            .falseLabel(lElse)
            .line(TacTranslator.nodeLine(is))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lThen).build());
    translate(is.getThenStmt(), b);
    b.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(lEnd).build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lElse).build());
    is.getElseStmt().ifPresent(es -> translate(es, b));
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
  }

  private void translateWhile(WhileStmt ws, TacBuilder b) {
    String lCond = b.newLabel("L_WHILE_COND");
    String lBody = b.newLabel("L_WHILE_BODY");
    String lEnd = b.newLabel("L_WHILE_END");
    BreakTarget outer = pushTarget(true, lEnd, lCond);
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lCond).build());
    ExpressionResult cond = owner.exprs().translate(ws.getCondition(), b);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond.operand())
            .trueLabel(lBody)
            .falseLabel(lEnd)
            .line(TacTranslator.nodeLine(ws))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lBody).build());
    translate(ws.getBody(), b);
    b.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(lCond).build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    restoreTarget(outer);
  }

  private void translateDo(DoStmt ds, TacBuilder b) {
    String lBody = b.newLabel("L_DO_BODY");
    String lEnd = b.newLabel("L_DO_END");
    BreakTarget outer = pushTarget(true, lEnd, lBody);
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lBody).build());
    translate(ds.getBody(), b);
    ExpressionResult cond = owner.exprs().translate(ds.getCondition(), b);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(cond.operand())
            .trueLabel(lBody)
            .falseLabel(lEnd)
            .line(TacTranslator.nodeLine(ds))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    restoreTarget(outer);
  }

  private void translateFor(ForStmt fs, TacBuilder b) {
    for (Expression init : fs.getInitialization()) {
      owner.exprs().translate(init, b);
    }
    String lCond = b.newLabel("L_FOR_COND");
    String lBody = b.newLabel("L_FOR_BODY");
    String lUpdate = b.newLabel("L_FOR_UPDATE");
    String lEnd = b.newLabel("L_FOR_END");
    BreakTarget outer = pushTarget(true, lEnd, lUpdate);
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lCond).build());
    if (fs.getCompare().isPresent()) {
      ExpressionResult cond = owner.exprs().translate(fs.getCompare().get(), b);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.BRANCH)
              .lhs1(cond.operand())
              .trueLabel(lBody)
              .falseLabel(lEnd)
              .line(TacTranslator.nodeLine(fs))
              .build());
    } else {
      // missing condition => infinite loop unless broken out
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.JUMP)
              .label(lBody)
              .line(TacTranslator.nodeLine(fs))
              .build());
    }
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lBody).build());
    translate(fs.getBody(), b);
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lUpdate).build());
    for (Expression u : fs.getUpdate()) {
      owner.exprs().translate(u, b);
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(lCond)
            .line(TacTranslator.nodeLine(fs))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    restoreTarget(outer);
  }

  private void translateForEach(ForEachStmt fes, TacBuilder b) {
    // Expand to:
    //   Iterable it = expr.iterator();
    //   while (it.hasNext()) { T v = (T) it.next(); body }
    // We emit a comment-like sequence using a synthetic helper call signature.
    ExpressionResult src = owner.exprs().translate(fes.getIterable(), b);
    TacOperand.TacVar it = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(it)
            .calleeSig("iterator/0")
            .args(new ArrayList<>(List.of(src.operand())))
            .line(TacTranslator.nodeLine(fes))
            .build());

    String lCond = b.newLabel("L_FORE_COND");
    String lBody = b.newLabel("L_FORE_BODY");
    String lEnd = b.newLabel("L_FORE_END");
    BreakTarget outer = pushTarget(true, lEnd, lCond);
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lCond).build());
    TacOperand.TacVar hasNext = b.newTemp(TacType.BOOLEAN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(hasNext)
            .calleeSig("hasNext/0")
            .args(new ArrayList<>(List.of((TacOperand) it)))
            .line(TacTranslator.nodeLine(fes))
            .build());
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BRANCH)
            .lhs1(hasNext)
            .trueLabel(lBody)
            .falseLabel(lEnd)
            .line(TacTranslator.nodeLine(fes))
            .build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lBody).build());
    TacOperand.TacVar next = b.newTemp(TacType.UNKNOWN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(next)
            .calleeSig("next/0")
            .args(new ArrayList<>(List.of((TacOperand) it)))
            .line(TacTranslator.nodeLine(fes))
            .build());
    // Declare the loop variable
    VariableDeclarationExpr vde = fes.getVariable();
    if (vde.getVariables().size() != 1) {
      LOG.warn("for-each with multi-variable decl skipped");
    } else {
      var decl = vde.getVariables().get(0);
      // The whole declared type, not its element type -- see translateVarDecl.
      TacType vt = TypeMapper.map(decl.getType());
      TacOperand.TacVar loopVar = new TacOperand.TacVar(decl.getNameAsString(), vt, false);
      b.locals().add(loopVar);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(loopVar)
              .lhs1(next)
              .line(TacTranslator.nodeLine(decl))
              .build());
    }
    translate(fes.getBody(), b);
    b.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(lCond).build());
    b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    restoreTarget(outer);
  }

  private void translateReturn(ReturnStmt rs, TacBuilder b) {
    TacOperand value = null;
    if (rs.getExpression().isPresent()) {
      value = owner.exprs().translate(rs.getExpression().get(), b).operand();
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.RETURN)
            .lhs1(value)
            .line(TacTranslator.nodeLine(rs))
            .build());
  }

  private void translateThrow(ThrowStmt ts, TacBuilder b) {
    TacOperand value = owner.exprs().translate(ts.getExpression(), b).operand();
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.THROW)
            .lhs1(value)
            .ctorType(thrownClassType(ts.getExpression(), value))
            .line(TacTranslator.nodeLine(ts))
            .build());
  }

  /**
   * The type of the value a {@code throw} raises.
   *
   * <p><b>Why the type matters.</b> A catch handler matches on the type of the exception, so a
   * {@code THROW} that does not say what it threw cannot say which handler the exception reaches.
   * The CFG builder reads this type to decide the exception-flow edges (see {@link
   * org.plumb.tac.ir.TacCfgBuilder}), and the engine reads the same edges to decide which catch
   * parameter inherits the thrown value's taint.
   *
   * <p><b>Why the solver is asked first.</b> The AST only says {@code IOException}; the solver says
   * {@code java.io.IOException}, and the qualified name is what the exception hierarchy is keyed
   * by. A class declared in the scanned source is not on the solver's classpath, so the solver
   * throws for it -- and then the fallback is the AST's own type, which the hierarchy does not
   * know, so the exception stays untyped and every handler stays reachable.
   */
  private TacType thrownClassType(Expression thrown, TacOperand value) {
    TacType resolved = resolvedClassType(() -> thrown.calculateResolvedType());
    if (resolved != null) {
      return resolved;
    }
    if (value instanceof TacOperand.TacVar var && var.type().kind() == TacType.Kind.CLASS) {
      return var.type();
    }
    return null;
  }

  /**
   * The declared type of a catch clause, resolved.
   *
   * <p>Same reasoning as {@link #thrownClassType}: the handler's declared type is what the
   * exception hierarchy is matched against, and the qualified name is the key.
   */
  private TacType catchClassType(com.github.javaparser.ast.type.Type declared) {
    TacType resolved = resolvedClassType(() -> declared.resolve());
    if (resolved != null) {
      return resolved;
    }
    TacType mapped = TypeMapper.map(declared);
    return mapped != null && mapped.kind() == TacType.Kind.CLASS ? mapped : null;
  }

  /**
   * The resolved type of an expression, when the solver knows it and it names a class.
   *
   * <p>The solver throws for anything it cannot see -- a class declared in the scanned source, a
   * construct it cannot type -- and asking again would not change the answer, so the failure is the
   * answer: no type.
   */
  private static TacType resolvedClassType(
      java.util.function.Supplier<com.github.javaparser.resolution.types.ResolvedType> resolve) {
    try {
      TacType mapped = TypeMapper.mapResolved(resolve.get());
      if (mapped != null && mapped.kind() == TacType.Kind.CLASS) {
        return mapped;
      }
    } catch (RuntimeException | StackOverflowError ignore) {
      // The solver does not know this type.
    }
    return null;
  }

  private void translateBreak(BreakStmt bs, TacBuilder b) {
    int line = TacTranslator.nodeLine(bs);
    int column = TacTranslator.nodeColumn(bs);

    // A labelled break names its target explicitly. It is checked FIRST,
    // because a labelled break inside a switch must leave the labelled
    // loop, not the switch.
    if (bs.getLabel().isPresent()) {
      String label = bs.getLabel().get().getIdentifier();
      BreakTarget target = labeledTargets.get().get(label);
      if (target == null || target.breakLabel == null) {
        b.reportDrop(
            "java.break.unresolved_label",
            "no enclosing statement labelled " + label,
            TacDrop.Impact.SEMANTICS,
            line,
            column);
        return;
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.JUMP)
              .label(target.breakLabel)
              .line(line)
              .build());
      return;
    }

    // An unlabelled break leaves the INNERMOST enclosing breakable statement
    // -- a loop or a switch, whichever encloses it more closely. The switch
    // is not a special case: a `break` in a case body leaves the switch, and
    // a `break` in a loop inside a case body leaves the LOOP.
    BreakTarget ctx = breakTargets.get().peek();
    if (ctx == null || ctx.breakLabel == null) {
      // A break with no target is not valid Java, but the translator must not
      // drop it silently: the jump is simply not emitted, so the statements
      // that should have been skipped keep running.
      b.reportDrop(
          "java.break.no_target",
          "break outside any loop or switch",
          TacDrop.Impact.SEMANTICS,
          line,
          column);
      return;
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP).label(ctx.breakLabel).line(line).build());
  }

  private void translateContinue(ContinueStmt cs, TacBuilder b) {
    int line = TacTranslator.nodeLine(cs);
    int column = TacTranslator.nodeColumn(cs);

    // A labelled continue names its target explicitly.
    if (cs.getLabel().isPresent()) {
      String label = cs.getLabel().get().getIdentifier();
      BreakTarget target = labeledTargets.get().get(label);
      if (target == null || target.continueLabel == null) {
        b.reportDrop(
            "java.continue.unresolved_label",
            "no enclosing loop labelled " + label,
            TacDrop.Impact.SEMANTICS,
            line,
            column);
        return;
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.JUMP)
              .label(target.continueLabel)
              .line(line)
              .build());
      return;
    }

    // A continue NEVER targets a switch: it always goes to the innermost
    // enclosing LOOP, skipping any switch in between.
    BreakTarget ctx = null;
    for (BreakTarget candidate : breakTargets.get()) {
      if (candidate.loop) {
        ctx = candidate;
        break;
      }
    }
    if (ctx == null || ctx.continueLabel == null) {
      // Same reasoning as the break: the jump is not emitted, so the
      // statements that should have been skipped keep running. Record it.
      b.reportDrop(
          "java.continue.no_target",
          "continue outside any loop",
          TacDrop.Impact.SEMANTICS,
          line,
          column);
      return;
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(ctx.continueLabel)
            .line(line)
            .build());
  }

  /**
   * Translate a try-with-resources' resource declarations.
   *
   * <p>Each resource is a variable declaration whose initializer runs before the body. The variable
   * is added to {@link TacBuilder#locals()} so the body's references resolve to it, and the
   * initializer is translated so the value -- and any taint on it -- reaches the variable.
   *
   * <p>A resource may also be an already-declared name ({@code try (existing)}), which has no
   * initializer. That form declares nothing; the name is already a local or a parameter, and
   * referencing it resolves on its own.
   *
   * @param ts the try statement
   * @param b the builder
   * @return the resource variables, in declaration order, so the implicit close can be emitted
   */
  private List<TacOperand.TacVar> translateResources(TryStmt ts, TacBuilder b) {
    List<TacOperand.TacVar> resources = new ArrayList<>();
    for (Expression resource : ts.getResources()) {
      if (!(resource instanceof VariableDeclarationExpr vde)) {
        // `try (existing)` -- a bare name. Nothing to declare.
        continue;
      }
      for (var declarator : vde.getVariables()) {
        // The whole declared type, not its element type -- see translateVarDecl.
        TacType t = TypeMapper.map(declarator.getType());
        TacOperand.TacVar v = new TacOperand.TacVar(declarator.getNameAsString(), t, false);
        b.locals().add(v);
        resources.add(v);
        if (!declarator.getInitializer().isPresent()) {
          continue;
        }
        ExpressionResult init = owner.exprs().translate(declarator.getInitializer().get(), b);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(v)
                .lhs1(init.operand())
                .line(TacTranslator.nodeLine(declarator))
                .build());
      }
    }
    return resources;
  }

  /**
   * Emit the implicit {@code close()} of a try-with-resources' resource.
   *
   * <p>Java's try-with-resources CLOSES the resource when the block ends -- on the normal path and
   * on the exceptional path alike. The TAC has no destructor concept, so the close is modelled as
   * the call it is: a {@code CALL} to {@code close} on the resource.
   *
   * <p>Emitting it is what keeps the resource-leak checker honest. The checker reports an
   * allocation unless it sees a close on that value, so a try-with-resources that was NOT closed
   * would be reported as a leak -- a false positive on code that closes the resource correctly. The
   * earlier fear was that a close call would "double-report" the genuinely leaked ones; the
   * opposite is true. The close call is what MARKS the resource closed, and only an allocation with
   * no close at all is a leak.
   *
   * <p>The close is emitted at the end of the try body, before the catch clauses, so it is
   * reachable on the normal path. The CFG's exception edges carry the exceptional path.
   *
   * @param resource the resource variable
   * @param b the builder
   * @param line the source line to attribute the close to
   */
  private void emitResourceClose(TacOperand.TacVar resource, TacBuilder b, int line) {
    TacOperand.TacVar dest = b.newTemp(TacType.VOID);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(dest)
            .calleeSig(resource.name() + ".close/0")
            .args(new ArrayList<>(List.of((TacOperand) resource)))
            .line(line)
            .build());
  }

  private void translateTry(TryStmt ts, TacBuilder b) {
    // Emit explicit exception-flow IR.
    //
    // The previous model was sequential: try-body, then
    // each catch body in source order. This was conservative
    // — any exception thrown inside try was treated as
    // flowing out of the whole try, never landing in a catch
    // block. The CFG had no try → catch edges, so taint, null
    // analysis, and resource tracking could not see that the
    // exception value (or an allocated resource) reached a
    // catch handler.
    //
    // The new model:
    //   1. Translate the try body. THROW / CALL instructions
    //      inside the body keep their semantics; the CFG
    //      builder is responsible for adding exception edges
    //      from any throwing instruction to every reachable
    //      {@link TacInstruction.Kind#CATCH_HANDLER} whose
    //      declared exception type is a supertype of the
    //      thrown type.
    //   2. For each catch clause, emit a
    //      {@code CATCH_HANDLER} instruction that
    //      binds the catch parameter to the exception value
    //      and is treated as a block entry marker by the CFG
    //      builder. The catch parameter is added to
    //      {@code b.locals()} so the catch body can
    //      reference it.
    //   3. Finally, if a finally block exists, emit a
    //      {@code FINALLY_ENTER} instruction so
    //      the CFG builder can route every exit of the
    //      try / catch region through the finally entry.
    //      The finally body is then translated normally.
    //
    // Compatibility: the LABEL name pattern
    // {@code L_CATCH_<Type>} is preserved as the
    // primary block-marker, so existing CFG builders and
    // checkers that key off label names keep working.
    // CATCH_HANDLER is added on top of LABEL for the
    // explicit exception flow.

    // Translate the try RESOURCES first. They are declarations, and their
    // initializers run BEFORE the try body -- that is the semantics of
    // try-with-resources: `try (R r = init)` evaluates `init`, binds `r`,
    // and only then enters the body.
    //
    // This was missing entirely. Without it the resource variable was never
    // added to b.locals(), so every reference to it inside the body fell
    // through translateName's `this.<name>` fallback and became a FIELDLOAD
    // off `this` with an UNKNOWN type. The initializer was never translated
    // either, so no taint could reach the resource at all: a
    // `try (ObjectInputStream ois = new ObjectInputStream(untrusted))` was
    // structurally incapable of producing a CWE-502 finding, even though the
    // same code outside a try-with-resources did produce one.
    //
    // The resource's `close()` IS modelled, as an ordinary CALL: see
    // emitResourceClose. It is what marks the resource closed for the
    // resource-leak checker.
    List<TacOperand.TacVar> resources = translateResources(ts, b);

    // Translate the try body first. THROW and CALL inside
    // the body can now reach the catch handler blocks that
    // we are about to emit.
    // Record the try range and (later) the catch /
    // finally ranges so the CFG builder can add exception-flow
    // edges from blocks inside the try range to the catch
    // handler blocks. Index inclusive on both ends.
    int tryStartIdx = b.instructions().size();
    translate(ts.getTryBlock(), b);
    int tryEndIdx = b.instructions().size() - 1;

    // The implicit close of each resource, on the normal path. It is emitted
    // AFTER the try range is recorded, so it is not part of the try region:
    // the close happens when the block is LEFT, not while it is running.
    for (TacOperand.TacVar resource : resources) {
      emitResourceClose(resource, b, TacTranslator.nodeLine(ts));
    }

    // Collect catch metadata as we lower each catch
    // clause so we can build the TryRegion at the end of
    // translateTry. Catch labels go into the list in source
    // order; the catch range covers from the first catch's
    // LABEL emission to the last catch body's last insn.
    List<String> catchLabels = new ArrayList<>();
    int firstCatchStart = -1;
    int lastCatchEnd = -1;
    for (CatchClause cc : ts.getCatchClauses()) {
      // Keep the {@code L_CATCH_<Type>} label name
      // pattern so existing checkers and labels-as-locations
      // keep working. CATCH_HANDLER is added on top of LABEL
      // for the explicit exception flow.
      String catchType = "";
      var p = cc.getParameter();
      TacType catchTacType = null;
      if (p != null) {
        TacType t = catchClassType(p.getType());
        if (t != null) {
          catchTacType = t;
          // Strip any generics / array suffix for the
          // short label name. The
          // full {@code TacType} is still carried in the
          // CATCH_HANDLER ctorType for the dataflow engine.
          String s = t.toString();
          int lt = s.indexOf('<');
          if (lt >= 0) s = s.substring(0, lt);
          int dot = s.lastIndexOf('.');
          if (dot >= 0) s = s.substring(dot + 1);
          catchType = s;
        }
      }
      String labelName =
          catchType.isEmpty() ? b.newLabel("L_CATCH") : b.newLabel("L_CATCH_" + catchType);
      catchLabels.add(labelName);
      if (firstCatchStart < 0) {
        firstCatchStart = b.instructions().size();
      }
      // Emit a LABEL first so existing label-name-based
      // tooling keeps working. CATCH_HANDLER follows it with
      // the full type and the catch parameter binding.
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(labelName)
              .line(TacTranslator.nodeLine(cc))
              .build());
      if (p != null) {
        TacType t = catchTacType == null ? TypeMapper.map(p.getType()) : catchTacType;
        TacOperand.TacVar v = new TacOperand.TacVar(p.getNameAsString(), t, false);
        b.locals().add(v);
        // Emit CATCH_HANDLER with the catch parameter
        // as {@code dest} and the exception type as
        // {@code ctorType}. The CFG builder treats this as
        // a block entry and the dataflow engine treats the
        // dest as a non-null local initialized to the
        // exception value flowing from the matched throw.
        // Drop the {@code MOVE NULL} initialization
        // — the catch parameter starts in NOT_NULL.
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
                .dest(v)
                .ctorType(catchTacType)
                .line(TacTranslator.nodeLine(cc))
                .build());
      } else {
        // No parameter (rare: pre-Java 7 multi-catch with
        // {@code try { ... } catch (Foo | Bar) { ... }}).
        // Emit a parameterless CATCH_HANDLER for IR
        // completeness.
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.CATCH_HANDLER)
                .ctorType(catchTacType)
                .line(TacTranslator.nodeLine(cc))
                .build());
      }
      translate(cc.getBody(), b);
      lastCatchEnd = b.instructions().size() - 1;
    }
    // FINALLY_ENTER marker. Emit a unique label so
    // existing tooling still finds an LABEL; the FINALLY_ENTER
    // is the IR-level signal that the CFG builder uses to route
    // exits of try / catch through the finally block.
    String finallyLabel = null;
    int finallyStartIdx = -1;
    int finallyEndIdx = -1;
    if (ts.getFinallyBlock().isPresent()) {
      Statement fb = ts.getFinallyBlock().get();
      finallyLabel = b.newLabel("L_FINALLY");
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(finallyLabel)
              .line(TacTranslator.nodeLine(fb))
              .build());
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.FINALLY_ENTER)
              .label(finallyLabel)
              .line(TacTranslator.nodeLine(fb))
              .build());
      finallyStartIdx = b.instructions().size();
      translate(fb, b);
      finallyEndIdx = b.instructions().size() - 1;
    }
    // Register the TryRegion so the CFG builder can add
    // exception-flow edges. Skip if the try block was empty
    // (tryStartIdx > tryEndIdx means no instructions were
    // emitted for the body); such regions are still legal
    // syntactically but no edges are needed.
    if (tryStartIdx <= tryEndIdx) {
      b.recordTryRegion(
          new TacFunction.TryRegion(
              tryStartIdx,
              tryEndIdx,
              firstCatchStart,
              lastCatchEnd,
              catchLabels,
              finallyLabel,
              finallyStartIdx,
              finallyEndIdx));
    }
  }

  private void translateSwitch(SwitchStmt ss, TacBuilder b) {
    // Lower to if/else-if chain: handles the common shape.
    ExpressionResult disc = owner.exprs().translate(ss.getSelector(), b);
    String lEnd = b.newLabel("L_SW_END");
    // Open the switch as a breakable statement so `break;` inside case
    // bodies can jump to its end label. The previous target is restored in
    // the finally block, which is what makes nested switches work.
    BreakTarget previous = pushTarget(false, lEnd, null);
    try {
      int count = ss.getEntries().size();

      // The BODY label of every entry, in source order. The bodies are laid
      // out one after another, so a case without a `break` falls into the
      // next one -- which is Java's fall-through.
      List<String> caseLabels = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        caseLabels.add(b.newLabel("L_CASE_" + i));
      }

      // The `default` entry has no test of its own: it is what the LAST test
      // falls to when nothing matched. A `default` in the middle of the
      // source therefore does not cut the tests after it short.
      String defaultBody = null;
      for (int i = 0; i < count; i++) {
        if (ss.getEntries().get(i).getLabels().isEmpty()) {
          defaultBody = caseLabels.get(i);
          break;
        }
      }
      String noMatch = defaultBody != null ? defaultBody : lEnd;

      // The TEST label of every case LABEL (an entry may carry several:
      // `case 1, 2 ->`). The tests form a chain: a test that does not match
      // goes to the NEXT TEST, not into the next entry's body.
      List<String> testLabels = new ArrayList<>();
      int[] testStart = new int[count];
      int[] testCount = new int[count];
      for (int i = 0; i < count; i++) {
        SwitchEntry entry = ss.getEntries().get(i);
        testStart[i] = testLabels.size();
        testCount[i] = entry.getLabels().size();
        for (int k = 0; k < testCount[i]; k++) {
          testLabels.add(b.newLabel("L_SW_TEST_" + i + "_" + k));
        }
      }
      // The test that follows an entry: the first test of the next entry that
      // has one, or "nothing matched".
      String[] afterEntry = new String[count];
      for (int i = 0; i < count; i++) {
        String next = noMatch;
        for (int j = i + 1; j < count; j++) {
          if (testCount[j] > 0) {
            next = testLabels.get(testStart[j]);
            break;
          }
        }
        afterEntry[i] = next;
      }

      for (int i = 0; i < count; i++) {
        SwitchEntry entry = ss.getEntries().get(i);
        if (testCount[i] == 0) {
          // The default: it has no test. It is reached from the last test's
          // false edge.
          continue;
        }
        // A `when` GUARD is a second test, applied AFTER the label matched.
        // When it fails, the switch continues with the NEXT case's test --
        // not with the default.
        boolean guarded = entry.getGuard().isPresent();
        String matchLabel = guarded ? b.newLabel("L_SW_MATCH_" + i) : null;
        for (int k = 0; k < testCount[i]; k++) {
          var labelExpr = entry.getLabels().get(k);
          String testLabel = testLabels.get(testStart[i] + k);
          String falseTarget =
              (k + 1 < testCount[i]) ? testLabels.get(testStart[i] + k + 1) : afterEntry[i];
          b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(testLabel).build());
          // Java 21 added pattern labels (`case String s ->`). A pattern label is a
          // TYPE TEST, not a value to compare against: the condition is an INSTANCEOF
          // and the pattern's variables are bound inside the arm's body.
          TacOperand.TacVar test;
          if (labelExpr instanceof com.github.javaparser.ast.expr.PatternExpr pattern) {
            test = owner.modern().emitPatternTestForSwitch(pattern, disc, b, entry);
          } else {
            ExpressionResult label = owner.exprs().translate(labelExpr, b);
            test = b.newTemp(TacType.BOOLEAN);
            b.emit(
                TacInstruction.builder(TacInstruction.Kind.BINOP)
                    .dest(test)
                    .binop(org.plumb.tac.ir.BinaryOperator.EQ)
                    .lhs1(disc.operand())
                    .lhs2(label.operand())
                    .line(TacTranslator.nodeLine(labelExpr))
                    .build());
          }
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.BRANCH)
                  .lhs1(test)
                  .trueLabel(guarded ? matchLabel : caseLabels.get(i))
                  .falseLabel(falseTarget)
                  .line(TacTranslator.nodeLine(entry))
                  .build());
        }
        if (guarded) {
          b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(matchLabel).build());
          ExpressionResult guardValue = owner.exprs().translate(entry.getGuard().get(), b);
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.BRANCH)
                  .lhs1(guardValue.operand())
                  .trueLabel(caseLabels.get(i))
                  .falseLabel(afterEntry[i])
                  .line(TacTranslator.nodeLine(entry))
                  .build());
        }
      }

      for (int i = 0; i < count; i++) {
        SwitchEntry entry = ss.getEntries().get(i);
        b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(caseLabels.get(i)).build());
        // A pattern label's variables are bound HERE, inside the arm's body, so that
        // they are in scope only for this arm -- which is exactly Java's scoping rule.
        bindSwitchPatternVariables(entry, disc, b);
        for (Statement s : entry.getStatements()) {
          translate(s, b);
        }
      }
      b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    } finally {
      restoreTarget(previous);
    }
  }

  /**
   * Binds the variables a switch arm's pattern declares.
   *
   * <p>Called at the start of the arm's body, so the binding is reachable only from that arm --
   * which is Java's scoping rule for a pattern variable.
   *
   * <p>Only the arm's FIRST pattern label is bound. Java allows multiple labels on one arm ({@code
   * case String s, Integer i ->}), and a variable declared in any of them is in scope for the arm;
   * binding all of them is what makes the arm's body able to use them.
   */
  private void bindSwitchPatternVariables(
      SwitchEntry entry, ExpressionResult selector, TacBuilder b) {
    for (var labelExpr : entry.getLabels()) {
      if (labelExpr instanceof com.github.javaparser.ast.expr.PatternExpr pattern) {
        owner.modern().bindPatternVariablesForSwitch(pattern, selector, b, entry);
      }
    }
  }

  /**
   * Translate a labeled statement.
   *
   * <p>A label has two jobs. It names a JUMP TARGET for {@code break label;} and {@code continue
   * label;}, and it scopes those targets to the labeled statement.
   *
   * <p>When the labeled statement is a LOOP, the label names that loop's own break and continue
   * targets, so the loop translator registers them (see {@link #registerPendingLabel}). When the
   * labeled statement is anything else -- a block, say -- {@code break label;} leaves it, so the
   * statement gets an end label of its own to jump to; Java forbids {@code continue label;} there,
   * so that form has no continue target.
   */
  private void translateLabeled(LabeledStmt ls, TacBuilder b) {
    String label = ls.getLabel().getIdentifier();
    Statement inner = ls.getStatement();
    boolean isLoop =
        inner instanceof WhileStmt
            || inner instanceof DoStmt
            || inner instanceof ForStmt
            || inner instanceof ForEachStmt;

    if (isLoop) {
      pendingLabel.set(label);
      try {
        translate(inner, b);
      } finally {
        pendingLabel.remove();
      }
      return;
    }

    String lEnd = b.newLabel("L_LABEL_END");
    labeledTargets.get().put(label, new BreakTarget(false, lEnd, null));
    try {
      translate(inner, b);
      b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    } finally {
      labeledTargets.get().remove(label);
    }
  }

  private void translateSynchronized(SynchronizedStmt syn, TacBuilder b) {
    // Model: acquire lock (no-op) + body + release (no-op).
    owner.exprs().translate(syn.getExpression(), b);
    translate(syn.getBody(), b);
  }

  private void translateAssert(AssertStmt as, TacBuilder b) {
    // Emit the predicate; the dataflow engine doesn't analyze asserts.
    var c = as.getCheck();
    if (c != null) owner.exprs().translate(c, b);
    as.getMessage().ifPresent(m -> owner.exprs().translate(m, b));
  }

  /**
   * One breakable statement: what a {@code break} and a {@code continue} may target.
   *
   * <p>{@link #loop} tells the two apart. A switch is breakable but not continuable -- Java never
   * lets a {@code continue} target a switch -- so its continue label is null.
   */
  private static final class BreakTarget {
    final boolean loop;
    final String breakLabel;
    final String continueLabel;

    BreakTarget(boolean loop, String breakLabel, String continueLabel) {
      this.loop = loop;
      this.breakLabel = breakLabel;
      this.continueLabel = continueLabel;
    }
  }
}
