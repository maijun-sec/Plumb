package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Mutable static field should be final (mutable global state). */
public final class MutableStaticShouldBeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-mutable-static-should-be-final", "MUTABLE_STATIC_SHOULD_BE_FINAL"),
          "Mutable static field should be final (mutable global state)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.MALICIOUS_CODE,
          List.of("CWE-500"),
          "org.plumb.style.MutableStaticShouldBeFinalCheck",
          false,
          List.of(
              "ast-style-mutable-static-should-be-final",
              "mutable-static-should-be-final")); // R-834: disabled by default (0 TP / 100% FP on

  // Juliet R-832 baseline)

  @Override
  public String name() {
    return "mutable-static-should-be-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // Must be static.
    if (!hasModifier(n, Modifier.Keyword.STATIC)) return;
    // Must NOT be final.
    if (hasModifier(n, Modifier.Keyword.FINAL)) return;
    // Skip enums and interfaces (their fields are
    // implicitly final/static).
    // Fire for each variable in the declaration.
    for (VariableDeclarator v : n.getVariables()) {
      ctx.report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          DEFAULT_RULE,
          "Static field '" + v.getNameAsString() + "' should be final (mutable global state)",
          RuleDef.Severity.MEDIUM,
          v.getNameAsString());
    }
  }

  private static boolean hasModifier(FieldDeclaration n, Modifier.Keyword kw) {
    for (Modifier m : n.getModifiers()) {
      if (m.getKeyword() == kw) return true;
    }
    return false;
  }
}
