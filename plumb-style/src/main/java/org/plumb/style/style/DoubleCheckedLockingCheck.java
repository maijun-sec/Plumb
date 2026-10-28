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

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-609 (Double-Checked Locking).
 *
 * <p>Detects the classic anti-pattern:
 *
 * <pre>{@code
 * private static String x = null;            // FLAW: not volatile
 *
 * public static String helper() {
 *     if (x == null) {
 *         synchronized(Klass.class) {
 *             if (x == null) {
 *                 x = "value";
 *             }
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Without the {@code volatile} modifier on the target field, the inner null check can see a
 * partially-constructed object on a thread other than the one that performed the initialization
 * (the JMM publication guarantee requires {@code volatile} or equivalent).
 *
 * <p>Detection strategy:
 *
 * <ol>
 *   <li>Walk every {@link IfStmt} via the standard {@code AstDispatcher}. The condition must be
 *       {@code X == null} (a {@link BinaryExpr} with {@code EQUALS} operator and a {@link
 *       NullLiteralExpr} on the right side).
 *   <li>Inside the "then" body, look for a {@link SynchronizedStmt} whose own body contains an
 *       inner {@code if (X == null) { X = ... }} (target name matches and a field / variable
 *       assignment follows).
 *   <li>Resolve the target identifier. If it names a {@link FieldDeclaration} that does NOT carry
 *       the {@code volatile} modifier, fire the finding. Local variables and parameters are skipped
 *       (the JVM stack is thread-safe; only static / instance fields are affected by the JMM
 *       publication issue).
 * </ol>
 *
 * <p>Equivalent safe idioms — a {@code volatile} field, a method-level {@code synchronized}, a
 * fully-synchronized block, or a {@code ReentrantLock} — do not have the double-check shape and are
 * correctly not reported.
 */
public final class DoubleCheckedLockingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          // rule_id aligned with
          // plumb_rule_to_cwe.json (`Cwe609` →
          // ["609"]). Legacy id
          // `dfa.cwe609-double-checked-locking` is
          // preserved as the short_name so any
          // external whitelist still matches
          // (Lesson 1154).
          new RuleId("ast-style-double-checked-locking", "DOUBLE_CHECKED_LOCKING"),
          "double-checked locking on a non-volatile field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-609"),
          "org.plumb.style.DoubleCheckedLockingCheck",
          true,
          List.of("ast-style-double-checked-locking-style", "double-checked-locking"));

  @Override
  public String name() {
    return "double-checked-locking";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    String var = null;
    Statement thenBody;
    // Outer if (X == null) { ... } → recurse into
    // the "then" branch looking for a synchronized
    // block.
    if (n.getElseStmt().isPresent()) return;
    var = extractNullCheckVar(n.getCondition());
    if (var == null) return;
    thenBody = n.getThenStmt();
    if (!(thenBody instanceof BlockStmt)) return;
    // Walk the block looking for a SynchronizedStmt
    // whose body contains an inner if (X == null) {
    // X = ... }.
    for (Statement s : ((BlockStmt) thenBody).getStatements()) {
      if (!(s instanceof SynchronizedStmt sync)) continue;
      // inner must contain: if (X == null) { X = value; }
      Statement inner = sync.getBody();
      AssignExpr assign = findInnerAssignNullCheck(inner, var);
      if (assign == null) continue;
      // the target `X` must be a field
      // declaration that does NOT carry the
      // `volatile` modifier. Local variables
      // are not affected by the JMM publication
      // issue.
      if (!isNonVolatileField(n, var)) continue;
      int line = -1, col = -1;
      java.util.Optional<com.github.javaparser.Position> begin = n.getBegin();
      if (begin != null) {
        com.github.javaparser.Position p = begin.orElse(null);
        if (p != null) {
          line = p.line;
          col = p.column;
        }
      }
      // (Lesson 1165): set sinkFunction
      // from the enclosing MethodDeclaration so
      // the eval harness can locate it.
      String sink = null;
      try {
        MethodDeclaration enc = n.findAncestor(MethodDeclaration.class).orElse(null);
        if (enc != null) {
          StringBuilder sig = new StringBuilder();
          sig.append(enc.getNameAsString()).append('(');
          com.github.javaparser.ast.NodeList<com.github.javaparser.ast.body.Parameter> params =
              enc.getParameters();
          for (int i = 0; i < params.size(); i++) {
            if (i > 0) sig.append(',');
            sig.append(params.get(i).getType().asString());
          }
          sig.append("):");
          sig.append(enc.getType().asString());
          sink = sig.toString();
        }
      } catch (RuntimeException ignore) {
        // best-effort
      }
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Double-checked locking on non-volatile " + "field `" + var + "` (CWE-609).",
          RuleDef.Severity.MEDIUM,
          "dcl@" + var,
          sink);
      // Only one finding per outer if — the same
      // outer if's dcl pattern fires once even if
      // there are multiple inner dcl candidates.
      return;
    }
  }

  /**
   * Recognize {@code X == null} condition. Returns the name of the variable on the left side, or
   * null if the expression doesn't match.
   */
  private static String extractNullCheckVar(Expression e) {
    if (!(e instanceof BinaryExpr be)) return null;
    if (be.getOperator() != BinaryExpr.Operator.EQUALS) return null;
    Expression rhs = be.getRight();
    Expression lhs = be.getLeft();
    if (!(rhs instanceof NullLiteralExpr)) return null;
    // LHS must be a simple name reference (we
    // don't handle obj.field == null here; the
    // dcl pattern is always a bare
    // static-field access).
    if (lhs instanceof NameExpr ne) return ne.getNameAsString();
    return null;
  }

  /**
   * Inside the {@link SynchronizedStmt} body, find an inner {@code if (X == null) { X = expr; }}
   * where the assignment target is X. Returns the {@link AssignExpr} (for potential future message
   * use) or null.
   */
  private static AssignExpr findInnerAssignNullCheck(Statement inner, String var) {
    if (!(inner instanceof BlockStmt block)) return null;
    for (Statement s : block.getStatements()) {
      if (!(s instanceof IfStmt innerIf)) continue;
      if (innerIf.getElseStmt().isPresent()) continue;
      String innerVar = extractNullCheckVar(innerIf.getCondition());
      if (innerVar == null || !innerVar.equals(var)) continue;
      Statement innerThen = innerIf.getThenStmt();
      if (!(innerThen instanceof BlockStmt thenBlock)) continue;
      for (Statement ts : thenBlock.getStatements()) {
        if (!(ts instanceof ExpressionStmt es)) continue;
        Expression expr = es.getExpression();
        if (!(expr instanceof AssignExpr ae)) continue;
        Expression target = ae.getTarget();
        if (target instanceof NameExpr ne && ne.getNameAsString().equals(var)) {
          return ae;
        }
      }
    }
    return null;
  }

  /**
   * True if {@code varName} names a field declaration in the enclosing CompilationUnit whose
   * modifier list does NOT include {@code volatile}. Local variables and parameters are skipped.
   */
  private static boolean isNonVolatileField(IfStmt n, String varName) {
    // Find the enclosing CompilationUnit.
    com.github.javaparser.ast.Node root = n.findRootNode();
    if (!(root instanceof com.github.javaparser.ast.CompilationUnit cu)) return false;
    final boolean[] match = {false};
    cu.walk(
        FieldDeclaration.class,
        fd -> {
          // If the field name doesn't match, skip.
          boolean nameMatch = false;
          for (VariableDeclarator vd : fd.getVariables()) {
            if (vd.getNameAsString().equals(varName)) {
              nameMatch = true;
              break;
            }
          }
          if (!nameMatch) return;
          // If the field is volatile, skip.
          boolean isVolatile = false;
          for (Modifier m : fd.getModifiers()) {
            if (m.getKeyword() == Modifier.Keyword.VOLATILE) {
              isVolatile = true;
              break;
            }
          }
          if (!isVolatile) {
            match[0] = true;
          }
        });
    return match[0];
  }
}
