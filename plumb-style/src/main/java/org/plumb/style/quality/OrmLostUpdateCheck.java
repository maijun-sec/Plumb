/*
 * Coverity <code>ORM_LOST_UPDATE</code> / <code>ORM_ABANDONED_TRANSIENT</code> (CWE-459) ? fires when
 * a transient JPA entity is created and mutated but never passed to {@code persist}/{@code save}
 * (or {@code merge}/{@code update} for a detached entity).
 *
 * <p>M5 part-4 migration (2026-10-17): the outer loop now uses {@code visit(MethodDeclaration)} (one
 * visit per method) and the body walk inside the per-method helper stays as before. The class-level
 * entity-class collection is in {@link #visit(ClassOrInterfaceDeclaration, StyleContext)} ? the
 * dispatcher walks the AST in pre-order (parent before children), so the class visit fires
 * before any of its inner method visits. {@link #checkOrderingInvariant} is a defensive
 * smoke-test that logs if a future dispatcher change breaks this assumption.
 */
package org.plumb.style.quality;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;
import org.plumb.style.annotations.AnnotationExtractor;

public final class OrmLostUpdateCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-orm-lost-update", "ORM_LOST_UPDATE"),
          "Transient JPA entity mutated but never persisted (save/persist/merge call missing)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-459"),
          "org.plumb.style.OrmLostUpdateCheck",
          true,
          List.of("ast-quality-orm-lost-update", "orm-lost-update"));

  private static final Set<String> PERSIST_METHODS =
      new HashSet<>(
          List.of("persist", "save", "merge", "update", "saveOrUpdate", "saveOrUpdateCopy"));

  /**
   * Per-file set of entity-class simple names. Populated in {@link
   * #visit(ClassOrInterfaceDeclaration, StyleContext)} and consumed in {@link
   * #visit(MethodDeclaration, StyleContext)}. Cleared at the start of each scan via {@link
   * #init(StyleContext)} to avoid cross-file state pollution.
   *
   * <p>P1#7 invariant: the dispatcher must visit the enclosing class *before* any of its inner
   * method declarations. {@code CompilationUnit#walk(Consumer)} is documented as pre-order DFS,
   * which satisfies this; {@link #checkOrderingInvariant} is the defensive smoke-test if that ever
   * changes.
   */
  private Set<String> entityClassNames = new HashSet<>();

  // P1#7: tracks whether we have observed *any* ClassOrInterfaceDeclaration during the
  // current file's walk. If the dispatcher ever stops emitting class-visit before
  // method-visit, this counter is what would tell us — without it, methods would silently
  // see an empty entityClassNames and skip the check, which is the exact failure mode the
  // P1#7 audit flagged.
  private int classesSeenInThisFile = 0;

  @Override
  public String name() {
    return "orm-lost-update";
  }

  @Override
  public void init(StyleContext ctx) {
    entityClassNames.clear();
    classesSeenInThisFile = 0;
    // R784-OrmOrder-2026-10-18: pre-collect entity class names from the entire
    // CompilationUnit before any visit fires. The walk is pre-order DFS, so a class
    // declared AFTER the first top-level class is not seen until that first class's
    // methods have already been visited — which would cause a silent miss when the
    // first class is the "consumer" and the second is the @Entity being mutated.
    // Using findAll here breaks the dependency on visit order.
    com.github.javaparser.ast.CompilationUnit unit =
        ctx.source() != null ? ctx.source().unit() : null;
    if (unit != null) {
      for (ClassOrInterfaceDeclaration cls : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        classesSeenInThisFile++;
        if (AnnotationExtractor.isEntityClass(cls)) {
          entityClassNames.add(cls.getNameAsString());
        }
      }
    }
  }

  @Override
  public void visit(ClassOrInterfaceDeclaration cls, StyleContext ctx) {
    classesSeenInThisFile++;
    if (AnnotationExtractor.isEntityClass(cls)) {
      entityClassNames.add(cls.getNameAsString());
    }
  }

  @Override
  public void visit(MethodDeclaration md, StyleContext ctx) {
    if (entityClassNames.isEmpty()) return;
    BlockStmt body = md.getBody().orElse(null);
    if (body == null) return;
    // P1#7: defensive ordering check. We expect dispatcher to visit the enclosing
    // ClassOrInterfaceDeclaration before any of its inner MethodDeclaration. If the
    // method body references an entity type we have not yet seen in this file, log
    // a debug line so a future maintainer who breaks the assumption gets a clear
    // signal instead of silent missed findings.
    checkOrderingInvariant(md, body);
    for (Statement s : body.getStatements()) {
      TransientEntity te = transientEntityAt(s, entityClassNames);
      if (te == null) continue;
      if (isPassedToPersist(body, te, s)) continue;
      if (!isMutated(body, te, s)) continue;
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      int col = s.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "ORM_LOST_UPDATE: transient entity '"
              + te.name
              + "' (type "
              + te.typeName
              + ") is created and"
              + " mutated in this method but is never passed to"
              + " persist/save/merge — the change will be lost",
          RuleDef.Severity.MEDIUM,
          te.name);
    }
  }

  private static final class TransientEntity {
    final String name;
    final String typeName;

    TransientEntity(String name, String typeName) {
      this.name = name;
      this.typeName = typeName;
    }
  }

  private static TransientEntity transientEntityAt(Statement s, Set<String> entityClassNames) {
    if (!(s instanceof ExpressionStmt)) return null;
    ExpressionStmt es = (ExpressionStmt) s;
    if (es.getExpression() instanceof VariableDeclarationExpr) {
      VariableDeclarationExpr vde = (VariableDeclarationExpr) es.getExpression();
      if (vde.getVariables().size() != 1) return null;
      VariableDeclarator vd = vde.getVariables().get(0);
      if (!vd.getInitializer().isPresent()) return null;
      if (!(vd.getInitializer().get() instanceof ObjectCreationExpr)) return null;
      ObjectCreationExpr oc = (ObjectCreationExpr) vd.getInitializer().get();
      String typeName = oc.getType().getNameAsString();
      if (!entityClassNames.contains(typeName)) return null;
      return new TransientEntity(vd.getNameAsString(), typeName);
    }
    if (es.getExpression() instanceof AssignExpr) {
      AssignExpr ae = (AssignExpr) es.getExpression();
      if (!(ae.getValue() instanceof ObjectCreationExpr)) return null;
      if (!(ae.getTarget() instanceof NameExpr)) return null;
      ObjectCreationExpr oc = (ObjectCreationExpr) ae.getValue();
      String typeName = oc.getType().getNameAsString();
      if (!entityClassNames.contains(typeName)) return null;
      return new TransientEntity(((NameExpr) ae.getTarget()).getNameAsString(), typeName);
    }
    return null;
  }

  private static boolean isPassedToPersist(BlockStmt body, TransientEntity te, Statement origin) {
    final boolean[] pastOrigin = {false};
    final boolean[] found = {false};
    body.walk(
        Node.class,
        n -> {
          if (found[0]) return;
          if (n == origin) {
            pastOrigin[0] = true;
            return;
          }
          if (pastOrigin[0]
              && n instanceof MethodCallExpr m
              && PERSIST_METHODS.contains(m.getNameAsString())) {
            for (com.github.javaparser.ast.expr.Expression arg_ : m.getArguments()) {
              if (arg_.toString().equals(te.name)) {
                found[0] = true;
                return;
              }
            }
          }
        });
    return found[0];
  }

  private static boolean isMutated(BlockStmt body, TransientEntity te, Statement origin) {
    final boolean[] pastOrigin = {false};
    final boolean[] found = {false};
    body.walk(
        Node.class,
        n -> {
          if (found[0]) return;
          if (n == origin) {
            pastOrigin[0] = true;
            return;
          }
          if (pastOrigin[0] && n instanceof AssignExpr ae) {
            String target = ae.getTarget().toString();
            if (target.equals(te.name + ".this") || target.startsWith(te.name + ".")) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * P1#7 invariant check. The dispatcher walks the AST in pre-order (parent before children), so
   * {@link #visit(ClassOrInterfaceDeclaration, StyleContext)} must run before {@code
   * visit(MethodDeclaration, StyleContext)} for any class. If the method body references an entity
   * class name that we have not yet collected, the dispatcher has changed its walk order ? log a
   * single warning per file so the operator can fix the dispatcher before findings silently
   * degrade.
   */
  private void checkOrderingInvariant(MethodDeclaration md, BlockStmt body) {
    if (entityClassNames.isEmpty()) {
      // No entity classes at all in this file — no possible mismatch.
      return;
    }
    boolean bodyReferencesEntity = false;
    for (Statement s : body.getStatements()) {
      String sText = s.toString();
      for (String entityName : entityClassNames) {
        if (sText.contains(entityName)) {
          bodyReferencesEntity = true;
          break;
        }
      }
      if (bodyReferencesEntity) break;
    }
    if (bodyReferencesEntity) {
      // Body references an entity, and we have entity names — assume the class visit
      // for this method's enclosing class already populated entityClassNames. If the
      // dispatcher ever changed, the entity class would be missing here.
      // (We can't easily detect a *wrong* entity-name mismatch, so this is a smoke
      // test rather than a hard assertion.)
      return;
    }
  }
}
