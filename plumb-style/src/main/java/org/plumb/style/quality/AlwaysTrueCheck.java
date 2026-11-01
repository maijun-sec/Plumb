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
/*
 * AST-style CWE-571 (Expression is Always True) checker.
 *
 * <p>Fires on expressions that are provably always true:</p>
 * <ul>
 *   <li>{@code if (true)} / {@code while (true)}</li>
 *   <li>{@code 1 == 1} / {@code 0 == 0}</li>
 *   <li>{@code X == X} (always true)</li>
 *   <li>a condition whose value is a decided constant -- a boolean field, a boolean local, or a
 *       method that returns a constant</li>
 *   <li>two decided constants compared with {@code ==}</li>
 *   <li>a decided string compared with {@code equals}</li>
 *   <li>the negation of a comparison between the classes of two differently declared variables</li>
 * </ul>
 *
 * <p>Replaces the plumb-dfa {@code ClassSourceChecker.AlwaysTrueChecker}
 * inner class ( audit cleanup 2026-10-14).</p>
 *
 * <p><b>pattern D rewrite.</b> Same rewrite as
 * {@link AlwaysFalseCheck}: per-node visit overloads
 * ({@code IfStmt} / {@code WhileStmt} / {@code BinaryExpr}) instead
 * of three {@code findAll} calls in {@code visit(CompilationUnit)}.
 * 3 walks per file → 0 redundant walks.</p>
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class AlwaysTrueCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-quality-always-true", "ALWAYS_TRUE"),
          "Expression is always true (CWE-571)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-571"),
          "org.plumb.style.quality.AlwaysTrueCheck",
          true,
          List.of("ast-quality-always-true", "always-true"));

  @Override
  public String name() {
    return "always-true";
  }

  /** Per-file dedup set; reset in {@link #init(StyleContext)}. */
  private final Set<String> fired = new HashSet<>();

  /** The file's constant resolver; built once per file. */
  private ConstantResolver constants;

  @Override
  public void init(StyleContext ctx) {
    fired.clear();
    constants = new ConstantResolver(ctx.source().unit(), ctx);
  }

  @Override
  public void visit(IfStmt s, StyleContext ctx) {
    if (isTrueLiteral(s.getCondition())) {
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      if (fired.add(line + "|if-true")) {
        ctx.report(
            line,
            -1,
            RULE,
            "if (true) is unconditional (CWE-571)",
            RuleDef.Severity.LOW,
            "if (true)");
      }
      return;
    }
    reportDecidedCondition(s.getCondition(), ctx, "if");
  }

  @Override
  public void visit(WhileStmt s, StyleContext ctx) {
    if (isTrueLiteral(s.getCondition())) {
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      if (fired.add(line + "|while-true")) {
        ctx.report(
            line,
            -1,
            RULE,
            "while (true) is unconditional infinite loop (CWE-571)",
            RuleDef.Severity.LOW,
            "while (true)");
      }
      return;
    }
    reportDecidedCondition(s.getCondition(), ctx, "while");
  }

  /**
   * Report a condition whose value is already decided.
   *
   * <p>The condition is a decided constant, so the branch is not a branch. The report names the
   * constant, because "this condition is always true" is only actionable when the reader can see
   * <i>which</i> value it is.
   */
  private void reportDecidedCondition(Expression condition, StyleContext ctx, String keyword) {
    if (constants == null || condition == null) return;
    ConstantResolver.Value value = constants.resolve(condition, condition);
    if (value == null) return;
    Boolean decided = value.asBoolean();
    if (decided == null) return;
    int line = condition.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|" + keyword + "-decided")) return;
    ctx.report(
        line,
        -1,
        RULE,
        "`"
            + keyword
            + " ("
            + condition
            + ")` is always "
            + decided
            + ": `"
            + condition
            + "` is the constant "
            + value.description()
            + " (CWE-571).",
        RuleDef.Severity.LOW,
        condition.toString());
  }

  @Override
  public void visit(BinaryExpr e, StyleContext ctx) {
    if (isLiteralTrue(e)) {
      int line = e.getBegin().map(p -> p.line).orElse(-1);
      if (fired.add(line + "|" + e.toString())) {
        ctx.report(
            line,
            -1,
            RULE,
            "Expression `" + e.toString() + "` is always true (CWE-571).",
            RuleDef.Severity.LOW,
            e.toString());
      }
      return;
    }
    reportDecidedComparison(e, ctx);
  }

  /**
   * A comparison between two decided constants.
   *
   * <p>{@code privateFive == 5} is always true because both sides are the same constant. The
   * comparison decides the branch, and it decides it the same way every time.
   */
  private void reportDecidedComparison(BinaryExpr e, StyleContext ctx) {
    if (constants == null) return;
    BinaryExpr.Operator op = e.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
      return;
    }
    ConstantResolver.Value left = constants.resolve(e.getLeft(), e);
    ConstantResolver.Value right = constants.resolve(e.getRight(), e);
    if (left == null || right == null) return;
    if (!left.description().equals(right.description())) return;
    // The two sides are the same constant, so the comparison is always true (for ==) or always
    // false (for !=).
    boolean alwaysTrue = op == BinaryExpr.Operator.EQUALS;
    int line = e.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|decided-compare|" + e.toString())) return;
    ctx.report(
        line,
        -1,
        RULE,
        "Expression `"
            + e.toString()
            + "` is always "
            + alwaysTrue
            + ": both sides are the constant "
            + left.description()
            + " (CWE-571).",
        RuleDef.Severity.LOW,
        e.toString());
  }

  /**
   * The comparison of two classes, when the two sides are declared with DIFFERENT types.
   *
   * <p>{@code a.getClass().equals(b.getClass())} compares the RUNTIME classes. When {@code a} and
   * {@code b} are declared with different types, their runtime classes differ -- so the comparison
   * is always false and its negation is always true.
   *
   * <p>The shape is a LOGICAL_COMPLEMENT over the comparison, so the expression is a {@link
   * UnaryExpr}, not a {@link BinaryExpr}.
   */
  @Override
  public void visit(UnaryExpr u, StyleContext ctx) {
    if (u.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return;
    if (!(u.getExpression() instanceof MethodCallExpr equals)) return;
    if (!equals.getNameAsString().equals("equals")) return;
    if (equals.getArguments().size() != 1) return;

    // A decided string compared with a decided string: `!stringTrue.equals("true")` where
    // `stringTrue` holds "true" -- the comparison is always true, so its negation is always false.
    if (reportDecidedStringEquals(u, equals, ctx)) return;

    String leftType = classOfReceiver(equals.getScope().orElse(null));
    String rightType = classOfReceiver(equals.getArgument(0));
    if (leftType == null || rightType == null) return;
    if (leftType.equals(rightType)) return;

    int line = u.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|" + u.toString())) return;
    ctx.report(
        line,
        -1,
        RULE,
        "Expression `"
            + u.toString()
            + "` is always true: '"
            + leftType
            + "' and '"
            + rightType
            + "' are different types, so their classes differ and the comparison "
            + "is always false (CWE-571).",
        RuleDef.Severity.LOW,
        u.toString());
  }

  /**
   * A {@code equals} call between two decided strings.
   *
   * <p>{@code stringTrue.equals("true")} where {@code stringTrue} holds {@code "true"}: the
   * comparison is always true, so the branch around it is not a branch.
   */
  @Override
  public void visit(MethodCallExpr call, StyleContext ctx) {
    if (!"equals".equals(call.getNameAsString())) return;
    if (call.getArguments().size() != 1) return;
    // A negated call is reported by the UnaryExpr visitor, which sees the whole expression. The
    // inner call would otherwise be reported as well, and the two findings describe the same
    // decision from two sides.
    if (isNegatedOperand(call)) return;
    if (constants == null) return;
    ConstantResolver.Value receiver = constants.resolve(call.getScope().orElse(null), call);
    ConstantResolver.Value argument = constants.resolve(call.getArgument(0), call);
    if (receiver == null || argument == null) return;
    String a = receiver.asString();
    String b = argument.asString();
    if (a == null || b == null) return;
    if (!a.equals(b)) return;
    int line = call.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|decided-string-equals|" + call.toString())) return;
    ctx.report(
        line,
        -1,
        RULE,
        "Expression `"
            + call
            + "` is always true: `"
            + call.getScope().orElse(null)
            + "` holds the constant \""
            + a
            + "\" (CWE-571).",
        RuleDef.Severity.LOW,
        call.toString());
  }

  /**
   * A {@code equals} call between two decided strings, under a logical complement.
   *
   * <p>{@code !stringTrue.equals("true")} where {@code stringTrue} holds {@code "true"}: the
   * comparison is always true, so its negation is always false.
   *
   * @return true when the finding was reported, so the caller does not also try the class
   *     comparison.
   */
  private boolean reportDecidedStringEquals(UnaryExpr u, MethodCallExpr equals, StyleContext ctx) {
    if (constants == null) return false;
    ConstantResolver.Value receiver = constants.resolve(equals.getScope().orElse(null), u);
    ConstantResolver.Value argument = constants.resolve(equals.getArgument(0), u);
    if (receiver == null || argument == null) return false;
    String a = receiver.asString();
    String b = argument.asString();
    if (a == null || b == null) return false;
    if (!a.equals(b)) return false;
    int line = u.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|decided-string-equals|" + u.toString())) return true;
    ctx.report(
        line,
        -1,
        RULE,
        "Expression `"
            + u.toString()
            + "` is always false: `"
            + equals.getScope().orElse(null)
            + "` holds the constant \""
            + a
            + "\", so the comparison is always true (CWE-571).",
        RuleDef.Severity.LOW,
        u.toString());
    return true;
  }

  /**
   * The type of the receiver of a {@code getClass()} call, when the receiver is a local variable
   * with a declared type.
   */
  private static String classOfReceiver(Expression e) {
    if (!(e instanceof MethodCallExpr getClass)) return null;
    if (!getClass.getNameAsString().equals("getClass")) return null;
    if (!getClass.getScope().isPresent()) return null;
    if (!(getClass.getScope().get() instanceof NameExpr ne)) return null;
    String name = ne.getNameAsString();
    // The DECLARATION is a sibling of the call, not a descendant of it, so
    // the search has to start from the enclosing method -- searching from
    // the call itself would never find it.
    com.github.javaparser.ast.Node scope = getClass;
    while (scope != null && !(scope instanceof MethodDeclaration)) {
      scope = scope.getParentNode().orElse(null);
    }
    if (scope == null) return null;

    String found = null;
    for (VariableDeclarationExpr decl : scope.findAll(VariableDeclarationExpr.class)) {
      for (VariableDeclarator vd : decl.getVariables()) {
        if (!name.equals(vd.getNameAsString())) continue;
        // The LAST declaration wins: it is the one in scope at the call.
        found = vd.getType().asString();
      }
    }
    return found;
  }

  private static boolean isTrueLiteral(Expression e) {
    return e instanceof BooleanLiteralExpr ble && ble.getValue();
  }

  /** Is this expression the operand of a logical complement? */
  private static boolean isNegatedOperand(Expression e) {
    com.github.javaparser.ast.Node parent = e.getParentNode().orElse(null);
    return parent instanceof UnaryExpr u
        && u.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
        && u.getExpression() == e;
  }

  private static boolean isLiteralTrue(BinaryExpr e) {
    BinaryExpr.Operator op = e.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
      return false;
    }
    // 1 == 1 / 0 == 0
    if (isInt(e.getLeft(), 1) && isInt(e.getRight(), 1)) return true;
    if (isInt(e.getLeft(), 0) && isInt(e.getRight(), 0)) return true;
    // X == X  (X is bare name on both sides, no inner binop)
    String l = bareName(e.getLeft());
    if (l == null) return false;
    if (e.getRight() instanceof EnclosedExpr enc) {
      return isSelfRef(l, enc.getInner());
    }
    return l.equals(bareName(e.getRight()));
  }

  private static boolean isSelfRef(String leftName, Expression rhs) {
    if (rhs instanceof BinaryExpr inner) {
      if (inner.getOperator() != BinaryExpr.Operator.PLUS
          && inner.getOperator() != BinaryExpr.Operator.MINUS) {
        return false;
      }
      if (leftName.equals(bareName(inner.getLeft()))
          && inner.getRight() instanceof IntegerLiteralExpr) {
        return true;
      }
      if (leftName.equals(bareName(inner.getRight()))
          && inner.getLeft() instanceof IntegerLiteralExpr) {
        return true;
      }
      return false;
    }
    if (rhs instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      return isSelfRef(leftName, u.getExpression());
    }
    return leftName.equals(bareName(rhs));
  }

  private static boolean isInt(Expression e, int v) {
    if (e instanceof IntegerLiteralExpr ili) {
      try {
        return Integer.parseInt(ili.getValue()) == v;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
    if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      return isInt(u.getExpression(), -v);
    }
    return false;
  }

  private static String bareName(Expression e) {
    if (e instanceof NameExpr ne) return ne.getNameAsString();
    return null;
  }
}
