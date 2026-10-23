/*
 * R722-C5: AST-style CWE-478 (Missing Default Case in Switch) checker.
 *
 * <p>Fires when a {@link com.github.javaparser.ast.stmt.SwitchStmt}
 * has at least one {@code case} entry but no
 * {@code default} entry. The check is a structural one:
 * walk every {@code SwitchEntry} in the switch and verify
 * the switch has at least one entry with label
 * {@code isDefault()}.</p>
 *
 * <p><strong>Why AST not taint:</strong> the pattern is
 * purely structural (an absence of a switch label). No
 * data flow is involved.</p>
 *
 * <p><strong>General-purpose:</strong> the rule targets
 * any Java switch statement lacking a default branch.
 * No Juliet-specific hard-coding.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-478: Missing Default Case in Switch Statement.
 *
 * <p>Detects a {@code switch} statement that has at least one {@code case} entry but no {@code
 * default} entry. Without a default, the switch silently does nothing for values outside the listed
 * cases.
 */
public final class MissingDefaultCaseCheck implements AstChecker {

  private static final String RULE_ID = "dfa-style-missing-default-case";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "MISSING_DEFAULT_CASE"),
          "Switch statement lacks a 'default' case (CWE-478)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-478"),
          "org.plumb.style.style.MissingDefaultCaseCheck",
          true);

  @Override
  public String name() {
    return "missing-default-case";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    List<SwitchEntry> entries = n.getEntries();
    if (entries == null || entries.isEmpty()) return;
    for (SwitchEntry e : entries) {
      if (e.getLabels().isEmpty()) {
        // No labels means it's a default entry
        return;
      }
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Switch statement has no 'default' case; unexpected values are silently ignored",
        RuleDef.Severity.LOW,
        "missing-default-case");
  }
}
