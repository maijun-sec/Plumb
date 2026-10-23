package org.plumb.style.quality;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Coverity <code>ORM_UNNECESSARY_GET</code> (CWE-252).
 *
 * <p>When the result of a JPA {@code get} / {@code find} call is used only as a reference (passed
 * as an argument, stored in a field, or has only {@code getId()} called on it), the database hit is
 * unnecessary ? a {@code load} (which returns a proxy without going to the database) would suffice.
 *
 * <p>Reference: Coverity 2023.6.0 Checker Reference, section <em>ORM_UNNECESSARY_GET</em>.
 *
 * <p><b>AST-level approximation.</b> The Coverity check uses data flow to track every use of the
 * variable. Here we do a conservative per-method scan: for each variable whose initializer is a
 * {@code get} / {@code find} call, we collect every later reference inside the same method. If
 * <em>all</em> references are either "argument to another call" or "receiver of {@code getId()}" or
 * "target of an assignment to a field", we fire. Any use that accesses another field or method on
 * the entity suppresses the finding.
 *
 * <p>Pattern D R-818: per-method visit. For each variable assigned from a get/find we run ONE local
 * body walk with instance-of dispatch (replaces N_stmts × {@code s.findAll(NameExpr.class)} walks).
 */
public final class OrmUnnecessaryGetCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-orm-unnecessary-get", "ORM_UNNECESSARY_GET"),
          "JPA get/find() result is used only as a reference; consider Session.load() to avoid the"
              + " DB hit",
          RuleDef.Severity.LOW,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-252"),
          "org.plumb.style.OrmUnnecessaryGetCheck",
          true,
          List.of("ast-quality-orm-unnecessary-get", "orm-unnecessary-get")
      );

  private static final Set<String> GET_METHODS =
      new HashSet<>(List.of("get", "find", "findById", "getById", "read"));

  @Override
  public String name() {
    return "orm-unnecessary-get";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration md, StyleContext ctx) {
    BlockStmt body = md.getBody().orElse(null);
    if (body == null) return;
    // For each variable assigned from a get/find, check
    // every later reference in the method.
    for (Statement s : body.getStatements()) {
      String var = getTarget(s);
      Expression call = getCallExpr(s);
      if (var == null || call == null) continue;
      if (!isGetCall(call)) continue;
      List<NameExpr> uses = collectUses(body, var, s);
      if (uses.isEmpty()) {
        // Variable was never used — different
        // finding (unused variable), not ours.
        continue;
      }
      if (anyContentsAccess(uses, var)) continue;
      // Only reference-only uses — fire.
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      int col = s.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "ORM_UNNECESSARY_GET: variable '"
              + var
              + "' is the result of a JPA get/find call"
              + " but is used only as a reference (no field / method access)."
              + " Consider Session.load() / EntityManager.getReference() to avoid the DB hit",
          RuleDef.Severity.LOW,
          var);
    }
  }

  /**
   * Returns the variable name on the LHS of an assignment statement (either a {@code
   * VariableDeclarationExpr} or an {@code AssignExpr}), or null if the statement isn't a
   * single-variable assignment.
   */
  private static String getTarget(Statement s) {
    if (s instanceof ExpressionStmt) {
      ExpressionStmt es = (ExpressionStmt) s;
      if (es.getExpression() instanceof VariableDeclarationExpr) {
        VariableDeclarationExpr vde = (VariableDeclarationExpr) es.getExpression();
        if (vde.getVariables().size() == 1) {
          VariableDeclarator vd = vde.getVariables().get(0);
          if (vd.getInitializer().isPresent()) {
            return vd.getNameAsString();
          }
        }
      }
      if (es.getExpression() instanceof AssignExpr) {
        AssignExpr ae = (AssignExpr) es.getExpression();
        if (ae.getTarget() instanceof NameExpr) {
          return ((NameExpr) ae.getTarget()).getNameAsString();
        }
      }
    }
    return null;
  }

  /** Returns the right-hand side call expression (if any) of an assignment statement. */
  private static Expression getCallExpr(Statement s) {
    if (s instanceof ExpressionStmt) {
      ExpressionStmt es = (ExpressionStmt) s;
      if (es.getExpression() instanceof VariableDeclarationExpr) {
        VariableDeclarationExpr vde = (VariableDeclarationExpr) es.getExpression();
        if (vde.getVariables().size() == 1) {
          return vde.getVariables().get(0).getInitializer().orElse(null);
        }
      }
      if (es.getExpression() instanceof AssignExpr) {
        return ((AssignExpr) es.getExpression()).getValue();
      }
    }
    return null;
  }

  private static boolean isGetCall(Expression e) {
    if (!(e instanceof MethodCallExpr)) return false;
    return GET_METHODS.contains(((MethodCallExpr) e).getNameAsString());
  }

  /**
   * Collects every {@link NameExpr} of {@code var} appearing in a statement after {@code origin}
   * inside {@code body}. The origin statement itself is excluded so we don't count the LHS of the
   * original assignment.
   *
   * <p>R-818: single walk of body, instance-of dispatch with a {@code pastOrigin} flag toggled on
   * encountering the origin statement (replaces N_stmts × {@code s.findAll(NameExpr.class)} walks).
   */
  private static List<NameExpr> collectUses(BlockStmt body, String var, Statement origin) {
    // R-818: collect NameExpr for {@code var} only in
    // statements whose index is greater than
    // {@code origin}'s. Use a single body walk with
    // per-node enclosing-statement lookup.
    List<NameExpr> out = new ArrayList<>();
    // Lesson 1277: walk callback needs effectively-final
    // locals, so stash mutable state in 1-element arrays.
    int[] originIdx = {-1};
    List<Statement> stmts = body.getStatements();
    for (int i = 0; i < stmts.size(); i++) {
      if (stmts.get(i) == origin) {
        originIdx[0] = i;
        break;
      }
    }
    if (originIdx[0] < 0) return out;
    body.walk(
        Node.class,
        n -> {
          if (n == body || n == origin) return;
          // Find nearest enclosing statement.
          Statement enc = null;
          Node cur = n;
          while (cur != null && cur != body) {
            if (cur instanceof Statement) {
              enc = (Statement) cur;
              break;
            }
            cur = cur.getParentNode().orElse(null);
          }
          if (enc == null) return;
          // Reject if enclosing statement is at or before
          // origin in body order. Use a final local for
          // the index lookup (no extra lookup loop).
          int encIdx = indexOfStmt(stmts, enc);
          if (encIdx <= originIdx[0]) return;
          if (n instanceof NameExpr) {
            NameExpr ne = (NameExpr) n;
            if (var.equals(ne.getNameAsString())) {
              out.add(ne);
            }
          }
        });
    return out;
  }

  /**
   * Index of a given statement in the body (O(N) lookup, but invoked only for nodes past origin).
   */
  private static int indexOfStmt(List<Statement> stmts, Statement s) {
    for (int i = 0; i < stmts.size(); i++) {
      if (stmts.get(i) == s) return i;
    }
    return -1;
  }

  /**
   * True if any use of {@code var} accesses a field or non-{@code getId} method on the variable
   * (i.e. accesses the entity's contents).
   */
  private static boolean anyContentsAccess(List<NameExpr> uses, String var) {
    for (NameExpr ne : uses) {
      Node parent = ne.getParentNode().orElse(null);
      // x.foo() — accesses contents unless foo is getId.
      if (parent instanceof MethodCallExpr) {
        MethodCallExpr mc = (MethodCallExpr) parent;
        if (mc.getScope().isPresent()
            && mc.getScope().get() == ne
            && !"getId".equals(mc.getNameAsString())) {
          return true;
        }
      }
      // x.field — accesses contents.
      if (parent instanceof FieldAccessExpr) {
        FieldAccessExpr fa = (FieldAccessExpr) parent;
        if (fa.getScope() == ne) return true;
      }
    }
    return false;
  }
}
