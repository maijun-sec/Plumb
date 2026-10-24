package org.plumb.style.naming;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;
import org.plumb.style.annotations.AnnotationExtractor;

/**
 * Method name should be camelCase.
 *
 * <p>M1 audit (2026-10-17) widened the test-method exemption: the previous version only skipped
 * methods whose name started with {@code test}, which works for JUnit 3 / 4 conventions but misses
 * JUnit 5's {@code @Test}-annotated methods with arbitrary names (e.g. {@code
 * shouldHandleEmptyInput()}, {@code returns_404_when_path_does_not_exist()}). The new version also
 * accepts any method carrying a JUnit {@code @Test} / {@code @ParameterizedTest} /
 * {@code @RepeatedTest} / {@code @TestFactory} / {@code @TestTemplate} annotation.
 */
public final class MethodNamingConventionsCheck implements AstChecker {

  private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

  /**
   * JUnit {@code @Test} family annotations. Both JUnit 4 ({@code org.junit.Test}) and JUnit 5
   * ({@code org.junit.jupiter.api.Test}) + variants. The set is unordered at the language level but
   * exposed as a {@link LinkedHashSet} so iteration is deterministic for log messages.
   */
  private static final Set<String> JUNIT_TEST =
      Set.of(
          "Test",
          "ParameterizedTest",
          "RepeatedTest",
          "TestFactory",
          "TestTemplate",
          "org.junit.Test",
          "org.junit.jupiter.api.Test",
          "org.junit.jupiter.api.ParameterizedTest",
          "org.junit.jupiter.api.RepeatedTest",
          "org.junit.jupiter.api.TestFactory",
          "org.junit.jupiter.api.TestTemplate");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-method-naming", "METHOD_NAMING"),
          "Method name should be camelCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.MethodNamingConventionsCheck",
          true,
          List.of("ast-naming-method-naming-conventions", "method-naming"));

  @Override
  public String name() {
    return "method-naming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (name.length() < 2) return;
    if (CAMEL_CASE.matcher(name).matches()) return;
    // M1: skip test methods by either convention:
    // (a) JUnit 3/4 name prefix "test..."
    // (b) JUnit 5 annotation-based @Test / @ParameterizedTest etc.
    if (name.startsWith("test")) return;
    if (AnnotationExtractor.hasAny(n, JUNIT_TEST)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method '" + name + "' should be camelCase",
        RuleDef.Severity.LOW,
        name);
  }
}
