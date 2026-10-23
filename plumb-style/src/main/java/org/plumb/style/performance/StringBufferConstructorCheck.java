package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use StringBuilder, not StringBuffer. */
public final class StringBufferConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-buffer-constructor", "STRING_BUFFER_CONSTRUCTOR"),
          "use StringBuilder, not StringBuffer",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.StringBufferConstructorCheck",
          true,
          List.of("ast-performance-string-buffer-constructor", "string-buffer-constructor")
      );

  @Override
  public String name() {
    return "string-buffer-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!(n.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) n.getType();
    if (!"StringBuffer".equals(t.getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use StringBuilder instead of StringBuffer (not thread-safe needed)",
        RuleDef.Severity.LOW,
        "StringBuffer");
  }
}
