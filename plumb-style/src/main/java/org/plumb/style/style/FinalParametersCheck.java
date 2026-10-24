package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** parameter never reassigned should be final. */
public final class FinalParametersCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-final-parameters", "FINAL_PARAMETERS"),
          "parameter never reassigned should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FinalParametersCheck",
          true,
          List.of("ast-style-parameters", "final-parameters"));

  @Override
  public String name() {
    return "final-parameters";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    for (Parameter p : m.getParameters()) {
      if (p.isFinal()) return;
      String pname = p.getNameAsString();
      int line = p.getBegin().map(b -> b.line).orElse(-1);
      int col = p.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Parameter " + pname + " should be final",
          RuleDef.Severity.LOW,
          pname);
    }
  }
}
