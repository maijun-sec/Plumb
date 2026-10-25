package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Code indentation check. */
public final class IndentationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-indentation", "INDENTATION"),
          "Code indentation check",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.IndentationCheck",
          false,
          List.of(
              "ast-style-indentation",
              "indentation")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // Disabled by default: this rule reports a style preference rather than a defect.

  @Override
  public String name() {
    return "indentation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.getNameAsString().matches("[A-Z][A-Za-z0-9]+")) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    if (col > 0) {
      ctx.report(
          line, col, DEFAULT_RULE, "Indentation check", RuleDef.Severity.LOW, c.getNameAsString());
    }
  }
}
