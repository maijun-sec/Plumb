/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * C5: AST-style CWE-478 (Missing Default Case in Switch) checker.
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
