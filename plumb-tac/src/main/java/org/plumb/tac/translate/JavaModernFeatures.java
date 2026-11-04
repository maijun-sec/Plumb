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

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.PatternExpr;
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import java.util.List;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Translation of the Java language features that arrived after Java 8.
 *
 * <p>Plumb's Java target is <b>Java 8 through Java 25</b>. Java 8's statement and expression set is
 * what the original translator was written against; everything since then -- switch expressions,
 * pattern matching for {@code instanceof}, record patterns, sealed types, records -- is handled
 * here.
 *
 * <h2>The rule: degrade, do not extend</h2>
 *
 * <p>Every construct in this file lowers to TAC that the existing engine already understands:
 * {@code BRANCH}, {@code MOVE}, {@code INSTANCEOF}, {@code CALL}. <b>No new opcode is introduced
 * for any of them.</b>
 *
 * <p>That is deliberate. The TAC is a multi-language contract: Kotlin's {@code when} and Scala's
 * {@code match} need to lower to the same shapes, and a Java-specific opcode would either need a
 * Kotlin/Scala counterpart or would leave those languages unable to express the construct at all.
 * The same reasoning applies inside a single language: a {@code SwitchExpr} is not a new kind of
 * computation, it is a branch chain that produces a value.
 *
 * <h2>What each construct becomes</h2>
 *
 * <table border="1">
 *   <caption>Java 14+ constructs and their TAC lowering</caption>
 *   <tr><th>Java</th><th>TAC</th></tr>
 *   <tr>
 *     <td>{@code switch (x) { case A -> ...; default -> ...; }}</td>
 *     <td>a temp, a BRANCH chain on the selector, and a MOVE into the temp on each arm
 *   </tr>
 *   <tr>
 *     <td>{@code yield v;} inside a switch expression</td>
 *     <td>a MOVE of {@code v} into the switch expression's temp, then a JUMP to the end
 *   </tr>
 *   <tr>
 *     <td>{@code x instanceof String s}</td>
 *     <td>an INSTANCEOF, plus a MOVE binding {@code s} -- so taint flows into the binding
 *   </tr>
 *   <tr>
 *     <td>{@code case String s ->}</td>
 *     <td>an INSTANCEOF against the arm's type, plus a MOVE binding {@code s}
 *   </tr>
 *   <tr>
 *     <td>{@code case Point(int x, int y) ->}</td>
 *     <td>an INSTANCEOF against the record type, plus a MOVE per component
 *   </tr>
 * </table>
 *
 * <h2>Why the pattern binding is a MOVE and not a special instruction</h2>
 *
 * <p>Because the binding is an ordinary assignment as far as the dataflow engine is concerned. A
 * pattern variable holds the same reference the scrutinee held, so a tainted scrutinee produces a
 * tainted binding -- and that falls out of the existing MOVE propagation with no new machinery. If
 * the binding were a distinct opcode, every taint checker, every alias tracker and every summary
 * writer would need to learn about it, and the Kotlin and Scala frontends would need their own
 * version of the same thing.
 *
 * <h2>Guarded patterns and the scoping rule</h2>
 *
 * <p>Java's binding variables are only in scope in the arm that declares them. The lowering binds
 * them in the arm's own block, which gives exactly that scope: the MOVE happens after the BRANCH
 * that selects the arm, so the binding is unreachable from any other arm.
 *
 * <p>One consequence is worth stating, because it is a place where a reader might expect more. In
 * {@code case String s ->} the value bound to {@code s} is the scrutinee itself, because the
 * pattern has no component list to destructure. The lowering therefore MOVEs the scrutinee into
 * {@code s}. For a record pattern the components are bound from the record's accessors, which the
 * lowering models as CALLs on the scrutinee -- so a tainted record propagates into each component
 * exactly as the source semantics require.
 */
final class JavaModernFeatures {

  private final TacTranslator owner;

  JavaModernFeatures(TacTranslator owner) {
    this.owner = owner;
  }

  // ─── switch expression ──────────────────────────────────────────────

  /**
   * Lowers a switch expression to a branch chain that produces a value.
   *
   * <p>The shape is: allocate the result temp, evaluate the selector, then for each arm emit a
   * condition and, when it holds, the arm's statements followed by a MOVE into the temp. The {@code
   * yield} statement inside an arm is the MOVE; a {@code break} inside a switch <i>statement</i>
   * still means "leave the switch".
   *
   * <p>Java requires the switch to be exhaustive, so there is always a path that assigns the temp.
   * The lowering does not verify exhaustiveness -- the compiler already did, and a missing
   * assignment would be a compile error, not a runtime one.
   *
   * @param se the switch expression
   * @param b the builder to emit into
   * @param expectedType the type the expression is expected to produce; used for the temp's type
   * @return the temp holding the switch's value
   */
  ExpressionResult translateSwitchExpr(SwitchExpr se, TacBuilder b, TacType expectedType) {
    ExpressionResult selector = owner.exprs().translate(se.getSelector(), b);
    TacType resultType = expectedType == null ? TacType.UNKNOWN : expectedType;
    TacOperand.TacVar result = b.newTemp(resultType);

    String endLabel = b.newLabel("L_SWEXPR_END");
    List<String> armLabels = new java.util.ArrayList<>();
    for (int i = 0; i < se.getEntries().size(); i++) {
      armLabels.add(b.newLabel("L_SWEXPR_ARM_" + i));
    }

    // The switch-expression context tells a `yield` statement where to assign.
    // It is pushed for the duration of the arms and popped afterwards.
    TacOperand.TacVar prevYieldTarget = owner.exprs().setYieldTarget(result);
    try {
      // Dispatch chain: each arm's condition branches to its body, else to the next arm.
      for (int i = 0; i < se.getEntries().size(); i++) {
        SwitchEntry entry = se.getEntries().get(i);
        Condition condition = armCondition(entry, selector, b);
        String next = (i + 1 < se.getEntries().size()) ? armLabels.get(i + 1) : endLabel;
        if (condition == null) {
          // A `default` arm: unconditional.
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.JUMP)
                  .label(armLabels.get(i))
                  .line(TacTranslator.nodeLine(entry))
                  .build());
          continue;
        }
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.BRANCH)
                .lhs1(condition.test)
                .trueLabel(armLabels.get(i))
                .falseLabel(next)
                .line(TacTranslator.nodeLine(entry))
                .build());
      }

      // The arms.
      for (int i = 0; i < se.getEntries().size(); i++) {
        SwitchEntry entry = se.getEntries().get(i);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.LABEL)
                .label(armLabels.get(i))
                .line(TacTranslator.nodeLine(entry))
                .build());
        // A pattern label's variables are bound HERE, inside the arm's body, so that
        // they are in scope only for this arm -- which is exactly Java's scoping rule.
        for (Expression label : entry.getLabels()) {
          if (label instanceof PatternExpr pattern) {
            bindPatternVariables(pattern, selector, b, entry);
          }
        }
        // An arrow arm's value is the expression itself (`case 1 -> 10;`), whereas a
        // block arm's value comes from a `yield` inside it. The arrow form is the
        // common one, and it is the form that used to lose the value: the expression
        // was translated, but nothing connected it to the switch expression's result.
        //
        // So the arrow form assigns its expression to the result temp directly. The
        // `yield` form does not need this: the yield statement itself MOVEs into the
        // same temp.
        //
        // The arrow arm's expression statement is NOT also translated by the statement
        // loop: translating it twice would evaluate the expression twice, and an
        // expression with a side effect (a method call, an allocation) would then have
        // that effect happen twice in the analysis -- which is both wrong and, for a
        // sink call, a duplicate finding.
        boolean arrowValueArm = isArrowValueArm(entry);
        if (arrowValueArm) {
          ExpressionResult armValue = owner.exprs().translate(armValueExpression(entry), b);
          b.emit(
              TacInstruction.builder(TacInstruction.Kind.MOVE)
                  .dest(result)
                  .lhs1(armValue.operand())
                  .line(TacTranslator.nodeLine(entry))
                  .build());
        }
        for (Statement s : entry.getStatements()) {
          if (arrowValueArm) break; // the single expression statement was handled above
          owner.stmts().translate(s, b);
        }
      }
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.LABEL)
              .label(endLabel)
              .line(TacTranslator.nodeLine(se))
              .build());
    } finally {
      owner.exprs().setYieldTarget(prevYieldTarget);
    }

    return new ExpressionResult(result, resultType);
  }

  /**
   * Whether an arm's value is given directly by an expression rather than by a {@code yield}.
   *
   * <p>An arrow arm ({@code case 1 -> 10;}) is a single expression statement, and that expression
   * IS the arm's value. A block arm ({@code case 1 -> { yield 10; }}) gets its value from the
   * {@code yield} inside it.
   *
   * <p>The distinction is decided by the arm's shape rather than by whether it happens to contain a
   * yield: an arrow arm cannot contain a yield, and a block arm's statements are a block, not a
   * bare expression.
   */
  private boolean isArrowValueArm(SwitchEntry entry) {
    List<Statement> statements = entry.getStatements();
    if (statements.size() != 1) return false;
    if (!(statements.get(0) instanceof com.github.javaparser.ast.stmt.ExpressionStmt es)) {
      return false;
    }
    // A block arm is a BlockStmt, not an expression statement, so this test is false for
    // it. JavaParser has no YieldExpr -- `yield` is a statement (YieldStmt), so an arrow
    // arm's expression is never a yield.
    return true;
  }

  /** The expression that gives an arrow arm its value. */
  private Expression armValueExpression(SwitchEntry entry) {
    com.github.javaparser.ast.stmt.ExpressionStmt es =
        (com.github.javaparser.ast.stmt.ExpressionStmt) entry.getStatements().get(0);
    return es.getExpression();
  }

  /**
   * The condition that selects one switch arm, or {@code null} for an unconditional {@code
   * default}.
   *
   * <p>An arm's labels are a mix of the classic constant labels ({@code case 1:}) and the pattern
   * labels of Java 21 ({@code case String s ->}). A pattern label is not a value to compare
   * against: it is a type test plus a binding. So the condition for a pattern arm is an INSTANCEOF
   * test, and the binding happens inside the arm's body.
   */
  private Condition armCondition(SwitchEntry entry, ExpressionResult selector, TacBuilder b) {
    if (entry.getLabels().isEmpty()) {
      // `default` (or a legacy `default:` with no label).
      return null;
    }
    if (entry.getLabels().size() > 1) {
      // Multiple labels on one arm (`case 1, 2 ->`). The condition is the OR of the
      // individual label conditions. Each label is emitted with its own test; the OR is
      // built as a chain of BINOPs.
      TacOperand.TacVar acc = null;
      for (Expression label : entry.getLabels()) {
        Condition c = labelCondition(label, selector, b);
        if (c == null) {
          // A `default` among the labels makes the whole arm unconditional.
          return null;
        }
        if (acc == null) {
          acc = c.test;
          continue;
        }
        TacOperand.TacVar combined = b.newTemp(TacType.BOOLEAN);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.BINOP)
                .dest(combined)
                .binop(BinaryOperator.OR)
                .lhs1(acc)
                .lhs2(c.test)
                .line(TacTranslator.nodeLine(label))
                .build());
        acc = combined;
      }
      return acc == null ? null : new Condition(acc);
    }
    return labelCondition(entry.getLabels().get(0), selector, b);
  }

  /**
   * The condition for a single case label.
   *
   * <p>A constant label compares the selector to the constant. A pattern label tests the selector's
   * type. A {@code null} label (the {@code case null ->} of Java 21) compares the selector to the
   * null literal.
   */
  private Condition labelCondition(Expression label, ExpressionResult selector, TacBuilder b) {
    if (label instanceof PatternExpr pattern) {
      return new Condition(emitPatternTest(pattern, selector, b));
    }
    if (label instanceof com.github.javaparser.ast.expr.NullLiteralExpr) {
      TacOperand.TacVar test = b.newTemp(TacType.BOOLEAN);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.BINOP)
              .dest(test)
              .binop(BinaryOperator.EQ)
              .lhs1(selector.operand())
              .lhs2(TacOperand.Const.ofNull())
              .line(TacTranslator.nodeLine(label))
              .build());
      return new Condition(test);
    }
    // A classic constant label. The selector may be a constant itself, in which case
    // the comparison is still emitted: the engine's constant folding can remove it and the
    // dataflow sees the same shape it would for a variable selector.
    ExpressionResult labelValue = owner.exprs().translate(label, b);
    TacOperand.TacVar test = b.newTemp(TacType.BOOLEAN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(test)
            .binop(BinaryOperator.EQ)
            .lhs1(selector.operand())
            .lhs2(labelValue.operand())
            .line(TacTranslator.nodeLine(label))
            .build());
    return new Condition(test);
  }

  // ─── pattern matching ────────────────────────────────────────────────

  /**
   * Lowers {@code x instanceof String s} to an INSTANCEOF plus a binding.
   *
   * <p>The binding is a MOVE of the scrutinee into the pattern variable, which is what makes the
   * pattern variable carry the scrutinee's taint.
   */
  ExpressionResult translateInstanceOfWithPattern(InstanceOfExpr ioe, TacBuilder b) {
    ExpressionResult scrutinee = owner.exprs().translate(ioe.getExpression(), b);
    PatternExpr pattern = ioe.getPattern().orElse(null);
    if (pattern == null) {
      // No pattern: the plain type test.
      TacType target = TypeMapper.map(ioe.getType());
      TacOperand.TacVar dest = b.newTemp(TacType.BOOLEAN);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
              .dest(dest)
              .lhs1(scrutinee.operand())
              .ctorType(target)
              .line(TacTranslator.nodeLine(ioe))
              .build());
      return new ExpressionResult(dest, TacType.BOOLEAN);
    }

    // With a pattern, the INSTANCEOF's type comes from the pattern itself (the pattern
    // carries the type; `ioe.getType()` is the same type but the pattern is the
    // authoritative source for a record pattern's record type).
    TacOperand.TacVar test = emitPatternTest(pattern, scrutinee, b);
    // Bind the pattern variables. The binding happens unconditionally here, which is
    // correct for `instanceof` (the binding only happens when the test succeeds, and
    // the value is only USED when the test succeeds) and is what the Java semantics
    // allow: the pattern variable is in scope after the instanceof only when it matched.
    bindPatternVariables(pattern, scrutinee, b, ioe);
    return new ExpressionResult(test, TacType.BOOLEAN);
  }

  /**
   * Emits the type test for a pattern and returns the boolean result.
   *
   * <p>For a record pattern, the test is an INSTANCEOF against the record type. The component
   * bindings are emitted separately by {@link #bindPatternVariables}.
   */
  private TacOperand.TacVar emitPatternTest(
      PatternExpr pattern, ExpressionResult scrutinee, TacBuilder b) {
    TacType target = TypeMapper.map(pattern.getType());
    TacOperand.TacVar test = b.newTemp(TacType.BOOLEAN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
            .dest(test)
            .lhs1(scrutinee.operand())
            .ctorType(target)
            .line(TacTranslator.nodeLine(pattern))
            .build());
    return test;
  }

  /**
   * Binds a pattern's variables.
   *
   * <p>A type pattern binds the scrutinee itself. A record pattern binds each component from the
   * record's accessor, which the lowering models as a CALL on the scrutinee -- so the component
   * receives the accessor's result, and taint flows the way the source semantics say.
   */
  private void bindPatternVariables(
      PatternExpr pattern, ExpressionResult scrutinee, TacBuilder b, Node context) {
    if (pattern instanceof TypePatternExpr tp) {
      TacType boundType = TypeMapper.map(tp.getType());
      TacOperand.TacVar bound = new TacOperand.TacVar(tp.getNameAsString(), boundType, false);
      b.addLocal(bound);
      b.emit(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(bound)
              .lhs1(scrutinee.operand())
              .line(TacTranslator.nodeLine(context))
              .build());
      return;
    }
    if (pattern instanceof RecordPatternExpr rp) {
      for (PatternExpr component : rp.getPatternList()) {
        // Each component is read from the record's accessor of the same name.
        String componentName = componentNameOf(component);
        if (componentName == null) continue;
        // The accessor call: `scrutinee.<componentName>()`.
        TacOperand.TacVar accessorResult = b.newTemp(TacType.UNKNOWN);
        b.emit(
            TacInstruction.builder(TacInstruction.Kind.CALL)
                .dest(accessorResult)
                .calleeSig(TypeMapper.map(rp.getType()).displayName() + "." + componentName + "/0")
                .args(java.util.List.of(scrutinee.operand()))
                .line(TacTranslator.nodeLine(component))
                .build());
        ExpressionResult componentValue = new ExpressionResult(accessorResult, TacType.UNKNOWN);
        bindPatternVariables(component, componentValue, b, component);
      }
    }
  }

  /**
   * The name a pattern component binds.
   *
   * <p>A record pattern's component list is written {@code Point(int x, int y)}; the bound names
   * are {@code x} and {@code y}, and the record's accessors are {@code x()} and {@code y()}. So the
   * bound name is the accessor name.
   */
  private String componentNameOf(PatternExpr component) {
    if (component instanceof TypePatternExpr tp) {
      return tp.getNameAsString();
    }
    return null;
  }

  /**
   * Emits the type test for a switch arm's pattern label.
   *
   * <p>The test is an INSTANCEOF against the pattern's type. The binding happens separately, in the
   * arm's body -- see {@link #bindPatternVariablesForSwitch}.
   *
   * @param pattern the pattern label
   * @param selector the switch's selector
   * @param b the builder to emit into
   * @param context the node the line number comes from
   * @return the boolean result of the type test
   */
  TacOperand.TacVar emitPatternTestForSwitch(
      PatternExpr pattern, ExpressionResult selector, TacBuilder b, Node context) {
    TacType target = TypeMapper.map(pattern.getType());
    TacOperand.TacVar test = b.newTemp(TacType.BOOLEAN);
    b.emit(
        TacInstruction.builder(TacInstruction.Kind.INSTANCEOF)
            .dest(test)
            .lhs1(selector.operand())
            .ctorType(target)
            .line(TacTranslator.nodeLine(context))
            .build());
    return test;
  }

  /**
   * Binds the variables a switch arm's pattern declares.
   *
   * <p>Called at the start of the arm's body, so the binding is reachable only from that arm --
   * Java's scoping rule for a pattern variable.
   */
  void bindPatternVariablesForSwitch(
      PatternExpr pattern, ExpressionResult selector, TacBuilder b, Node context) {
    bindPatternVariables(pattern, selector, b, context);
  }

  /** A boolean test produced for a switch arm. */
  private record Condition(TacOperand.TacVar test) {}
}
