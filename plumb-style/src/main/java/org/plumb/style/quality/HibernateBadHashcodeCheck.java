/*
 * Coverity <code>HIBERNATE_BAD_HASHCODE</code> (CWE-579) ? fires
 * when a JPA entity's {@code hashCode()} or {@code equals()}
 * method depends on a field annotated {@code @Id} (or
 * {@code @GeneratedValue}). The key is not assigned until
 * after the entity is persisted, so using it in
 * {@code hashCode()} / {@code equals()} makes the entity behave
 * inconsistently in Java collections before it is saved.
 *
 * <p>Reference: Coverity 2023.6.0 Checker Reference, section
 * <em>HIBERNATE_BAD_HASHCODE</em>.</p>
 *
 * <p><b>Detection strategy.</b> For each class annotated
 * {@code @Entity} (or carrying a JPA-equivalent annotation),
 * find the {@code hashCode()} / {@code equals(Object)} methods
 * and inspect their bodies. If the method body references a
 * field annotated {@code @Id} or {@code @GeneratedValue}
 * anywhere ? assignment, comparison, return, method-call
 * argument ? fire.</p>
 *
 * <p><b>R-817 (2026-10-14): pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ClassOrInterfaceDeclaration.class)} (1 walk).
 * The new implementation uses {@code visit(ClassOrInterfaceDeclaration)}
 * dispatched by {@code AstDispatcher} in a single walk ? 0
 * redundant top-level walks. The two sub-walks per matched
 * class ({@code body.findAll(FieldAccessExpr)} +
 * {@code body.findAll(NameExpr)}) are unchanged because they
 * are bounded by the method body, not the whole AST.</p>
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;
import org.plumb.style.annotations.AnnotationExtractor;
import org.plumb.style.annotations.AnnotationFacts;

public final class HibernateBadHashcodeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-hibernate-bad-hashcode", "HIBERNATE_BAD_HASHCODE"),
          "JPA entity hashCode()/equals() depends on @Id / @GeneratedValue field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-579"),
          "org.plumb.style.HibernateBadHashcodeCheck",
          true,
          List.of("ast-quality-hibernate-bad-hashcode", "hibernate-bad-hashcode")
      );

  @Override
  public String name() {
    return "hibernate-bad-hashcode";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration cls, StyleContext ctx) {
    if (!AnnotationExtractor.isEntityClass(cls)) return;
    // The set of @Id / @GeneratedValue field names.
    Set<String> idFields = new HashSet<>();
    for (FieldDeclaration f : cls.getFields()) {
      if (AnnotationExtractor.hasAny(f, AnnotationFacts.JPA_ID)
          || AnnotationExtractor.hasAny(f, AnnotationFacts.JPA_GENERATED_VALUE)) {
        for (VariableDeclarator v : f.getVariables()) {
          idFields.add(v.getNameAsString());
        }
      }
    }
    if (idFields.isEmpty()) return;
    // Walk hashCode() and equals(Object).
    for (MethodDeclaration md : cls.getMethods()) {
      String name = md.getNameAsString();
      if (!"hashCode".equals(name) && !"equals".equals(name)) continue;
      BlockStmt body = md.getBody().orElse(null);
      if (body == null) continue;
      String hit = bodyUsesIdField(body, idFields);
      if (hit == null) continue;
      int line = md.getBegin().map(p -> p.line).orElse(-1);
      int col = md.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "HIBERNATE_BAD_HASHCODE: "
              + name
              + "() in entity '"
              + cls.getNameAsString()
              + "' references @Id field '"
              + hit
              + "' — the ID is not assigned until after the entity"
              + " is persisted, so hashCode/equals will be inconsistent",
          RuleDef.Severity.MEDIUM,
          name + " / " + hit);
    }
  }

  /** Returns the first @Id field name referenced anywhere in {@code body}, or null if none. */
  private static String bodyUsesIdField(BlockStmt body, Set<String> idFields) {
    // 1. Field access: this.id, this.id == o.id, etc.
    for (FieldAccessExpr fa : body.findAll(FieldAccessExpr.class)) {
      // match "this.id" or "id" (within the same class)
      if (idFields.contains(fa.getNameAsString())) return fa.getNameAsString();
    }
    // 2. Simple name reference: "id" without "this." — only
    // matches when the field is in the same class. We use
    // NameExpr for that.
    for (NameExpr ne : body.findAll(NameExpr.class)) {
      String n = ne.getNameAsString();
      if (idFields.contains(n)) {
        // The NameExpr must be a real field reference,
        // not a local variable. Walk up the AST to
        // confirm there's no local declaration.
        if (!isLocalVariable(ne, n)) return n;
      }
    }
    return null;
  }

  /**
   * True if {@code ne} is a reference to a local variable (declared inside the same method). Field
   * references inside a method body always have a name that resolves to a field, not a local.
   */
  private static boolean isLocalVariable(NameExpr ne, String name) {
    // Walk up to the enclosing BlockStmt / method body and
    // check the local declarations.
    com.github.javaparser.ast.Node cur = ne.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof com.github.javaparser.ast.stmt.BlockStmt) {
        BlockStmt block = (com.github.javaparser.ast.stmt.BlockStmt) cur;
        for (com.github.javaparser.ast.stmt.Statement s : block.getStatements()) {
          if (s instanceof com.github.javaparser.ast.stmt.ExpressionStmt) {
            com.github.javaparser.ast.expr.Expression e =
                ((com.github.javaparser.ast.stmt.ExpressionStmt) s).getExpression();
            if (e instanceof VariableDeclarationExpr) {
              for (VariableDeclarator v : ((VariableDeclarationExpr) e).getVariables()) {
                if (v.getNameAsString().equals(name)) return true;
              }
            }
          }
        }
      }
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }
}
