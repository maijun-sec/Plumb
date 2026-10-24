package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid native methods (JNI ties to platform). */
public final class AvoidNativeMethodsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-native-methods", "AVOID_NATIVE_METHODS"),
          "avoid native methods (JNI ties to platform)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidNativeMethodsCheck",
          true,
          List.of("ast-style-native-methods", "avoid-native-methods"));

  @Override
  public String name() {
    return "avoid-native-methods";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    for (Modifier m : n.getModifiers()) {
      if (m.getKeyword() == Modifier.Keyword.NATIVE) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Avoid native methods",
            RuleDef.Severity.LOW,
            n.getNameAsString());
        return;
      }
    }
  }
}
