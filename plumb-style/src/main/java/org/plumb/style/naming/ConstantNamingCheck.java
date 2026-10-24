package org.plumb.style.naming;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Static final field should be UPPER_SNAKE_CASE. */
public final class ConstantNamingCheck implements AstChecker {

  private static final Pattern UPPER = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "constant-naming";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.STYLE_CONSTANT_NAMING).orElse(null);
  }

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    boolean isStatic =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
    boolean isFinal =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    if (!(isStatic && isFinal)) return;
    for (var v : n.getVariables()) {
      String name = v.getNameAsString();
      if (!UPPER.matcher(name).matches()) {
        int line = v.getRange().map(r -> r.begin.line).orElse(-1);
        int col = v.getRange().map(r -> r.begin.column).orElse(-1);
        this.ctx.report(
            line,
            col,
            this.rule,
            "Constant '" + name + "' should be UPPER_SNAKE_CASE",
            RuleDef.Severity.LOW,
            name);
      }
    }
  }
}
