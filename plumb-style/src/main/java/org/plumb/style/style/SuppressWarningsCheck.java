/*
 * R-AST-FIX-2026-10-18: heuristics-not-implemented stub.
 *
 * <p>The original checker was a no-op
 * ({@code // Heuristic: skip — would need annotation processing.}). It
 * existed only to occupy a RuleRegistry slot. Marking the rule
 * {@code enabled=false} causes
 * {@link org.plumb.style.AstCheckerRegistry#tryRegister} to drop it
 * from {@code builtins()} and {@code registeredNames()}.
 */
package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * @SuppressWarnings should be specific ? unimplemented.
 *
 * <p>Detection requires annotation processing / symbol resolution which is outside the AST pass.
 *     Kept as a registry-skipped placeholder; an analyzer-level checker should replace this when
 *     needed.
 */
@Deprecated
public final class SuppressWarningsCheck implements AstChecker {

  /**
   * R-AST-FIX-2026-10-18: enabled=false ? stub, not registered. Operators needing the rule should
   * build an annotation-processing module and re-add with enabled=true.
   */
  private static final RuleDef DISABLED_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-suppress-warnings-NOT-IMPLEMENTED",
              "SUPPRESS_WARNINGS_NOT_IMPLEMENTED"),
          "@SuppressWarnings not specific — STUB, not registered",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SuppressWarningsCheck",
          /* enabled */ false);

  @Override
  public String name() {
    return "suppress-warnings-STUB";
  }

  @Override
  public RuleDef rule() {
    return DISABLED_RULE;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // STUB: detection would require annotation processing. See R-AST-FIX-2026-10-18.
  }
}
