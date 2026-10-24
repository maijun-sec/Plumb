package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class is coupled to too many other classes (limit 20). */
public final class CouplingBetweenObjectsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-coupling", "COUPLING"),
          "class is coupled to too many other classes (limit 20)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CouplingBetweenObjectsCheck",
          true,
          List.of("ast-design-coupling-between-objects", "coupling"));

  private static final int MAX_COUPLING = 20;

  @Override
  public String name() {
    return "coupling";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    Set<String> coupled = new HashSet<>();
    for (ClassOrInterfaceType t : n.findAll(ClassOrInterfaceType.class)) {
      if (!t.getNameAsString().equals(n.getNameAsString())) {
        coupled.add(t.getNameAsString());
      }
    }
    if (coupled.size() <= MAX_COUPLING) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class is coupled to " + coupled.size() + " other classes (limit " + MAX_COUPLING + ")",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
