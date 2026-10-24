package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** explicit initialization to default value is redundant. */
public final class ExplicitInitializationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-explicitinitialization", "EXPLICITINITIALIZATION"),
          "explicit initialization to default value is redundant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ExplicitInitializationCheck",
          true,
          List.of("ast-style-explicit-initialization", "explicitinitializationcheck"));

  @Override
  public String name() {
    return "explicitinitialization";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    if (v.getInitializer().isPresent()) {
      String init = v.getInitializer().get().toString();
      if (init.equals("0")
          || init.equals("0L")
          || init.equals("null")
          || init.equals("false")
          || init.equals("true")
          || init.equals("0.0")
          || init.equals("0.0f")
          || init.equals("0.0d")) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Explicit initialization to default is redundant: " + v.getNameAsString(),
            RuleDef.Severity.LOW,
            v.getNameAsString());
      }
    }
  }
}
