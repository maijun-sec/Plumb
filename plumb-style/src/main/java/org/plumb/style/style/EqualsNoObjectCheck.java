package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals() parameter is not Object (EQ_SELF_NO_OBJECT). */
public final class EqualsNoObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-equals-no-object", "EQUALS_NO_OBJECT"),
          "equals() parameter is not Object (EQ_SELF_NO_OBJECT)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.EqualsNoObjectCheck",
          true,
          List.of("ast-style-self-no-object", "equals-no-object"));

  @Override
  public String name() {
    return "equals-no-object";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"equals".equals(n.getNameAsString())) return;
    // The method must be a non-static instance method
    // (static equals(Class, Class) overloads are not
    // affected).
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC)) return;
    if (n.getParameters().size() != 1) return;
    Parameter p = n.getParameters().get(0);
    Type t = p.getType();
    // A primitive parameter is definitely not Object.
    if (t instanceof PrimitiveType) {
      int line = p.getBegin().map(pp -> pp.line).orElse(-1);
      int col = p.getBegin().map(pp -> pp.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "equals() takes a primitive parameter : overloads "
              + "Object.equals() without overriding it",
          RuleDef.Severity.HIGH,
          t.asString());
      return;
    }
    // A reference parameter is suspect unless it is exactly
    // Object / java.lang.Object.
    if (t instanceof ClassOrInterfaceType coi) {
      String name = coi.getNameAsString();
      String fqn = coi.getNameWithScope();
      if ("Object".equals(name) || "java.lang.Object".equals(fqn)) return;
      int line = p.getBegin().map(pp -> pp.line).orElse(-1);
      int col = p.getBegin().map(pp -> pp.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "equals() takes '"
              + name
              + "' instead of Object : "
              + "overloads Object.equals() without overriding it",
          RuleDef.Severity.HIGH,
          name);
    }
  }
}
