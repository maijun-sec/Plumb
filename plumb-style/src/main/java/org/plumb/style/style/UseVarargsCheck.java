package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ArrayType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use varargs (...) instead of array as last parameter. */
public final class UseVarargsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-varargs", "USE_VARARGS"),
          "use varargs (...) instead of array as last parameter",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.UseVarargsCheck",
          true,
          List.of("ast-style-varargs", "use-varargs"));

  @Override
  public String name() {
    return "use-varargs";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getParameters().isEmpty()) return;
    Parameter last = n.getParameter(n.getParameters().size() - 1);
    if (last.getType() instanceof ArrayType) {
      int line = last.getBegin().map(p -> p.line).orElse(-1);
      int col = last.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Use varargs instead of array as last parameter",
          RuleDef.Severity.MEDIUM,
          last.getNameAsString());
    }
  }
}
