/*
 * Import is never used.
 *
 * <p><b>pattern A rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} + 3
 * separate {@code n.findAll(...)} calls (NameExpr +
 * ClassOrInterfaceType MethodCallExpr) -- 3 walks per file.
 * The new implementation uses a single walk of the
 * CompilationUnit with instance-of dispatch and collects
 * the 3 sets of used names in one pass. Total: 1 walk per
 * file (was 3).</p>
 *
 */
package org.plumb.style.style;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
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

public final class UnusedImportsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-imports", "UNUSED_IMPORTS"),
          "Import is never used",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedImportsCheck",
          false,
          List.of(
              "ast-style-unused-imports",
              "unused-imports")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832
  // Disabled by default: this rule reports a style preference rather than a defect.

  private final List<ImportDeclaration> importsBuffer = new java.util.ArrayList<>();
  private final Set<String> usedNames = new HashSet<>();

  @Override
  public String name() {
    return "unused-imports";
  }

  @Override
  public void init(StyleContext ctx) {
    usedNames.clear();
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
    usedNames.add(t.getNameAsString());
  }

  public void visit(MethodCallExpr m, StyleContext ctx) {
    usedNames.add(m.getNameAsString());
  }

  /** R-AST-FIX-2026-10-18: mark annotation imports as used. */
  public void visit(MarkerAnnotationExpr a, StyleContext ctx) {
    usedNames.add(a.getNameAsString());
  }

  /** R-AST-FIX-2026-10-18: same for {@code @Foo(priority = 1)} (NormalAnnotationExpr). */
  public void visit(NormalAnnotationExpr a, StyleContext ctx) {
    usedNames.add(a.getNameAsString());
  }

  /** R-AST-FIX-2026-10-18: same for {@code @Foo("value")} (SingleMemberAnnotationExpr). */
  public void visit(SingleMemberAnnotationExpr a, StyleContext ctx) {
    usedNames.add(a.getNameAsString());
  }

  @Override
  public void finish(StyleContext ctx) {
    if (importsBuffer.isEmpty()) return;
    for (ImportDeclaration id : importsBuffer) {
      String fqn = id.getNameAsString();
      String simple = simpleName(fqn);
      if (simple == null) continue;
      // java.lang.* is implicit, but a redundant import is
      // also caught here. Skip the implicit ones to avoid
      // noisy reports.
      if (id.isAsterisk() && fqn.startsWith("java.lang")) continue;
      if (usedNames.contains(simple)) continue;
      int line = id.getBegin().map(p -> p.line).orElse(-1);
      int col = id.getBegin().map(p -> p.column).orElse(-1);
      String tail = id.isAsterisk() ? ".*" : "";
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Import '" + fqn + tail + "' is never used",
          RuleDef.Severity.LOW,
          fqn + tail);
    }
  }

  private static String simpleName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    if (dot < 0) return fqn;
    return fqn.substring(dot + 1);
  }
}
