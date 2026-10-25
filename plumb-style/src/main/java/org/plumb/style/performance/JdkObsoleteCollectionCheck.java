package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Use non-obsolete JDK collection (e.g. {@code Vector}, {@code Hashtable}, {@code Stack}). */
public final class JdkObsoleteCollectionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-jdk-obsolete-collection", "JDK_OBSOLETE_COLLECTION"),
          "Use non-obsolete JDK collection",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JdkObsoleteCollectionCheck",
          true,
          List.of("ast-performance-obsolete-collection", "jdk-obsolete-collection"));

  @Override
  public String name() {
    return "jdk-obsolete-collection";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr o, StyleContext ctx) {
    String s = o.getType().getNameAsString();
    if (!"Vector".equals(s) && !"Hashtable".equals(s) && !"Stack".equals(s)) return;
    int line = o.getBegin().map(p -> p.line).orElse(-1);
    int col = o.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use non-obsolete JDK collection: " + s,
        RuleDef.Severity.MEDIUM,
        s);
  }
}
