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
 * AST-style CWE-571 (Expression is Always True) checker pattern, applied to
 * cyclomatic complexity. Walks the method body ONCE with a single VoidVisitorAdapter
 * that increments the counter on each decision point, instead of issuing 8 separate
 * {@code findAll} calls (one per node type). M3 audit.
 */
package org.plumb.style.design;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Cyclomatic complexity exceeds the configured threshold (default 10). */
public final class CyclomaticComplexityCheck implements AstChecker {

  /**
   * Default threshold. Kept as a constant for now; if we ever want per-project config, lift to a
   * {@code RuleParameter} like the other configurable checks.
   */
  private static final int DEFAULT_THRESHOLD = 10;

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-cyclomatic-complexity", "CYCLOMATIC_COMPLEXITY"),
          "cyclomatic complexity exceeds " + DEFAULT_THRESHOLD,
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CyclomaticComplexityCheck",
          true,
          List.of("ast-design-cyclomatic-complexity", "cyclomatic-complexity"));

  @Override
  public String name() {
    return "cyclomatic-complexity";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;

    // M3: single-walk implementation. The
    // previous version issued 8 separate findAll() calls
    // (one per node type), which is 8 full traversals of
    // the method body. We use a VoidVisitorAdapter that
    // bumps the counter on each decision point. This
    // matches the canonical PMD / Checkstyle formula:
    //   cc = 1 + (branches) + (loops) + (catch clauses)
    //       (conditional expressions) + (case labels)
    //       (&& and || short-circuit operators)
    final int[] cc = {1};
    n.getBody()
        .get()
        .accept(
            new VoidVisitorAdapter<Void>() {
              @Override
              public void visit(IfStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(WhileStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(DoStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(ForStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(ForEachStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(CatchClause n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(ConditionalExpr n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
              }

              @Override
              public void visit(SwitchStmt n, Void arg) {
                // Each non-empty case label adds a path. We
                // count all entries that carry at least one
                // label; a fall-through `default:` alone
                // doesn't add a branch.
                for (SwitchEntry e : n.getEntries()) {
                  if (!e.getLabels().isEmpty()) cc[0]++;
                }
                super.visit(n, arg);
              }

              @Override
              public void visit(BinaryExpr n, Void arg) {
                // Short-circuit operators each add a path.
                if (n.getOperator() == BinaryExpr.Operator.AND
                    || n.getOperator() == BinaryExpr.Operator.OR) {
                  cc[0]++;
                }
                super.visit(n, arg);
              }
            },
            null);

    if (cc[0] > DEFAULT_THRESHOLD) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Cyclomatic complexity "
              + cc[0]
              + " exceeds "
              + DEFAULT_THRESHOLD
              + " in method "
              + n.getNameAsString(),
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }
}
