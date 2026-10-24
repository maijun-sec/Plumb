package org.plumb.style.style;

import com.github.javaparser.ast.stmt.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Switch case falls through to the next case without break/return/throw/continue. */
public final class MissingBreakCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missing-break", "MISSING_BREAK"),
          "Switch case falls through to the next case without break/return/throw/continue",
          RuleDef.Severity.LOW,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-484"),
          "org.plumb.style.MissingBreakCheck",
          true,
          List.of("ast-style-missing-break", "missing-break"));

  @Override
  public String name() {
    return "missing-break";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    final List<SwitchEntry> entries = n.getEntries();
    if (entries.isEmpty()) return;
    // Walk every non-last case. The last case has no
    // fall-through target so we never flag it. We
    // also skip empty cases (intentional fall-through
    // is a valid idiom in some codebases).
    for (int i = 0; i < entries.size() - 1; i++) {
      final SwitchEntry entry = entries.get(i);
      final List<Statement> body = entry.getStatements();
      if (body.isEmpty()) continue; // intentional fall-through
      final Statement last = body.get(body.size() - 1);
      if (isTerminating(last)) continue;
      // Non-terminating case body. Fire.
      ctx.report(
          entry.getBegin().map(p -> p.line).orElse(-1),
          entry.getBegin().map(p -> p.column).orElse(-1),
          DEFAULT_RULE,
          "Switch case falls through to the next case without break/return/throw/continue",
          RuleDef.Severity.LOW,
          entry.toString());
    }
  }

  /**
   * Returns true if a statement that exits the case body (the next case is unreachable). Same shape
   * as JavaParser's kept explicit to avoid library version drift.
   */
  private static boolean isTerminating(Statement s) {
    return s instanceof BreakStmt
        || s instanceof ReturnStmt
        || s instanceof ThrowStmt
        || s instanceof ContinueStmt;
  }
}
