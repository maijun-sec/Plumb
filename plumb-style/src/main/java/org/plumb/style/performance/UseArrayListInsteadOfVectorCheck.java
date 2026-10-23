package org.plumb.style.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use ArrayList instead of Vector. */
public final class UseArrayListInsteadOfVectorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-arraylist-instead-of-vector", "USE_ARRAYLIST_INSTEAD_OF_VECTOR"),
          "use ArrayList instead of Vector",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UseArrayListInsteadOfVectorCheck",
          true,
          List.of("ast-performance-arraylist-instead-of-vector", "use-arraylist-instead-of-vector")
      );

  @Override
  public String name() {
    return "use-arraylist-instead-of-vector";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceType n, StyleContext ctx) {
    if (!"Vector".equals(n.getNameAsString())) return;
    // Skip if the import is fully qualified
    // java.util.Vector : could be an intentional
    // choice. Otherwise, the import is the bare
    // name, so we flag it.
    fire(n, ctx);
  }

  private void fire(Node n, StyleContext ctx) {
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use ArrayList instead of Vector (wrap in Collections.synchronizedList if needed)",
        RuleDef.Severity.LOW,
        "Vector");
  }
}
