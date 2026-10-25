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
 * Line is too long.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
 * | `100` | Maximum allowed line length | | `ignorePattern` | STRING | `` | Regex of lines to
 * ignore (after trim) | | `ignoreImports` | BOOLEAN | `false` | Skip import statements | |
 * `ignoreComments` | BOOLEAN | `true` | Skip comment lines |
 */
public final class LineLengthCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter("max", "Maximum allowed line length", RuleParameter.Type.INT, "100"),
    new RuleParameter(
        "ignorePattern", "Regex of lines to ignore (after trim)", RuleParameter.Type.STRING, ""),
    new RuleParameter(
        "ignoreImports", "Skip import statements", RuleParameter.Type.BOOLEAN, "false"),
    new RuleParameter("ignoreComments", "Skip comment lines", RuleParameter.Type.BOOLEAN, "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-line-length", "LINE_LENGTH"),
          "Line is too long",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LineLengthCheck",
          true,
          List.of("ast-style-line-length", "line-length"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "line-length";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    ParsedSource source = ctx.source();
    String text = source.source();
    if (text == null) return;
    Integer _o = ctx.paramOverride("max", Integer.class);
    int max = _o != null ? _o : 100;
    String ignorePattern = paramString(ctx, "ignorePattern", "");
    boolean ignoreImports = paramBool(ctx, "ignoreImports", false);
    boolean ignoreComments = paramBool(ctx, "ignoreComments", true);
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.length() <= max) continue;
      String trimmed = line.trim();
      if (!ignorePattern.isEmpty() && trimmed.matches(ignorePattern)) continue;
      if (ignoreImports && trimmed.startsWith("import ")) continue;
      if (ignoreComments
          && (trimmed.startsWith("//")
              || trimmed.startsWith("/*")
              || trimmed.startsWith("*")
              || trimmed.endsWith("*/"))) continue;
      int lineNo = i + 1;
      ctx.report(
          lineNo,
          max,
          DEFAULT_RULE,
          "Line is " + line.length() + " chars (max " + max + ")",
          RuleDef.Severity.LOW,
          "line-length");
    }
  }

  private static String paramString(StyleContext ctx, String name, String def) {
    String override = ctx.paramOverride(name, String.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.defaultValue();
    }
    return def;
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
