/*
 * Coverity <code>UNINIT_NONNULL</code> (CWE-457) -- reports fields that are annotated as non-null but
 * are not assigned a non-null value by <em>every</em> constructor.
 *
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;
import org.plumb.style.annotations.AnnotationExtractor;
import org.plumb.style.annotations.AnnotationFacts;

public final class UninitNonnullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-uninit-nonnull", "UNINIT_NONNULL"),
          "@NonNull / @Nonnull field not assigned a non-null value in every constructor",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-457"),
          "org.plumb.style.UninitNonnullCheck",
          true,
          List.of("ast-quality-uninit-nonnull", "uninit-nonnull"));

  @Override
  public String name() {
    return "uninit-nonnull";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration cls, StyleContext ctx) {
    List<FieldDeclaration> nonnullFields =
        AnnotationExtractor.fieldsAnnotatedWith(cls, AnnotationFacts.NONNULL);
    if (nonnullFields.isEmpty()) return;
    List<ConstructorDeclaration> ctors = cls.getConstructors();
    if (ctors.isEmpty()) return;
    for (FieldDeclaration f : nonnullFields) {
      for (VariableDeclarator var : f.getVariables()) {
        String fieldName = var.getNameAsString();
        // Inline initialiser covers all constructors.
        if (hasInlineInitializer(var)) continue;
        for (ConstructorDeclaration ctor : ctors) {
          if (ctorIsExcludedFromCheck(ctor)) continue;
          if (assignsNonnull(ctor.getBody(), fieldName)) continue;
          int line = f.getBegin().map(p -> p.line).orElse(-1);
          int col = f.getBegin().map(p -> p.column).orElse(-1);
          String ctorSig = ctor.getDeclarationAsString();
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "UNINIT_NONNULL: field '"
                  + fieldName
                  + "' is annotated @NonNull / @Nonnull but is not "
                  + "assigned a non-null value in constructor '"
                  + ctorSig
                  + "'",
              RuleDef.Severity.MEDIUM,
              fieldName + " in " + ctorSig);
        }
      }
    }
  }

  private static boolean ctorIsExcludedFromCheck(ConstructorDeclaration ctor) {
    return ctor.isPrivate();
  }

  private static boolean assignsNonnull(BlockStmt body, String fieldName) {
    if (body == null) return false;
    for (Statement s : body.getStatements()) {
      if (s instanceof ExpressionStmt) {
        Expression e = ((ExpressionStmt) s).getExpression();
        if (assignmentToField(e, fieldName, true)) return true;
      }
    }
    return false;
  }

  private static boolean hasInlineInitializer(VariableDeclarator var) {
    return var.getInitializer().isPresent() && !isNullLiteral(var.getInitializer().get());
  }

  private static boolean assignmentToField(Expression e, String fieldName, boolean strictNotNull) {
    if (e == null) return false;
    if (e instanceof AssignExpr) {
      AssignExpr ae = (AssignExpr) e;
      if (!targetsField(ae.getTarget(), fieldName)) return false;
      if (!strictNotNull) return true;
      return !isNullLiteral(ae.getValue());
    }
    if (e instanceof UnaryExpr) {
      UnaryExpr ue = (UnaryExpr) e;
      if (targetsField(ue.getExpression(), fieldName)) return true;
    }
    return false;
  }

  private static boolean targetsField(Expression target, String fieldName) {
    if (target == null) return false;
    if (target instanceof NameExpr) {
      return fieldName.equals(((NameExpr) target).getNameAsString());
    }
    if (target instanceof FieldAccessExpr) {
      FieldAccessExpr fa = (FieldAccessExpr) target;
      return fieldName.equals(fa.getNameAsString()) && fa.getScope().toString().equals("this");
    }
    return false;
  }

  private static boolean isNullLiteral(Expression e) {
    return e instanceof NullLiteralExpr;
  }

  /** Public utility for tests. */
  public static Set<String> uninitFields(ClassOrInterfaceDeclaration cls) {
    Set<String> out = new LinkedHashSet<>();
    List<FieldDeclaration> nonnullFields =
        AnnotationExtractor.fieldsAnnotatedWith(cls, AnnotationFacts.NONNULL);
    if (nonnullFields.isEmpty()) return out;
    List<ConstructorDeclaration> ctors = cls.getConstructors();
    if (ctors.isEmpty()) return out;
    for (FieldDeclaration f : nonnullFields) {
      for (VariableDeclarator var : f.getVariables()) {
        String name = var.getNameAsString();
        if (hasInlineInitializer(var)) continue;
        for (ConstructorDeclaration ctor : ctors) {
          if (ctor.isPrivate()) continue;
          if (!assignsNonnull(ctor.getBody(), name)) {
            out.add(name);
            break;
          }
        }
      }
    }
    return out;
  }
}
