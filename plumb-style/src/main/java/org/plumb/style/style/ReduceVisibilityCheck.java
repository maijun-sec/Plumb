package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** field visibility can be reduced to private. */
public final class ReduceVisibilityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-reduce-visibility", "REDUCE_VISIBILITY"),
          "field visibility can be reduced to private",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ReduceVisibilityCheck",
          false,
          List.of("ast-style-reduce-visibility", "reduce-visibility")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "reduce-visibility";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Heuristic: a public field with no name
    // reference outside the class : but we can't
    // easily check external usage. We just flag
    // public non-final non-static fields as a
    // heuristic (mutable public state is suspect).
    for (FieldDeclaration f : n.getFields()) {
      if (!f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC))
        continue;
      if (f.isStatic() && f.isFinal()) continue;
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Public non-final field : consider reducing visibility",
          RuleDef.Severity.LOW,
          "public");
    }
  }
}
