package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** confusing class / method name (NM_CONFUSING_INSPECTION). */
public final class NmConfusingInspectionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nm-confusing", "NM_CONFUSING"),
          "confusing class / method name (NM_CONFUSING_INSPECTION)",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.NmConfusingInspectionCheck",
          true,
          List.of("ast-style-confusing-inspection", "nm-confusing"));

  @Override
  public String name() {
    return "nm-confusing";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (startsWithDigit(name)) {
      report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          ctx,
          name);
    }
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (startsWithDigit(name)) {
      report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          ctx,
          name);
    }
  }

  private static boolean startsWithDigit(String s) {
    if (s.isEmpty()) return false;
    return Character.isDigit(s.charAt(0));
  }

  private void report(int line, int col, StyleContext ctx, String name) {
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Name '" + name + "' starts with a digit : confusing",
        RuleDef.Severity.LOW,
        name);
  }
}
