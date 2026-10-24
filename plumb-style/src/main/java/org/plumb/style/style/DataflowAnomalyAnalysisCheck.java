/*
 * R-AST-FIX-2026-10-18: dataflow-anomaly stub.
 *
 * <p>The previous checker was a no-op
 * ({@code // Heuristic: skip — would need full dataflow analysis.}). It
 * existed only to occupy a RuleRegistry slot. Marking the rule
 * {@code enabled=false} causes
 * {@link org.plumb.style.AstCheckerRegistry#tryRegister} to drop it
 * from {@code builtins()} and {@code registeredNames()}.
 *
 * <p>An equivalent DFA-side check (uninitialized read / dead store) is
 * implemented in {@code plumb-dfa}; AST can only do a degenerate
 * constant-bool analysis (covered by
 * {@code LiteralBooleanConditionCheck}).
 */
package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** data flow anomaly (UR, DU, DD) — STUB, not registered. */
@Deprecated
public final class DataflowAnomalyAnalysisCheck implements AstChecker {

  /** R-AST-FIX-2026-10-18: enabled=false. */
  private static final RuleDef DISABLED_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-dataflow-anomaly-NOT-IMPLEMENTED", "DATAFLOW_ANOMALY_NOT_IMPLEMENTED"),
          "data flow anomaly (UR, DU, DD) — STUB; equivalent in plumb-dfa",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DataflowAnomalyAnalysisCheck",
          /* enabled */ false);

  @Override
  public String name() {
    return "dataflow-anomaly-STUB";
  }

  @Override
  public RuleDef rule() {
    return DISABLED_RULE;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // STUB: detection requires full dataflow analysis. See R-AST-FIX-2026-10-18.
  }
}
