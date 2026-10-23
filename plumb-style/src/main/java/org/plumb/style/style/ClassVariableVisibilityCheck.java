package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public static field should be final or non-public. */
public final class ClassVariableVisibilityCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "class-variable-visibility";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.CLASS_VAR_VISIBILITY).orElse(null);
  }

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // The parent must be a top-level class (not
    // interface / enum / nested / local). // also fires for inner-class fields; Plumb's
    // pilot narrows to the immediate class body
    // for simplicity.
    if (!(n.getParentNode().orElse(null)
        instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls)) {
      return;
    }
    if (!cls.getNameAsString().matches("[A-Z][A-Za-z0-9_]*")) {
      // Inner class names are allowed; for the
      // pilot, skip non-top-level to keep the
      // surface narrow.
      return;
    }
    // Must be static, not final, not annotated.
    if (!n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC)) {
      return;
    }
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) {
      return;
    }
    if (!n.getAnnotations().isEmpty()) {
      return;
    }
    for (VariableDeclarator v : n.getVariables()) {
      int line = n.getRange().map(r -> r.begin.line).orElse(-1);
      int col = n.getRange().map(r -> r.begin.column).orElse(-1);
      this.ctx.report(
          line,
          col,
          this.rule,
          "make '" + v.getNameAsString() + "' a static final constant or non-public with accessors",
          RuleDef.Severity.LOW,
          v.getNameAsString());
    }
  }
}
