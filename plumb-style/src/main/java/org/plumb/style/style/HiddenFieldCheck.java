package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Local variable should not shadow field. */
public final class HiddenFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-hiddenfield", "HIDDENFIELD"),
          "Local variable should not shadow field",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.HiddenFieldCheck",
          true,
          List.of("ast-style-hidden-field", "hiddenfield")
      );

  @Override
  public String name() {
    return "hiddenfield";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    for (MethodDeclaration m : c.getMethods()) {
      for (com.github.javaparser.ast.body.Parameter p : m.getParameters()) {
        String pname = p.getNameAsString();
        for (FieldDeclaration f : c.getFields()) {
          for (VariableDeclarator v : f.getVariables()) {
            if (v.getNameAsString().equals(pname)) {
              int line = p.getBegin().map(p2 -> p2.line).orElse(-1);
              int col = p.getBegin().map(p2 -> p2.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Local variable '" + pname + "' shadows field",
                  RuleDef.Severity.LOW,
                  pname);
            }
          }
        }
      }
    }
  }
}
