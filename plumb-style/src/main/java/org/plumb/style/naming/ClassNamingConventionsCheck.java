package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Class name does not match the required pattern.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `format` |
 * STRING | `^[A-Z][a-zA-Z0-9]*$` | Regex the class name must match | | `abstractClassFormat` |
 * STRING | `` | Regex for abstract classes (empty = same as format) | | `utilityClassFormat` |
 * STRING | `` | Regex for utility classes (empty = same as format) |
 */
public final class ClassNamingConventionsCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "format",
        "Regex the class name must match",
        RuleParameter.Type.STRING,
        "^[A-Z][a-zA-Z0-9]*$"),
    new RuleParameter(
        "abstractClassFormat",
        "Regex for abstract classes (empty = same as format)",
        RuleParameter.Type.STRING,
        ""),
    new RuleParameter(
        "utilityClassFormat",
        "Regex for utility classes (empty = same as format)",
        RuleParameter.Type.STRING,
        ""),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-class-naming", "CLASS_NAMING"),
          "Class name does not match the required pattern",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ClassNamingConventionsCheck",
          false, // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)
          List.of(),
          List.of(PARAMS));

  @Override
  public String name() {
    return "class-naming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    String name = n.getNameAsString();
    String format = paramString(ctx, "format", "^[A-Z][a-zA-Z0-9]*$");
    String pattern;
    if (n.isAbstract()) {
      String af = paramString(ctx, "abstractClassFormat", "");
      pattern = af.isEmpty() ? format : af;
    } else if (isUtilityClass(n)) {
      String uf = paramString(ctx, "utilityClassFormat", "");
      pattern = uf.isEmpty() ? format : uf;
    } else {
      pattern = format;
    }
    if (!Pattern.matches(pattern, name)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Class name '" + name + "' does not match " + pattern,
          RuleDef.Severity.LOW,
          name);
    }
  }

  /**
   * A utility class has only static methods/fields and a private no-arg constructor. We approximate
   * by checking that every declared method is static.
   */
  private static boolean isUtilityClass(ClassOrInterfaceDeclaration n) {
    if (n.getMethods().isEmpty()) return false;
    return n.getMethods().stream().allMatch(m -> m.isStatic());
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
