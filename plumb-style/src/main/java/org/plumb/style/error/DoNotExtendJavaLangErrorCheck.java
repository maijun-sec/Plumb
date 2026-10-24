package org.plumb.style.error;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** do not extend java.lang.Error. */
public final class DoNotExtendJavaLangErrorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-do-not-extend-error", "DO_NOT_EXTEND_ERROR"),
          "do not extend java.lang.Error",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.DoNotExtendJavaLangErrorCheck",
          true,
          List.of("ast-error-not-extend-error", "do-not-extend-error"));

  @Override
  public String name() {
    return "do-not-extend-error";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    for (var ext : n.getExtendedTypes()) {
      String name = ext.getNameAsString();
      if ("Error".equals(name)
          || "java.lang.Error".equals(name)
          || "Throwable".equals(name)
          || "java.lang.Throwable".equals(name)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Do not extend " + name + " : reserved for JVM",
            RuleDef.Severity.MEDIUM,
            name);
      }
    }
  }
}
