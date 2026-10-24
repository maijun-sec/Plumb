package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * File should end with a newline.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `lineSeparator` | STRING | `\n` | Required line separator at end of file |
 */
public final class NewlineAtEndOfFileCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "lineSeparator", "Required line separator at end of file", RuleParameter.Type.STRING, "\n"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-newline-at-eof", "NEWLINE_AT_EOF"),
          "File should end with a newline",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NewlineAtEndOfFileCheck",
          true,
          List.of("ast-style-newline-at-eof", "newline-at-eof"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "newline-at-eof";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    ParsedSource source = ctx.source();
    String text = source.source();
    if (text == null || text.isEmpty()) return;
    String sep = paramString(ctx, "lineSeparator", "\n");
    if (text.endsWith(sep)) return;
    int line = n.getEnd().map(p -> p.line).orElse(-1);
    ctx.report(
        line,
        0,
        DEFAULT_RULE,
        "File does not end with a newline",
        RuleDef.Severity.LOW,
        "newline-at-eof");
  }

  private static String paramString(StyleContext ctx, String name, String def) {
    String override = ctx.paramOverride(name, String.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.defaultValue();
    }
    return def;
  }
}
