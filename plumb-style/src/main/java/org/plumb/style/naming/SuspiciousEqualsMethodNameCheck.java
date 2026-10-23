package org.plumb.style.naming;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals() method should take java.lang.Object. */
public final class SuspiciousEqualsMethodNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-suspicious-equals", "SUSPICIOUS_EQUALS"),
          "equals() method should take java.lang.Object",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-697"),
          "org.plumb.style.SuspiciousEqualsMethodNameCheck",
          true,
          List.of("ast-naming-suspicious-equals-method-name", "suspicious-equals")
      );

  @Override
  public String name() {
    return "suspicious-equals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"equals".equals(n.getNameAsString())) return;
    if (n.getParameters().size() != 1) return;
    Parameter param = n.getParameter(0);
    String typeName = param.getType().toString();
    // Flag anything other than Object / java.lang.Object
    // / Comparable / java.lang.Comparable.
    if ("Object".equals(typeName)
        || "java.lang.Object".equals(typeName)
        || "Comparable".equals(typeName)
        || "java.lang.Comparable".equals(typeName)) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "equals() method should take java.lang.Object : got " + typeName,
        RuleDef.Severity.MEDIUM,
        typeName);
  }
}
