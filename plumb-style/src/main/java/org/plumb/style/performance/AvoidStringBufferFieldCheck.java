package org.plumb.style.performance;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid StringBuffer as a field. */
public final class AvoidStringBufferFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-avoid-stringbuffer-field", "AVOID_STRINGBUFFER_FIELD"),
          "avoid StringBuffer as a field",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidStringBufferFieldCheck",
          true,
          List.of("ast-performance-stringbuffer-field", "avoid-stringbuffer-field"));

  @Override
  public String name() {
    return "avoid-stringbuffer-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (n.getElementType() instanceof ClassOrInterfaceType t) {
      String name = t.getNameAsString();
      if ("StringBuffer".equals(name) || "java.lang.StringBuffer".equals(name)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Avoid StringBuffer as a field : use a local variable or StringBuilder",
            RuleDef.Severity.LOW,
            name);
      }
    }
  }
}
