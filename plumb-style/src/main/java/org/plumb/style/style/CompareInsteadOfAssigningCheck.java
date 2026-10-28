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
 * C4: AST-style CWE-482 (Comparing Instead of
 * Assigning) checker.
 *
 * <p>Fires when the source uses {@code ==} or
 * {@code !=} in a context where the canonical
 * (well-formed) code would use {@code =}. The
 * canonical Juliet CWE-482 V01 form is:</p>
 *
 * <pre>{@code
 *   boolean isZero = false;
 *   if ((isZero == (zeroOrOne == 0)) == true) {...}  // FLAW
 * }</pre>
 *
 * <p>The semantic detection shape is
 * "an equality / inequality operator whose left
 * operand is itself a single variable that is
 * written inside the enclosing scope" — a
 * classic `if (x == y)` that should have been
 * `if (x = y)` (the C developer confused the two
 * operators). This is a deliberately narrow
 * detection shape because the false-positive cost
 * of flagging legitimate equality checks is also
 * significant.</p>
 *
 * <p>This checker is <strong>general-purpose</strong>:
 * it pattern-matches on
 * {@link BinaryExpr BinaryExpr} nodes whose
 * operator is {@code ==} or {@code !=} where the
 * left-hand-side is a NameExpr that appears as a
 * single-variable writer in the enclosing method
 * body. Adding a new rule is a single-entry change
 * to {@link #CompareInsteadOfAssigningCheck}; no
 * Juliet-specific hard-coding.</p>
 *
 * <p><strong>Why AST and not taint:</strong> this is
 * a syntactic-equivalence check on operator
 * shape, not a data-flow / taint propagation. AST
 * walking via JavaParser is the right tool.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-482: Comparing Instead of Assigning.
 *
 * <p>Detects {@code (variable == expr)} / {@code (variable != expr)} expressions where the variable
 * is a single-name writer elsewhere in the same method body. The semantic clue that distinguishes
 * TP from FP is the absence of any {@code =} assignment to that variable in the body -- i.e. the
 * variable is <em>never</em> written, so {@code ==} is a legitimate comparison. If the variable IS
 * written elsewhere (e.g. {@code isZero = (zeroOrOne == 0)} appears in the same method), the
 * comparison {@code isZero == (...)} is most likely a typo for {@code isZero = (...)} -- the bug
 * pattern.
 */
public final class CompareInsteadOfAssigningCheck implements AstChecker {

  private static final Set<String> TYPO_FLAT_OPERATORS = Set.of("==", "!=");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-compare-instead-of-assigning", "COMPARE_INSTEAD_OF_ASSIGNING"),
          "Comparison operator may be using == where = was intended (CWE-482)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-482"),
          "org.plumb.style.style.CompareInsteadOfAssigningCheck",
          true,
          List.of("dfa-style-compare-assign-issue", "compare-instead-of-assigning"));

  @Override
  public String name() {
    return "compare-instead-of-assigning";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    BinaryExpr.Operator op = n.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) return;
    // Only emit on a single-name LHS (e.g.
    // {@code isZero == something}). Excludes
    // {@code (a.b == x)}, {@code (1 == x)},
    // and other non-variable LHS shapes where
    // == is unambiguously an equality check.
    if (!(n.getLeft() instanceof NameExpr lhsName)) return;
    String lhsNameStr = lhsName.getNameAsString();
    // Look up the enclosing method body and
    // check whether the LHS variable is
    // ASSIGNED somewhere in it. If not, the
    // == is likely a typo for =. If it IS
    // assigned, the AI prompt filters based on
    // intent.
    MethodDeclaration md = lhsName.findAncestor(MethodDeclaration.class).orElse(null);
    if (md == null) return;
    if (md.getBody().isEmpty()) return;
    if (!isVariableWrittenInBody(md.getBody().get(), lhsNameStr)) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Suspicious '"
            + op.asString()
            + "' on a variable that is "
            + "written elsewhere in this method; did you mean '=' "
            + "(single =) instead of '"
            + op.asString()
            + "'?",
        RuleDef.Severity.MEDIUM,
        "compare-instead-of-assign: " + lhsNameStr);
  }

  /**
   * Returns {@code true} if {@code varName} is written ({@code =} assignment OR variable declarator
   * with non-null initializer) somewhere in {@code body}. The pattern "if (varName vs " then {@code
   * varName = (...)} later" is the classic CWE-482 typo.
   */
  private static boolean isVariableWrittenInBody(BlockStmt body, String varName) {
    // Variable declarations with an
    // initializer (e.g. {@code int x = 5;}).
    for (VariableDeclarationExpr vde : body.findAll(VariableDeclarationExpr.class)) {
      for (VariableDeclarator vd : vde.getVariables()) {
        if (!vd.getNameAsString().equals(varName)) continue;
        if (vd.getInitializer().isPresent()) return true;
      }
    }
    // Assignment statements
    // ({@code varName = ...}).
    for (AssignExpr ae : body.findAll(AssignExpr.class)) {
      Expression t = ae.getTarget();
      if (t instanceof NameExpr ne && ne.getNameAsString().equals(varName)) {
        return true;
      }
    }
    return false;
  }
}
