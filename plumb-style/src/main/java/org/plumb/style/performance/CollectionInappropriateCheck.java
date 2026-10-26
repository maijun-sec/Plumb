package org.plumb.style.performance;

import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Using {@code Hashtable} / {@code Vector} / {@code Stack} -- prefer {@code java.util.concurrent}
 * alternatives or {@code ArrayList} / {@code HashMap}.
 */
public final class CollectionInappropriateCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-collection-inappropriate", "COLLECTION_INAPPROPRIATE"),
          "using Hashtable/Vector",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CollectionInappropriateCheck",
          true,
          List.of("ast-performance-collection-inappropriate", "collection-inappropriate"));

  @Override
  public String name() {
    return "collection-inappropriate";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceType t, StyleContext ctx) {
    String name = t.getNameAsString();
    if (!"Hashtable".equals(name) && !"Vector".equals(name) && !"Stack".equals(name)) return;
    int line = t.getBegin().map(p -> p.line).orElse(-1);
    int col = t.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use "
            + name
            + " from java.util.concurrent or ArrayList/HashMap; "
            + name
            + " is legacy/synchronized",
        RuleDef.Severity.LOW,
        name);
  }
}
