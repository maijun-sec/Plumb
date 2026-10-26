/*
 * unused import -- remove it.
 *
 * <p><b>pattern A rewrite.</b> The previous
 * implementation had a {@code for (ImportDeclaration)} loop
 * with THREE {@code n.findAll(...)} calls inside it
 * (NameExpr + ClassOrInterfaceType + AnnotationExpr) -- worst
 * case N_imp × 3 walks. The new implementation hoists the
 * 3 sets out of the loop: a single walk of the
 * CompilationUnit populates 3 sets, then we check each
 * import against the pre-computed sets. Total: 1 walk per
 * file (was N_imp × 3).</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class UnusedImportCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-import", "UNUSED_IMPORT"),
          "unused import — remove it",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedImportCheck",
          false,
          List.of(
              "ast-style-unused-import",
              "unused-import")); // R-836 step_f: disabled by default (0 TP / 100% FP on Juliet

  // Disabled by default: this rule reports a style preference rather than a defect.

  /** Per-file: all import declarations for the current CU. Populated in init(). */
  private final List<ImportDeclaration> importsBuffer = new java.util.ArrayList<>();

  private final Set<String> usedNames = new HashSet<>();
  private final Set<String> usedTypes = new HashSet<>();
  private final Set<String> usedAnnotations = new HashSet<>();

  @Override
  public String name() {
    return "unused-import";
  }

  @Override
  public void init(StyleContext ctx) {
    usedNames.clear();
    usedTypes.clear();
    usedAnnotations.clear();
    importsBuffer.clear();
    com.github.javaparser.ast.CompilationUnit unit = ctx.source().unit();
    if (unit != null) {
      importsBuffer.addAll(unit.getImports());
    }
  }

  public void visit(NameExpr x, StyleContext ctx) {
    usedNames.add(x.getNameAsString());
  }

  public void visit(ClassOrInterfaceType t, StyleContext ctx) {
    usedTypes.add(t.getNameAsString());
    usedTypes.add(t.asString());
  }

  /** */
  public void visit(AnnotationExpr a, StyleContext ctx) {
    usedAnnotations.add(a.getNameAsString());
  }

  public void visit(MarkerAnnotationExpr a, StyleContext ctx) {
    usedAnnotations.add(a.getNameAsString());
  }

  public void visit(NormalAnnotationExpr a, StyleContext ctx) {
    usedAnnotations.add(a.getNameAsString());
  }

  public void visit(SingleMemberAnnotationExpr a, StyleContext ctx) {
    usedAnnotations.add(a.getNameAsString());
  }

  @Override
  public void finish(StyleContext ctx) {
    if (importsBuffer.isEmpty()) return;
    for (ImportDeclaration imp : importsBuffer) {
      String name = imp.getNameAsString();
      String simpleName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
      // Used as identifier (foo.bar / foo)
      boolean usedInCode =
          usedNames.contains(name) || usedNames.stream().anyMatch(s -> s.startsWith(name + "."));
      // Used as type (incl. fully-qualified)
      boolean usedAsType =
          usedTypes.contains(simpleName)
              || usedTypes.contains(name)
              || usedTypes.stream().anyMatch(s -> s.startsWith(name + "."));
      // Used as annotation
      boolean usedAsAnnotation =
          usedAnnotations.contains(simpleName)
              || usedAnnotations.stream().anyMatch(s -> s.endsWith("." + simpleName));
      if (usedInCode || usedAsType || usedAsAnnotation) continue;
      int line = imp.getBegin().map(b -> b.line).orElse(-1);
      int col = imp.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Unused import '" + name + "'", RuleDef.Severity.LOW, name);
    }
  }
}
