package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class depends on many classes in a foreign package. */
public final class LoosePackageCouplingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-loose-coupling-2", "LOOSE_COUPLING_2"),
          "class depends on many classes in a foreign package",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LoosePackageCouplingCheck",
          true,
          List.of("ast-design-package-coupling", "loose-coupling")
      );

  @Override
  public String name() {
    return "loose-coupling";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Heuristic: count ClassOrInterfaceType references
    // and report if the number is large.
    Set<String> types = new HashSet<>();
    for (ClassOrInterfaceType t : n.findAll(ClassOrInterfaceType.class)) {
      types.add(t.asString());
    }
    if (types.size() < 30) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class references " + types.size() + " distinct types",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
