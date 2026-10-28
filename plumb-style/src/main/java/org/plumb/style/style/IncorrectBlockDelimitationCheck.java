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
 * C6: AST-style CWE-483 (Incorrect Block Delimitation) checker.
 *
 * <p>Fires when an {@code if} statement body is a single
 * statement (no braces) AND there is at least one
 * statement immediately after the {@code if} in the
 * enclosing {@code BlockStmt}. Without braces, the
 * statement after the {@code if} runs unconditionally;
 * the developer may have intended it to be inside the
 * {@code if}.</p>
 *
 * <p><strong>Why AST not taint:</strong> purely structural
 * absence of braces around an if-body.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-483: Incorrect Block Delimitation.
 *
 * <p>Detects an {@code if} statement whose body is a single statement (no curly braces) and that
 * has at least one following statement in the same enclosing block. The risk: the developer wrote
 * the next statement intending it to be part of the {@code if}, but the missing braces mean it
 * always runs.
 */
public final class IncorrectBlockDelimitationCheck implements AstChecker {

  private static final String RULE_ID = "dfa-style-incorrect-block-delimitation";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "INCORRECT_BLOCK_DELIMITATION"),
          "If statement body is a single statement without braces; the next statement is"
              + " unconditional (CWE-483)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-483"),
          "org.plumb.style.style.IncorrectBlockDelimitationCheck",
          true);

  @Override
  public String name() {
    return "incorrect-block-delimitation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    Statement thenStmt = n.getThenStmt();
    if (thenStmt instanceof BlockStmt) return; // already braced
    if (thenStmt == null) return;

    // Find enclosing BlockStmt
    Optional<BlockStmt> enclosingOpt = n.findAncestor(BlockStmt.class);
    if (enclosingOpt.isEmpty()) return;
    BlockStmt enclosing = enclosingOpt.get();
    List<Statement> stmts = enclosing.getStatements();
    // Locate this if-stmt's index in the enclosing block
    int idx = -1;
    for (int i = 0; i < stmts.size(); i++) {
      if (stmts.get(i) == n) {
        idx = i;
        break;
      }
    }
    if (idx < 0) return;
    // Must have at least one following statement
    if (idx + 1 >= stmts.size()) return;

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "If statement body is a single statement without braces; the next statement runs"
            + " unconditionally. Did you intend to wrap them in {}?",
        RuleDef.Severity.LOW,
        "incorrect-block-delimitation");
  }
}
