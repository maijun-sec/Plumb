/*
 * Coverity <code>SINGLETON_RACE</code> (CWE-366) -- fires on
 * non-synchronized read-modify-write of an instance / static
 * field in a singleton class (servlet, Spring controller).
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ClassOrInterfaceDeclaration)} (1 walk) +
 * per-class per-field per-method
 * {@code findRace(body, field)} that did 2-3 findAll
 * (UnaryExpr, AssignExpr, containsName's NameExpr) → up to 4
 * walks per field per method per singleton class. The new
 * implementation uses
 * {@code visit(ClassOrInterfaceDeclaration)} dispatched by
 * {@code AstDispatcher} (1 walk top-level) and a single
 * {@code body.accept(VoidVisitorAdapter)} with instanceof
 * dispatch in {@link #findRace} (1 walk per method per field).
 * Per-field work goes from ~3 walks to 1 walk.</p>
 */
package org.plumb.style.concurrency;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;
import org.plumb.style.annotations.AnnotationExtractor;
import org.plumb.style.config.CheckerConfig;

public final class SingletonRaceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-concurrency-singleton-race", "SINGLETON_RACE"),
          "Read-modify-write on a non-volatile field in a singleton class without synchronization",
          RuleDef.Severity.HIGH,
          RuleDef.Type.MULTITHREADING,
          List.of("CWE-366"),
          "org.plumb.style.SingletonRaceCheck",
          true,
          List.of("ast-concurrency-singleton-race", "singleton-race"));

  // M7: controller-annotation and servlet-superclass tables
  // now live in CheckerConfig — operators can extend them in
  // one place rather than patching this checker.

  @Override
  public String name() {
    return "singleton-race";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration cls, StyleContext ctx) {
    if (!isSingleton(cls)) return;
    java.util.Set<String> fields = new HashSet<>();
    for (FieldDeclaration f : cls.getFields()) {
      if (f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) {
        continue;
      }
      if (f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.VOLATILE)) {
        continue;
      }
      for (VariableDeclarator v : f.getVariables()) {
        fields.add(v.getNameAsString());
      }
    }
    if (fields.isEmpty()) return;
    for (MethodDeclaration md : cls.getMethods()) {
      if (isSynchronized(md)) continue;
      BlockStmt body = md.getBody().orElse(null);
      if (body == null) continue;
      for (String field : fields) {
        String site = findRace(body, field);
        if (site == null) continue;
        int line = md.getBegin().map(p -> p.line).orElse(-1);
        int col = md.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "SINGLETON_RACE: field '"
                + field
                + "' in singleton class '"
                + cls.getNameAsString()
                + "' is read-modify-written in method '"
                + md.getNameAsString()
                + "' without synchronization or volatile — concurrent requests can lose updates",
            RuleDef.Severity.HIGH,
            field + " in " + md.getNameAsString());
      }
    }
  }

  private static boolean isSingleton(ClassOrInterfaceDeclaration cls) {
    if (AnnotationExtractor.hasAny(cls, CheckerConfig.controllerAnnotations())) return true;
    for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
      String name = ext.getNameAsString();
      for (String s : CheckerConfig.servletSuperclasses()) {
        if (s.endsWith("." + name)) return true;
        if (s.equals(name)) return true;
      }
    }
    return false;
  }

  private static boolean isSynchronized(MethodDeclaration md) {
    return md.getModifiers().stream()
        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.SYNCHRONIZED);
  }

  private static String findRace(BlockStmt body, String field) {
    final String[] result = {null};
    body.walk(
        com.github.javaparser.ast.Node.class,
        n -> {
          if (result[0] != null) return;
          if (isInsideSync(n)) return;
          if (n instanceof UnaryExpr ue && ue.getExpression() instanceof NameExpr) {
            NameExpr ne = (NameExpr) ue.getExpression();
            if (field.equals(ne.getNameAsString())) {
              result[0] = "++/-- on " + field;
            }
          } else if (n instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr ne) {
            if (field.equals(ne.getNameAsString())) {
              if (ae.getOperator() != AssignExpr.Operator.ASSIGN) {
                result[0] = "compound assign on " + field;
              } else if (containsName(ae.getValue(), field)) {
                result[0] = "read-modify-write on " + field;
              }
            }
          }
        });
    return result[0];
  }

  private static boolean containsName(Expression e, String name) {
    if (e == null) return false;
    for (NameExpr ne : e.findAll(NameExpr.class)) {
      if (name.equals(ne.getNameAsString())) return true;
    }
    return false;
  }

  private static boolean isInsideSync(com.github.javaparser.ast.Node n) {
    com.github.javaparser.ast.Node cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof SynchronizedStmt) return true;
      if (cur instanceof BlockStmt) {
        cur = cur.getParentNode().orElse(null);
        continue;
      }
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }
}
