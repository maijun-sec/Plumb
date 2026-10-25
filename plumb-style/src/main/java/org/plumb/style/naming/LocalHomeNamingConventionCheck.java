/*
 * <p>The previous checker was a no-op
 * ({@code // Heuristic: skip — would need EJB type resolution.}). It
 * existed only to occupy a RuleRegistry slot. Marking the rule
 * {@code enabled=false} causes
 * {@link org.plumb.style.AstCheckerRegistry#tryRegister} to drop it
 * from {@code builtins()} and {@code registeredNames()}.
 *
 * <p>Detection would require type resolution (an {@code @LocalHome}
 * interface annotation lookup, not simple AST). Replace when an
 * annotation-processing pass is available.
 */
package org.plumb.style.naming;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** EJB local home should end with LocalHome — STUB, not registered. */
@Deprecated
public final class LocalHomeNamingConventionCheck implements AstChecker {

  /** R-AST-FIX-2026-10-18: enabled=false. */
  private static final RuleDef DISABLED_RULE =
      new RuleDef(
          new RuleId(
              "ast-naming-local-home-naming-NOT-IMPLEMENTED", "LOCAL_HOME_NAMING_NOT_IMPLEMENTED"),
          "EJB LocalHome should end with LocalHome — STUB, not registered",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LocalHomeNamingConventionCheck",
          /* enabled */ false);

  @Override
  public String name() {
    return "local-home-naming-STUB";
  }

  @Override
  public RuleDef rule() {
    return DISABLED_RULE;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {}
}
