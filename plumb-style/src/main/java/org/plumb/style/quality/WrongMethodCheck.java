/*
 * Coverity <code>WRONG_METHOD</code> — flags calls to {@link Boolean#getBoolean(String)} and
 * {@link Integer#getInteger(String)} that pass an arbitrary string. The intended conversion of
 * strings like {@code "true"} / {@code "42"} is done by {@link Boolean#valueOf(String)} and {@link
 * Integer#valueOf(String)}; {@code getBoolean} / {@code getInteger} actually look up a system
 * property of that name.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)} before.
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class WrongMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-wrong-method", "WRONG_METHOD"),
          "Use of Boolean.getBoolean/Integer.getInteger where Boolean.valueOf/Integer.valueOf was"
              + " probably intended",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-253", "CWE-665"),
          "org.plumb.style.WrongMethodCheck",
          true,
          List.of("ast-quality-wrong-method", "wrong-method")
      );

  /** Mirrors the Coverity default option {@code ignore_pattern}. */
  private static final Pattern IGNORE_PATTERN = Pattern.compile("(?i).*prop.*|.*name");

  private static final Set<String> TRIGGER_METHODS = Set.of("getBoolean", "getInteger");

  @Override
  public String name() {
    return "wrong-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String methodName = m.getNameAsString();
    if (!TRIGGER_METHODS.contains(methodName)) return;
    if (m.getArguments().isEmpty()) return;

    String owner = null;
    int argc = -1;
    try {
      Object resolved = m.resolve();
      if (resolved instanceof ResolvedMethodDeclaration rmd) {
        owner = rmd.declaringType().getQualifiedName();
        argc = rmd.getNumberOfParams();
      }
    } catch (RuntimeException ignore) {
      // fall back to scope text
    }

    if (owner != null
        && !"java.lang.Boolean".equals(owner)
        && !"java.lang.Integer".equals(owner)) {
      return;
    }
    if (!(argc == 1 || (owner == null && m.getArguments().size() == 1))) return;

    String firstArg = m.getArgument(0).toString();
    // Strip surrounding quotes if the arg is a string literal — the
    // Coverity ignore_pattern is meant to match identifier names
    // like "configName" / "myProperty", not literal text with quotes attached.
    if (firstArg.length() >= 2
        && firstArg.charAt(0) == '"'
        && firstArg.charAt(firstArg.length() - 1) == '"') {
      firstArg = firstArg.substring(1, firstArg.length() - 1);
    }
    if (IGNORE_PATTERN.matcher(firstArg).matches()) return;

    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    String reason =
        owner == null
            ? "Likely " + methodName + "(" + firstArg + ")"
            : owner
                + "."
                + methodName
                + "(String) reads the system property named '"
                + firstArg
                + "', not the literal value";
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "WRONG_METHOD: "
            + reason
            + " — use Boolean.valueOf / Integer.valueOf to parse literal strings",
        RuleDef.Severity.MEDIUM,
        m.toString());
  }
}