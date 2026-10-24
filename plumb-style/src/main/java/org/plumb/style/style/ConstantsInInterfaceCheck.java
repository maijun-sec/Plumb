package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** interface with only constants : use a class or enum. */
public final class ConstantsInInterfaceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-interface-constants", "INTERFACE_CONSTANTS"),
          "interface with only constants : use a class or enum",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ConstantsInInterfaceCheck",
          true,
          List.of("ast-style-constants-in-interface", "interface-constants"));

  @Override
  public String name() {
    return "interface-constants";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (!n.isInterface()) return;
    // Check that ALL members are field declarations
    // (interface fields are implicitly public static
    // final : no need to require explicit `static`).
    boolean allFields =
        !n.getMembers().isEmpty()
            && n.getMembers().stream().allMatch(m -> m instanceof FieldDeclaration);
    if (!allFields) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Interface with only fields : use a class or enum",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
