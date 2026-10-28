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
 * Flags class comparisons that use the name instead of the type, so two
 * different classes with the same name compare equal (CWE-486).
 *
 * <p>Detects {@code a.getClass() == b.getClass()} or
 * {@code a.getClass() == SomeClass.class} -- comparing
 * classes by reference equality is unreliable across
 * classloaders. The DFA half only fired on
 * {@code getSimpleName()} calls (the indirect form via
 * the string result). The AST half fires on the direct
 * reference-equality form, which is the canonical
 * pattern reviewers usually look for.</p>
 *
 * <p><b>AST half.</b> Walk every {@link BinaryExpr} with
 * {@code ==} / {@code !=}. If either side is a
 * {@code getClass()} call (with empty arg list), fire.
 * If either side is a {@code .class} literal, fire.</p>
 *
 * <p>Note: the user-supplied CWE label is CWE-273
 * (insufficient privileges / compare-by-name family);
 * the legacy DFA source used CWE-486 (compare-objects-
 * by-name). We honour the user's CWE label here.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-273: compare classes by name / reference equality on {@code getClass()}. */
public final class CompareClassesByNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-compare-classes-by-name", "COMPARE_CLASSES_BY_NAME"),
          "Comparing classes via getClass() == b.getClass() / .class "
              + "is unreliable across classloaders (CWE-273)",
          RuleDef.Severity.LOW,
          RuleDef.Type.SECURITY,
          List.of("CWE-273"),
          "org.plumb.style.security.CompareClassesByNameCheck",
          true,
          List.of("dfa-security-compare-classes-by-name", "compare-classes-by-name"));

  @Override
  public String name() {
    return "compare-classes-by-name";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    BinaryExpr.Operator op = n.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
      return;
    }
    boolean leftIsGetClass = isGetClassCall(n.getLeft());
    boolean rightIsGetClass = isGetClassCall(n.getRight());
    boolean leftIsClassLiteral = isClassLiteral(n.getLeft());
    boolean rightIsClassLiteral = isClassLiteral(n.getRight());

    // Fire when at least one side is getClass() (the
    // canonical reference-equality check) and the
    // other is either getClass() or .class. Two
    // bare .class literals is a constant compare, not
    // a getClass() pattern — skip.
    if (!((leftIsGetClass && (rightIsGetClass || rightIsClassLiteral))
        || (rightIsGetClass && (leftIsGetClass || leftIsClassLiteral)))) {
      return;
    }

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Comparing classes by reference equality (a.getClass() "
            + "== b.getClass() or == X.class) is unreliable: "
            + "use instanceof instead (CWE-273)",
        RuleDef.Severity.LOW,
        "compare-classes-by-name: " + op.asString());
  }

  /**
   * True if {@code e} is a method-call expression whose name is {@code getClass} and has zero
   * arguments. Receiver is not type-checked (a fresh {@code Class<?>} reference also matches; the
   * DFA half was similarly loose).
   */
  private static boolean isGetClassCall(Expression e) {
    return e instanceof MethodCallExpr m
        && m.getNameAsString().equals("getClass")
        && m.getArguments().isEmpty();
  }

  /**
   * True if {@code e} is a {@code .class} literal -- e.g. {@code String.class}. AST shape: {@link
   * FieldAccessExpr} with name "class" and a type receiver.
   */
  private static boolean isClassLiteral(Expression e) {
    // JavaParser 3.26 parses `String.class` as a
    // TypeExpr (the "modern" form). Older code (and
    // explicit `.class` on a non-type reference) may
    // also surface as a FieldAccessExpr with name
    // "class". Accept both. The textual form of a
    // class literal ends in ".class"; we also accept
    // that as a last-resort fallback.
    if (e instanceof TypeExpr) return true;
    if (e instanceof FieldAccessExpr fa && fa.getNameAsString().equals("class")) return true;
    // Last-resort: textual fallback. The expression
    // toString() for `String.class` is "String.class"
    // regardless of which AST form JavaParser uses.
    if (e != null) {
      String s = e.toString();
      if (s.endsWith(".class")) return true;
    }
    return false;
  }
}
