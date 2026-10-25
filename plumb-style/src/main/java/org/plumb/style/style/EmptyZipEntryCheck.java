package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Empty zip entry. */
public final class EmptyZipEntryCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-zip-entry", "EMPTY_ZIP_ENTRY"),
          "Empty zip entry",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyZipEntryCheck",
          true,
          List.of("ast-style-zip-entry", "empty-zip-entry"));

  @Override
  public String name() {
    return "empty-zip-entry";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (m.getNameAsString().equals("putNextEntry") && m.getArguments().isEmpty()) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(line, col, DEFAULT_RULE, "Empty zip entry", RuleDef.Severity.LOW, "zip");
    }
  }
}
