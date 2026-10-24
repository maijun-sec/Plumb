package org.plumb.style.naming;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Non-static field should be camelCase.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback directly; was {@code
 * visit(CompilationUnit) + findAll(FieldDeclaration.class)} before.
 */
public final class MemberNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-membername", "MEMBERNAME"),
          "non-static field should be camelCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MemberNameCheck",
          true,
          List.of("ast-naming-member-name", "membernamecheck"));

  @Override
  public String name() {
    return "membername";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration f, StyleContext ctx) {
    if (f.isStatic()) return;
    for (VariableDeclarator v : f.getVariables()) {
      String name = v.getNameAsString();
      if (name.matches("[a-z][a-zA-Z0-9_]*")) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Non-static field should be camelCase: " + name,
          RuleDef.Severity.LOW,
          name);
    }
  }
}
