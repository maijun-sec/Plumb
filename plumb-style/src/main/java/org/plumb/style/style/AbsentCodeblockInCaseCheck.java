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
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Case statement is missing a code block.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `requireBraces` | BOOLEAN | `true` | Fire on every multi-statement case body |
 */
public final class AbsentCodeblockInCaseCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "requireBraces",
        "Fire on every multi-statement case body",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-absent-codeblock-in-case", "ABSENT_CODEBLOCK_IN_CASE"),
          "Case statement is missing a code block",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-484"),
          "org.plumb.style.AbsentCodeblockInCaseCheck",
          true,
          List.of("ast-style-absent-codeblock-in-case", "absent-codeblock-in-case"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "absent-codeblock-in-case";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    if (!paramBool(ctx, "requireBraces", true)) return;
    for (SwitchEntry entry : n.getEntries()) {
      int stmtCount = entry.getStatements().size();
      if (stmtCount <= 1) continue; // single statement is fine
      // If the first statement is itself a block, OK.
      Statement first = entry.getStatements().get(0);
      if (first instanceof BlockStmt) continue;
      int line = entry.getBegin().map(p -> p.line).orElse(-1);
      int col = entry.getBegin().map(p -> p.column).orElse(-1);
      String label = entry.getLabels().isEmpty() ? "default" : entry.getLabels().get(0).toString();
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Case body has " + stmtCount + " statements but no enclosing block",
          RuleDef.Severity.MEDIUM,
          label);
    }
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
