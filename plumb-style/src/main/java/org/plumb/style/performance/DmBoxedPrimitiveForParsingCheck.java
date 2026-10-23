package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** new Integer("5") : use Integer.parseInt(s) instead (DM_BOXED_PRIMITIVE_FOR_PARSING). */
public final class DmBoxedPrimitiveForParsingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-dm-boxed-primitive", "DM_BOXED_PRIMITIVE"),
          "new Integer(\"5\") : use Integer.parseInt(s) instead (DM_BOXED_PRIMITIVE_FOR_PARSING)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-704"),
          "org.plumb.style.DmBoxedPrimitiveForParsingCheck",
          true,
          List.of("ast-performance-boxed-primitive-for-parsing", "dm-boxed-primitive")
      );

  private static final Set<String> WRAPPERS =
      Set.of("Integer", "Long", "Double", "Float", "Short", "Byte", "Boolean");

  @Override
  public String name() {
    return "dm-boxed-primitive";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!(n.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) n.getType();
    if (!WRAPPERS.contains(t.getNameAsString())) return;
    if (n.getArguments().size() != 1) return;
    if (n.getArgument(0) instanceof com.github.javaparser.ast.expr.StringLiteralExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "new " + t.getNameAsString() + "(String) : use parseXxx(s) instead",
          RuleDef.Severity.MEDIUM,
          t.getNameAsString());
    }
  }
}
