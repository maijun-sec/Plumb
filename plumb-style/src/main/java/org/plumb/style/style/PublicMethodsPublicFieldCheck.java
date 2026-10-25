package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Public method exposes public field. */
public final class PublicMethodsPublicFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-publicmethodspublicfield-sb", "PUBLICMETHODSPUBLICFIELD_SB"),
          "Public method exposes public field",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PublicMethodsPublicFieldCheck",
          true,
          List.of("ast-style-public-method-public-field", "publicmethodspublicfield_sb"));

  @Override
  public String name() {
    return "publicmethodspublicfield-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.isPublic() && m.getBody().isPresent()) {
      String body = m.getBody().get().toString();
      if (body.contains("return ") && body.matches(".*return\\s+\\w+\\.\\w+\\s*;.*")) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Public method exposes public field",
            RuleDef.Severity.LOW,
            m.getNameAsString());
      }
    }
  }
}
