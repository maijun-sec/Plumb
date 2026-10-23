package org.plumb.style.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Optional;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** String concatenation in a loop should use StringBuilder. */
public final class StringBuilderConcatenationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-builder-concatenation-in-loop", "STRING_BUILDER_CONCATENATION_IN_LOOP"),
          "String concatenation in a loop should use StringBuilder",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.StringBuilderConcatenationCheck",
          true,
          List.of("ast-performance-string-builder-concatenation-in-loop", "string-builder-concatenation-in-loop")
      );

  @Override
  public String name() {
    return "string-builder-concatenation-in-loop";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // First pass: collect local variable declarations
    // and their declared types, so we can
    // distinguish a `String s` from an `int s`.
    final java.util.Map<String, String> declaredTypes = new java.util.HashMap<>();
    n.walk(
        com.github.javaparser.ast.expr.VariableDeclarationExpr.class,
        vde -> {
          final String type = vde.getElementType().asString();
          for (com.github.javaparser.ast.body.VariableDeclarator v : vde.getVariables()) {
            declaredTypes.put(v.getNameAsString(), type);
          }
        });
    // Walk every statement; if it's a loop, walk its
    // body for AssignExpr with operator + or += on a
    // String-typed variable. whenever
    // a String variable is reassigned via `+` inside
    // a loop, regardless of the RHS type.
    n.walk(
        Statement.class,
        s -> {
          if (!isLoop(s)) return;
          // Form A: `s +=...` : AssignExpr operator is PLUS (for +=)
          s.walk(
              AssignExpr.class,
              ae -> {
                final AssignExpr.Operator op = ae.getOperator();
                if (op != AssignExpr.Operator.PLUS) return;
                final Expression target = ae.getTarget();
                if (!(target instanceof NameExpr)) return;
                final String tname = ((NameExpr) target).getNameAsString();
                if (!isLikelyStringType(declaredTypes.get(tname), tname)) return;
                ctx.report(
                    ae.getBegin().map(p -> p.line).orElse(-1),
                    ae.getBegin().map(p -> p.column).orElse(-1),
                    DEFAULT_RULE,
                    "String concatenation in a loop should use StringBuilder",
                    RuleDef.Severity.MEDIUM,
                    tname);
              });
          // Form B: `s = s +...` : the outer AssignExpr
          // has operator =, but the inner BinaryExpr is
          // +. Fire if the outer LHS equals the inner
          // PLUS LHS.
          s.walk(
              BinaryExpr.class,
              be -> {
                if (be.getOperator() != BinaryExpr.Operator.PLUS) return;
                if (!(be.getLeft() instanceof NameExpr)) return;
                final String lname = ((NameExpr) be.getLeft()).getNameAsString();
                if (!isLikelyStringType(declaredTypes.get(lname), lname)) return;
                final Optional<com.github.javaparser.ast.Node> par = be.getParentNode();
                if (par.isEmpty()) return;
                if (!(par.get() instanceof AssignExpr ae)) return;
                if (ae.getOperator() != AssignExpr.Operator.ASSIGN) return;
                if (!(ae.getTarget() instanceof NameExpr)) return;
                final String target = ((NameExpr) ae.getTarget()).getNameAsString();
                if (!target.equals(lname)) return;
                ctx.report(
                    be.getBegin().map(p -> p.line).orElse(-1),
                    be.getBegin().map(p -> p.column).orElse(-1),
                    DEFAULT_RULE,
                    "String concatenation in a loop should use StringBuilder",
                    RuleDef.Severity.MEDIUM,
                    lname);
              });
        });
  }

  /**
   * Returns true if the variable is plausibly a String, either by its declared type or by its name.
   * We require the declared type to be a String-like (type == null falls back to name heuristic
   * only; this happens for loop induction variables and untyped params).
   */
  private static boolean isLikelyStringType(String declaredType, String name) {
    if (declaredType != null) {
      return "String".equals(declaredType)
          || "java.lang.String".equals(declaredType)
          || "CharSequence".equals(declaredType)
          || "StringBuilder".equals(declaredType)
          || "StringBuffer".equals(declaredType);
    }
    // Fallback: name-based heuristic for variables
    // that aren't locally declared (e.g. method
    // parameters from an enclosing scope).
    if (name == null || name.isEmpty()) return false;
    String lower = name.toLowerCase();
    if (lower.contains("str")) return true;
    if (lower.contains("name")) return true;
    if (lower.contains("text")) return true;
    if (lower.contains("line")) return true;
    if (lower.contains("msg")) return true;
    if (lower.contains("key")) return true;
    if (name.length() == 1 && (name.equals("s") || name.equals("S"))) return true;
    return false;
  }

  private static boolean isLoop(Statement s) {
    return s.isForStmt() || s.isWhileStmt() || s.isDoStmt() || s.isForEachStmt();
  }
}
