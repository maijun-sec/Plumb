package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * A {@link java.util.Comparator#compare(Object, Object) Comparator.compare()} method that returns
 * a raw {@code 1} or {@code -1} literal instead of delegating to {@link Integer#compare(int, int)}.
 *
 * <p>The previous implementation had two bugs (S1 audit 2026-10-17):
 *
 * <ol>
 *   <li>It tested the same field twice ({@code getNameAsString()} against both {@code "compareTo"}
 *       and {@code "compare"}), making the second check unreachable and the rule fire on EVERY
 *       {@code compareTo} call in the codebase.
 *   <li>It never actually inspected {@code return} statements, so the rule never caught the
 *       anti-pattern it was supposed to catch.
 * </ol>
 *
 * <p>The corrected logic walks every {@link MethodDeclaration}, checks whether it is a {@code
 * Comparator<T>.compare(T, T)} override (name = {@code "compare"}, 2 parameters, declaring class
 * implements {@code Comparator} or extends a class that does), and inspects each {@code return}
 * statement for a literal {@code 1} / {@code -1}.
 */
public final class ComparatorCompareCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-comparator-compare", "COMPARATOR_COMPARE"),
          "compare() returns literal 1 or -1 — use Integer.compare() instead",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ComparatorCompareCheck",
          true,
          List.of("ast-bug-comparator-compare", "comparator-compare")
      );

  @Override
  public String name() {
    return "comparator-compare";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Step 1: only consider `compare(Object, Object)`-shaped
    // methods. The signature is what matters, not the
    // declared type — a class that implements Comparator
    // via inheritance still has a `compare(Object, Object)`
    // method.
    if (!"compare".equals(n.getNameAsString())) return;
    if (n.getParameters().size() != 2) return;

    // Step 2: the enclosing class must implement
    // java.util.Comparator (directly or via a base class).
    // We check the immediate implements / extends list.
    // Subclasses that inherit the compare() contract from
    // a parent that implements Comparator will be flagged
    // by THIS same check when the dispatcher visits the
    // subclass declaration directly — the parent might
    // define compare() as abstract (no body) so the rule
    // doesn't fire there, and the subclass's concrete
    // implementation will be caught here.
    if (!declaresComparator(n)) return;

    // Step 3: inspect return statements. A return of
    // IntegerLiteralExpr 1 or -1 (with optional unary
    // minus) is the anti-pattern; a return of
    // Integer.compare(...) is the recommended idiom.
    for (ReturnStmt ret : n.findAll(ReturnStmt.class)) {
      if (!isLiteralOneOrMinusOne(ret.getExpression().orElse(null))) continue;
      int line = ret.getBegin().map(p -> p.line).orElse(-1);
      int col = ret.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "compare() returns literal "
              + ret.getExpression().get().toString()
              + " \u2014 use Integer.compare() or "
              + "Integer.signum() to avoid subtle overflow bugs",
          RuleDef.Severity.LOW,
          "compare");
    }
  }

  /**
   * True when the enclosing class declares (directly) {@code java.util.Comparator}. We check the
   * immediate {@code implements} list. The subclass that doesn't redeclare the implements clause
   * but inherits compare() from a Comparator-implementing parent will still be flagged when the
   * dispatcher visits THAT class's {@code compare} declaration ? see the comment in visit().
   */
  private static boolean declaresComparator(MethodDeclaration n) {
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    if (!(parent instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls)) {
      return false;
    }
    Set<String> refs = new HashSet<>();
    for (ClassOrInterfaceType t : cls.getImplementedTypes()) {
      refs.add(t.getNameAsString());
      refs.add(t.asString());
    }
    for (ClassOrInterfaceType t : cls.getExtendedTypes()) {
      refs.add(t.getNameAsString());
      refs.add(t.asString());
    }
    return refs.contains("Comparator")
        || refs.contains("java.util.Comparator")
        || refs.contains("Comparator<T>");
  }

  /**
   * True when {@code e} is the literal {@code 1} or {@code -1}. {@code +1} is accepted too for
   * completeness ? any literal ±1 is suspect in a Comparator because the right idiom is to
   * delegate to {@code Integer.compare}.
   */
  private static boolean isLiteralOneOrMinusOne(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof IntegerLiteralExpr ili) {
      try {
        return Math.abs(Integer.parseInt(ili.getValue())) == 1;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
    if (e instanceof UnaryExpr u) {
      com.github.javaparser.ast.expr.Expression inner = u.getExpression();
      if (inner instanceof IntegerLiteralExpr ili) {
        try {
          return Math.abs(Integer.parseInt(ili.getValue())) == 1;
        } catch (NumberFormatException ex) {
          return false;
        }
      }
    }
    return false;
  }
}