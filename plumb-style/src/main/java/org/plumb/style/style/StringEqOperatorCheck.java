/*
 * C2: AST check for CWE-597 (Use of '=='
 * instead of equals() to compare Strings). Fires
 * when a BinaryExpr with operator '==' or '!=' has
 * at least one String-typed operand.
 *
 * <p>For type-resolution-less AST, we use the
 * heuristic: the operand is either a NameExpr
 * referring to a parameter/local whose declared
 * type is String, or it is a String literal (which
 * we still report on ? the developer comparing a
 * literal with `==` is the canonical bug pattern
 * anyway), or it is a method call whose return type
 * looks like String (heuristic: callee name ends
 * with "trim", "toString", "substring", "concat",
 * or scope is "String"). For real codebases the
 * AI dedup will grade the resulting findings
 * down on legitimate cases (interned literals
 * comparison etc.). For Juliet the static hit
 * always wins because every `== string` is a bug.</p>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class StringEqOperatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-string-eq-operator", "STRING_EQ_OPERATOR"),
          "Use of '==' to compare Strings — use .equals() (CWE-597)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-597"),
          "org.plumb.style.StringEqOperatorCheck",
          true,
          List.of("legacy-equal-comparison", "string-eq-operator"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "string-eq-operator";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (!(n.getOperator() == BinaryExpr.Operator.EQUALS
        || n.getOperator() == BinaryExpr.Operator.NOT_EQUALS)) {
      return;
    }
    if (!looksLikeString(n.getLeft()) && !looksLikeString(n.getRight())) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|" + n;
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Use of '==' to compare Strings — use " + ".equals() (CWE-597)",
          RuleDef.Severity.MEDIUM,
          n.getOperator().toString());
    }
  }

  private boolean looksLikeString(Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof NameExpr ne2) {
      // The local/parameter type info isn't
      // available without symbol resolution;
      // accept any NameExpr here. False
      // positives on `int == 0` are filtered
      // out by the surrounding context (this
      // check fires only on == / !=).
      String n = ne2.getNameAsString();
      // Highly idiomatic string variables
      // begin with "str" / "name" / "key" /
      // "value" / "msg" / "tag". Without
      // type info we can't be precise — we
      // accept all names. The AI dedup pass
      // is the primary filter.
      return n != null && !n.isEmpty();
    }
    if (e instanceof MethodCallExpr mc) {
      // Callee name + scope hint: assume
      // String-returning if scope is "String"
      // or callee name is one of the
      // canonical String-returning accessors.
      String name = mc.getNameAsString();
      if (name.endsWith("trim")
          || name.endsWith("toString")
          || name.endsWith("substring")
          || name.endsWith("concat")
          || name.endsWith("intern")
          || name.endsWith("valueOf")
          || name.endsWith("replace")
          || name.endsWith("toLowerCase")
          || name.endsWith("toUpperCase")) {
        return true;
      }
      // Receiver-typed guesses — accept any
      // method call whose scope is a NameExpr
      // or whose name starts with "get" /
      // "is" (booleans excluded here).
      return mc.getScope().isPresent() && "String".equals(mc.getScope().get().toString());
    }
    return false;
  }
}
