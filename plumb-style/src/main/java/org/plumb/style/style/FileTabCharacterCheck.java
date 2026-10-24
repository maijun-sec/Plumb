package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** File must not contain tab characters. */
public final class FileTabCharacterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-filetabcharacter", "FILETABCHARACTER"),
          "File must not contain tab characters",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FileTabCharacterCheck",
          true,
          List.of("legacy-tab-character", "filetabcharacter"));

  @Override
  public String name() {
    return "filetabcharacter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.toString().contains("\t")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "File must not contain tab characters",
          RuleDef.Severity.LOW,
          "tab");
    }
  }
}
