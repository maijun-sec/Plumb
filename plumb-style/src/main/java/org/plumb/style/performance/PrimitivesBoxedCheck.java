package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** new Integer/Long/... : use the literal. */
public final class PrimitivesBoxedCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-primitives-boxed", "PRIMITIVES_BOXED"),
          "new Integer/Long/... : use the literal",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-704"),
          "org.plumb.style.PrimitivesBoxedCheck",
          true,
          List.of("ast-performance-primitives-boxed", "primitives-boxed"));

  private static final Set<String> WRAPPERS =
      Set.of(
          "Integer", "Long", "Double", "Float", "Short", "Byte", "Boolean", "Character", "Number");

  @Override
  public String name() {
    return "primitives-boxed";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!(n.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) n.getType();
    String name = t.getNameAsString();
    if (!WRAPPERS.contains(name)) return;
    // new Boolean(...) is particularly bad because
    // of the cached-instance pitfall. Others are
    // mainly style.
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid 'new " + name + "(...)' : use the primitive literal",
        RuleDef.Severity.MEDIUM,
        name);
  }
}
