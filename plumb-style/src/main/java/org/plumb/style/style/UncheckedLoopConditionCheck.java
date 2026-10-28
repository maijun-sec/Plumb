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
 * Flags a loop whose condition is not checked, so the loop may run forever
 * (CWE-674).
 *
 * <p>The DFA source at
 * {@code plumb-dfa/.../checkers/UncheckedLoopConditionChecker.java}
 * is a deprecated placeholder -- the active
 * implementation lives in
 * {@code plumb-dfa/.../checkers/taint/UncheckedLoopConditionChecker.java}
 * (CWE-606 tainted loop bound). This AST check is the
 * user-promised CWE-674 sketch: fires on
 * {@code while(true)} / {@code for(;;)} whose body has
 * no break / return / throw.</p>
 *
 * <p>Note: a richer CWE-835 implementation already lives
 * at {@code plumb-style/.../InfiniteLoopCheck.java}.
 * This rule is intentionally narrower -- constant-true
 * loops only, no modulo proof -- and uses a distinct CWE
 * label (CWE-674 = unchecked recursion / unbounded loop)
 * so the two rules don't double-fire.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-674: unchecked loop condition (constant-true loop with no exit). */
public final class UncheckedLoopConditionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unchecked-loop-condition", "UNCHECKED_LOOP_CONDITION"),
          "Loop has a constant-true condition (while(true) / for(;;)) "
              + "but the body has no break / return / throw — "
              + "unbounded loop (CWE-674)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of("CWE-674"),
          "org.plumb.style.style.UncheckedLoopConditionCheck",
          true,
          List.of("ast-style-unchecked-loop-condition", "unchecked-loop-condition"));

  @Override
  public String name() {
    return "unchecked-loop-condition";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (!isTrueLiteral(n.getCondition())) return;
    if (hasExit(n.getBody())) return;
    report(n, ctx, "while(true)");
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // `for(;;)` — no init / no compare / no update.
    if (n.getInitialization().size() != 0) return;
    if (n.getCompare().isPresent()) return;
    if (n.getUpdate().size() != 0) return;
    if (hasExit(n.getBody())) return;
    report(n, ctx, "for(;;)");
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    if (!isTrueLiteral(n.getCondition())) return;
    if (hasExit(n.getBody())) return;
    report(n, ctx, "do { ... } while(true)");
  }

  /**
   * True if {@code n} (an Expression) is the literal {@code true}. Recognises the {@link
   * com.github.javaparser.ast.expr.BooleanLiteralExpr} form. We intentionally do not chase unary
   * negation (i.e. {@code !false}) -- those are usually intentional placeholders for code being
   * fleshed out and emit too many FPs.
   */
  private static boolean isTrueLiteral(com.github.javaparser.ast.expr.Expression n) {
    return n instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr ble && ble.getValue();
  }

  /**
   * True if the loop body contains a {@code break} / {@code return} / {@code throw} that exits the
   * loop. The check is intentionally shallow -- a deep control- flow analysis (e.g. {@code break}
   * inside an inner loop or {@code if (a) return;} inside a nested block) is left to the
   * value-bounded proof in {@code InfiniteLoopCheck}.
   */
  private static boolean hasExit(Statement body) {
    if (body == null) return false;
    for (BreakStmt b : body.findAll(BreakStmt.class)) {
      if (loopExitsThisStmt(b, body)) return true;
    }
    if (!body.findAll(ReturnStmt.class).isEmpty()) return true;
    if (!body.findAll(ThrowStmt.class).isEmpty()) return true;
    return false;
  }

  /**
   * Does this {@code break} exit {@code enclosingLoopBody}? A bare {@code break;} exits the
   * innermost loop in the source tree; a labelled {@code break label;} exits the loop whose {@code
   * LabeledStmt} ancestor carries that label. We treat a bare {@code break} as exiting the nearest
   * enclosing loop -- which the inner loop, not the body under test, in most cases. For the
   * conservative sketch we require the break to be in the same BlockStmt as the loop body's
   * first-level statements. This misses {@code break} inside a nested if/else but stays
   * FP-resistant.
   */
  private static boolean loopExitsThisStmt(BreakStmt b, Statement enclosing) {
    if (b.getLabel().isPresent()) {
      // A labelled break is unambiguous: it exits the
      // matching label, which is the loop being
      // audited.
      return true;
    }
    // Bare break: walk up to find the nearest loop.
    // If that loop's body is `enclosing`, this break
    // exits `enclosing`. If the nearest loop is an
    // INNER loop, the bare break exits that inner loop
    // — NOT `enclosing`. We must not treat this as an
    // exit for the outer loop.
    java.util.Optional<Node> parent = b.getParentNode();
    while (parent.isPresent()) {
      Node p = parent.get();
      if (p instanceof ForStmt || p instanceof WhileStmt || p instanceof DoStmt) {
        Statement bodyOfP;
        if (p instanceof ForStmt fp) bodyOfP = fp.getBody();
        else if (p instanceof WhileStmt wp) bodyOfP = wp.getBody();
        else bodyOfP = ((DoStmt) p).getBody();
        return bodyOfP == enclosing;
      }
      parent = p.getParentNode();
    }
    return false;
  }

  private static void report(Node n, StyleContext ctx, String kind) {
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        kind + " has no break / return / throw — " + "unbounded loop (CWE-674)",
        RuleDef.Severity.LOW,
        "unchecked-loop-condition: " + kind);
  }
}
