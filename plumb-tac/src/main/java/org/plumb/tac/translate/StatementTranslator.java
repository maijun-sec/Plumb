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
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import java.util.ArrayList;
import java.util.List;
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

  /** Break / continue targets for the innermost enclosing loop. */
  private final ThreadLocal<LoopCtx> loopCtx = ThreadLocal.withInitial(LoopCtx::new);

  /**
   * End label of the innermost enclosing switch -- used by {@code break;} statements inside case
   * bodies to jump past the switch. Switch statements are lowered to an if/else-if chain (see
   * {@link #translateSwitch}), and {@code break} inside a case body must emit a {@code JUMP} to the
   * switch-end label, otherwise the body of subsequent cases is unreachable in TAC and downstream
   * analyzers miss findings whose source is on a path past the first matching case (e.g. Juliet
   * CWE191 Variant 15: {@code switch(6){...}} then {@code switch(7){...}}).
   */
  private final ThreadLocal<String> switchEndLabel = new ThreadLocal<>();

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
      owner.exprs().translate(ys.getExpression(), b);
      // We don't model yield's `result` return; just a debug breadcrumb.
      LOG.debug("yield expression emitted (result not threaded back)");
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
    LOG.debug(
        "Unhandled statement: {} at line {}",
        s.getClass().getSimpleName(),
        TacTranslator.nodeLine(s));
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

  private void translateVarDecl(VariableDeclarationExpr vde, TacBuilder b) {
    for (var declarator : vde.getVariables()) {
      TacType t = TypeMapper.map(vde.getElementType());
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
          ExpressionResult scope = owner.exprs().translate(faeInit.getScope(), b);
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
                  .dest(v)
                  .lhs1(scope.operand())
                  .fieldName(faeInit.getNameAsString())
                  .line(TacTranslator.nodeLine(declarator))
                  .build());
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
        }
      }
    }
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
    LoopCtx outer = loopCtx.get();
    LoopCtx inner = new LoopCtx(lEnd, lCond);
    loopCtx.set(inner);
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
    loopCtx.set(outer);
  }

  private void translateDo(DoStmt ds, TacBuilder b) {
    String lBody = b.newLabel("L_DO_BODY");
    String lEnd = b.newLabel("L_DO_END");
    LoopCtx outer = loopCtx.get();
    loopCtx.set(new LoopCtx(lEnd, lBody));
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
    loopCtx.set(outer);
  }

  private void translateFor(ForStmt fs, TacBuilder b) {
    for (Expression init : fs.getInitialization()) {
      owner.exprs().translate(init, b);
    }
    String lCond = b.newLabel("L_FOR_COND");
    String lBody = b.newLabel("L_FOR_BODY");
    String lUpdate = b.newLabel("L_FOR_UPDATE");
    String lEnd = b.newLabel("L_FOR_END");
    LoopCtx outer = loopCtx.get();
    loopCtx.set(new LoopCtx(lEnd, lUpdate));
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
    loopCtx.set(outer);
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
    LoopCtx outer = loopCtx.get();
    loopCtx.set(new LoopCtx(lEnd, lCond));
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
      TacType vt = TypeMapper.map(vde.getElementType());
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
    loopCtx.set(outer);
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
            .line(TacTranslator.nodeLine(ts))
            .build());
  }

  private void translateBreak(BreakStmt bs, TacBuilder b) {
    // Prefer the innermost switch's end label over the loop's break label.
    // Switch-case break statements have no enclosing LoopCtx, so without
    // this they would silently drop (logging only a warning) and the
    // TAC would fall through to the next case body — losing all
    // statements past the first matching case.
    String switchEnd = switchEndLabel.get();
    if (switchEnd != null) {
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.JUMP)
              .label(switchEnd)
              .line(TacTranslator.nodeLine(bs))
              .build());
      return;
    }
    LoopCtx ctx = loopCtx.get();
    if (ctx == null || ctx.breakLabel == null) {
      LOG.warn("break outside loop at line {}", TacTranslator.nodeLine(bs));
      return;
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(ctx.breakLabel)
            .line(TacTranslator.nodeLine(bs))
            .build());
  }

  private void translateContinue(ContinueStmt cs, TacBuilder b) {
    LoopCtx ctx = loopCtx.get();
    if (ctx == null || ctx.continueLabel == null) {
      LOG.warn("continue outside loop at line {}", TacTranslator.nodeLine(cs));
      return;
    }
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.JUMP)
            .label(ctx.continueLabel)
            .line(TacTranslator.nodeLine(cs))
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
        TacType t = TypeMapper.map(vde.getElementType());
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
        TacType t = TypeMapper.map(p.getType());
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
        TacType t = TypeMapper.map(p.getType());
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
    // Push the switch end label so `break;` inside case bodies can jump
    // to it. We save/restore the previous value to handle nested switches.
    String prevSwitchEnd = switchEndLabel.get();
    switchEndLabel.set(lEnd);
    try {
      List<String> caseLabels = new ArrayList<>();
      for (int i = 0; i < ss.getEntries().size(); i++) {
        caseLabels.add(b.newLabel("L_CASE_" + i));
      }
      for (int i = 0; i < ss.getEntries().size(); i++) {
        SwitchEntry entry = ss.getEntries().get(i);
        // For each label, emit (disc == label) -- goto case_i : fall through.
        if (entry.getLabels().isEmpty()) {
          // default — unconditional branch
          b.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(caseLabels.get(i)).build());
          continue;
        }
        for (var labelExpr : entry.getLabels()) {
          ExpressionResult label = owner.exprs().translate(labelExpr, b);
          TacOperand.TacVar cmp = b.newTemp(TacType.BOOLEAN);
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.BINOP)
                  .dest(cmp)
                  .binop(org.plumb.tac.ir.BinaryOperator.EQ)
                  .lhs1(disc.operand())
                  .lhs2(label.operand())
                  .line(TacTranslator.nodeLine(labelExpr))
                  .build());
          String next = (i + 1 < ss.getEntries().size()) ? caseLabels.get(i + 1) : lEnd;
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.BRANCH)
                  .lhs1(cmp)
                  .trueLabel(caseLabels.get(i))
                  .falseLabel(next)
                  .line(TacTranslator.nodeLine(entry))
                  .build());
        }
      }
      // No label matched: jump to end.
      b.emit(TacInstruction.builder(TacInstruction.Kind.JUMP).label(lEnd).build());
      for (int i = 0; i < ss.getEntries().size(); i++) {
        b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(caseLabels.get(i)).build());
        for (Statement s : ss.getEntries().get(i).getStatements()) {
          translate(s, b);
        }
      }
      b.emit(TacInstruction.builder(TacInstruction.Kind.LABEL).label(lEnd).build());
    } finally {
      switchEndLabel.set(prevSwitchEnd);
    }
  }

  private void translateLabeled(LabeledStmt ls, TacBuilder b) {
    // A labeled statement; we don't model labels specially, just
    // translate the inner statement.
    translate(ls.getStatement(), b);
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

  /** Per-thread break/continue label context. */
  private static final class LoopCtx {
    final String breakLabel;
    final String continueLabel;

    LoopCtx(String breakLabel, String continueLabel) {
      this.breakLabel = breakLabel;
      this.continueLabel = continueLabel;
    }

    LoopCtx() {
      this(null, null);
    }
  }
}
