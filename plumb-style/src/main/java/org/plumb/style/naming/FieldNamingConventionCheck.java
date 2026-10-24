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

/** field name should be camelCase. */
public final class FieldNamingConventionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-field-naming", "FIELD_NAMING"),
          "field name should be camelCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.FieldNamingConventionCheck",
          true,
          List.of("legacy-naming-convention", "field-naming"));

  private static final Pattern CAMEL = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

  @Override
  public String name() {
    return "field-naming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    boolean isStatic =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
    boolean isFinal =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      if (isStatic && isFinal) continue; // constants, separate rule
      if (CAMEL.matcher(name).matches()) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Field '" + name + "' should be camelCase",
          RuleDef.Severity.LOW,
          name);
    }
  }
}
