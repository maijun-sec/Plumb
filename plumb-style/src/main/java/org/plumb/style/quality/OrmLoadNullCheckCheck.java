/*
 * Coverity <code>ORM_LOAD_NULL_CHECK</code> (CWE-252).
 *
 * <p>JPA "load" operations never return {@code null} ? they return a non-null proxy object. The
 * proxy can only be used to read back the ID it was created with; any other access throws an
 * exception. A {@code null} check on the result is therefore dead code.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodDeclaration.class)} before. The inner per-statement
 * inspection stays as a local loop because we compare adjacent statements (assign + null check).
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class OrmLoadNullCheckCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-orm-load-null-check", "ORM_LOAD_NULL_CHECK"),
          "Null check on the result of a JPA load method (load returns a non-null proxy)",
          RuleDef.Severity.LOW,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-252"),
          "org.plumb.style.OrmLoadNullCheckCheck",
          true,
          List.of("ast-quality-orm-load-null-check", "orm-load-null-check"));

  private static final Set<String> LOAD_METHODS =
      new HashSet<>(List.of("load", "getReference", "loadById", "getOne"));

  @Override
  public String name() {
    return "orm-load-null-check";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration md, StyleContext ctx) {
    BlockStmt body = md.getBody().orElse(null);
    if (body == null) return;
    List<Statement> stmts = body.getStatements();
    for (int i = 0; i < stmts.size(); i++) {
      String loadedVar = loadAssignmentTo(stmts.get(i));
      if (loadedVar == null) continue;
      if (i + 1 >= stmts.size()) continue;
      if (!isNullCheckOn(stmts.get(i + 1), loadedVar)) continue;
      int line = stmts.get(i + 1).getBegin().map(p -> p.line).orElse(-1);
      int col = stmts.get(i + 1).getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "ORM_LOAD_NULL_CHECK: variable '"
              + loadedVar
              + "' is the result of a JPA load method"
              + " (load / getReference / getOne) which never returns null"
              + " — this null check is dead code; the proxy will throw on first use",
          RuleDef.Severity.LOW,
          loadedVar);
    }
  }

  private static String loadAssignmentTo(Statement s) {
    if (s instanceof ExpressionStmt) {
      ExpressionStmt es = (ExpressionStmt) s;
      if (es.getExpression() instanceof AssignExpr) {
        AssignExpr ae = (AssignExpr) es.getExpression();
        if (ae.getTarget() instanceof com.github.javaparser.ast.expr.NameExpr) {
          String name =
              ((com.github.javaparser.ast.expr.NameExpr) ae.getTarget()).getNameAsString();
          if (isLoadCall(ae.getValue())) return name;
        }
      }
    }
    if (s instanceof ExpressionStmt) {
      ExpressionStmt es = (ExpressionStmt) s;
      if (es.getExpression() instanceof VariableDeclarationExpr) {
        VariableDeclarationExpr vde = (VariableDeclarationExpr) es.getExpression();
        if (vde.getVariables().size() == 1) {
          VariableDeclarator vd = vde.getVariables().get(0);
          if (vd.getInitializer().isPresent() && isLoadCall(vd.getInitializer().get())) {
            return vd.getNameAsString();
          }
        }
      }
    }
    return null;
  }

  private static boolean isLoadCall(com.github.javaparser.ast.expr.Expression e) {
    if (!(e instanceof MethodCallExpr)) return false;
    MethodCallExpr m = (MethodCallExpr) e;
    return LOAD_METHODS.contains(m.getNameAsString());
  }

  private static boolean isNullCheckOn(Statement s, String var) {
    if (!(s instanceof IfStmt)) return false;
    IfStmt ifs = (IfStmt) s;
    if (!(ifs.getCondition() instanceof BinaryExpr)) return false;
    BinaryExpr be = (BinaryExpr) ifs.getCondition();
    if (be.getOperator() != BinaryExpr.Operator.EQUALS
        && be.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
      return false;
    }
    boolean leftIsVar = be.getLeft().toString().equals(var);
    boolean rightIsVar = be.getRight().toString().equals(var);
    boolean leftIsNull = be.getLeft() instanceof NullLiteralExpr;
    boolean rightIsNull = be.getRight() instanceof NullLiteralExpr;
    return (leftIsVar && rightIsNull) || (rightIsVar && leftIsNull);
  }
}
