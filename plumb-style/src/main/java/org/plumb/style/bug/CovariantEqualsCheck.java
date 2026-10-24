package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals() should accept Object, not a subtype. */
public final class CovariantEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-covariant-equals", "COVARIANT_EQUALS"),
          "equals() should accept Object, not a subtype",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.CovariantEqualsCheck",
          true,
          List.of("ast-bug-covariant-equals", "covariant-equals"));

  @Override
  public String name() {
    return "covariant-equals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!n.getNameAsString().equals("equals")) return;
    if (n.getParameters().size() != 1) return;
    Parameter p = n.getParameters().get(0);
    // Check the parameter type : it must be a class type
    // with name "Object" or "java.lang.Object".
    String typeName = p.getType().asString();
    if (typeName.equals("Object") || typeName.equals("java.lang.Object")) {
      return; // canonical equals
    }
    // If it's a primitive or array, also not a covariant
    // override; only class types with non-Object names are.
    if (!(p.getType() instanceof ClassOrInterfaceType)) return;
    int line = n.getBegin().map(p2 -> p2.line).orElse(-1);
    int col = n.getBegin().map(p2 -> p2.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "equals() should accept 'Object' but is declared with '" + typeName + "'",
        RuleDef.Severity.MEDIUM,
        n.getNameAsString());
  }
}
