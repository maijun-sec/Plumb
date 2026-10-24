package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Serializable class field is not marked transient or serialVersionUID. */
public final class BeanMembersShouldSerializeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-bean-members-serialize", "BEAN_MEMBERS_SERIALIZE"),
          "Serializable class field is not marked transient or serialVersionUID",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.BeanMembersShouldSerializeCheck",
          true,
          List.of("ast-naming-members-should-serialize", "bean-members-serialize"));

  @Override
  public String name() {
    return "bean-members-serialize";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Check if the class implements Serializable.
    boolean serializable =
        n.getImplementedTypes().stream()
            .anyMatch(
                t ->
                    t.getNameAsString().equals("Serializable")
                        || t.getNameAsString().equals("java.io.Serializable"));
    if (!serializable) return;
    for (FieldDeclaration f : n.getFields()) {
      if (f.isStatic()) continue;
      if (f.isTransient()) continue;
      // Skip serialVersionUID.
      for (var v : f.getVariables()) {
        if (v.getNameAsString().equals("serialVersionUID")) continue;
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field '"
                + v.getNameAsString()
                + "' in Serializable class : mark transient or add to serialVersionUID",
            RuleDef.Severity.MEDIUM,
            v.getNameAsString());
      }
    }
  }
}
