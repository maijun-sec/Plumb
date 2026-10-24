package org.plumb.style.naming;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public constant should be SCREAMING_SNAKE_CASE. */
public final class PublicConstantFieldNamingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-public-constant", "PUBLIC_CONSTANT"),
          "public constant should be SCREAMING_SNAKE_CASE",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.PublicConstantFieldNamingCheck",
          true,
          List.of("ast-naming-public-constant-field-naming", "public-constant"));

  private static final Pattern SNAKE_CASE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  @Override
  public String name() {
    return "public-constant";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    boolean isPublicStaticFinal =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC)
            && n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC)
            && n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    if (!isPublicStaticFinal) return;
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      if (SNAKE_CASE.matcher(name).matches()) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Public constant '" + name + "' should be SCREAMING_SNAKE_CASE",
          RuleDef.Severity.LOW,
          name);
    }
  }
}
