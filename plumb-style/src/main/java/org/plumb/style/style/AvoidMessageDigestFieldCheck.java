package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid MessageDigest as a field (not thread-safe). */
public final class AvoidMessageDigestFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-messagedigest-field", "AVOID_MESSAGEDIGEST_FIELD"),
          "avoid MessageDigest as a field (not thread-safe)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-362"),
          "org.plumb.style.AvoidMessageDigestFieldCheck",
          true,
          List.of("ast-style-messagedigest-field", "avoid-messagedigest-field"));

  @Override
  public String name() {
    return "avoid-messagedigest-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (n.getElementType() instanceof ClassOrInterfaceType t) {
      String name = t.getNameAsString();
      if ("MessageDigest".equals(name) || "java.security.MessageDigest".equals(name)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "MessageDigest as a field is not thread-safe",
            RuleDef.Severity.MEDIUM,
            name);
      }
    }
  }
}
