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
package org.plumb.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.plumb.reporter.Finding;

/**
 * A single-pass AST checker.
 *
 * <p>{@link AstDispatcher} walks the {@link CompilationUnit} once via a single parent visitor and
 * dispatches each node to every registered checker's per-type visit method. For ~600 checkers and a
 * 1000-node file this is a ~600x speedup on the dispatch loop (vs. one walk per checker).
 *
 * <p>Each check implements only the per-type methods it cares about; the rest are default no-ops.
 * The dispatcher is responsible for walking the children of each node (so the checks must
 * <em>not</em> call into {@link com.github.javaparser.ast.Node#walk} on their own -- that is the
 * dispatcher's job).
 *
 * <p><b></b> the prior {@code StyleCheck} interface has been retired -- all 636 checkers now
 * implement {@code AstChecker} (the strict superset). There is no longer any other checker
 * interface; {@link AstCheckerRegistry} is the single registration entry point.
 */
public interface AstChecker {

  /** Human-readable name; used in logs and timing reports. */
  String name();

  /**
   * Called once before the AST walk. Receives the per-file context (style findings accumulate
   * here).
   */
  void init(StyleContext ctx);

  /** Called once after the AST walk. Default no-op. */
  default void finish(StyleContext ctx) {}

  /**
   * single source of truth for this checker's rule metadata. The default implementation reflects
   * the checker's class for a static field named {@code DEFAULT_RULE} (or {@code RULE}, or {@code
   * <UpperCamel>_RULE}) of type {@link org.plumb.rules.RuleDef}. If found, the value is returned.
   * If not, a synthetic {@code RuleDef} is derived from {@link #name()} ({@code "kebab-case"}
   * becomes {@code "UPPER_SNAKE_CASE"}) and the FQCN of the checker class.
   *
   * <p>The {@link org.plumb.style.AstCheckerRegistry} uses this to populate the {@link
   * org.plumb.rules.RuleRegistry} at registration time, and {@link StyleContext#report} uses it as
   * the source of {@code RuleDef} on each emitted finding. The legacy 115 hard-coded entries in
   * {@code RuleRegistry.withBuiltinDefaults()} are kept for the ~25 DFA checkers that are not
   * {@code AstChecker}s.
   */
  default org.plumb.rules.RuleDef rule() {
    Class<?> cls = getClass();
    for (String f : new String[] {"DEFAULT_RULE", "RULE"}) {
      try {
        java.lang.reflect.Field field = cls.getDeclaredField(f);
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
          field.setAccessible(true);
          Object v = field.get(null);
          if (v instanceof org.plumb.rules.RuleDef rd) {
            return rd;
          }
        }
      } catch (NoSuchFieldException ignored) {
        // try next candidate name
      } catch (IllegalAccessException ex) {
        // skip — try next
      }
    }
    // //use the new `<engine>-<topic>-<slug>`
    // format so the synthetic id matches the convention.
    // Engine is always "ast" (DFA checkers don't fall through
    // this path - they are not AstCheckers). Topic is the
    // class's package leaf. Slug is `name()` (kebab-case).

    // Fallback: derive a synthetic RuleDef from the
    // checker's name() convention. Lower-fidelity than
    // DEFAULT_RULE but sufficient for the registry index.
    String n = name();
    if (n == null || n.isEmpty()) return null;
    String fqn = cls.getName();
    String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
    String topic = pkg.isEmpty() ? "misc" : pkg.substring(pkg.lastIndexOf('.') + 1);
    String id = "ast-" + topic.toLowerCase(java.util.Locale.ROOT) + "-" + n;
    String upperSnake = n.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    return new org.plumb.rules.RuleDef(
        new org.plumb.rules.RuleId(id, upperSnake),
        upperSnake,
        org.plumb.rules.RuleDef.Severity.INFO,
        org.plumb.rules.RuleDef.Type.STYLE,
        java.util.List.of(),
        fqn,
        true);
  }

  /**
   * The rule-specific knowledge the LLM is told about this check before it judges a finding.
   *
   * <p>Default {@code null} (no AI dedup). Override on the checks that benefit from LLM judgement.
   */
  default String getAiPrompt(Finding finding) {
    return null;
  }

  // per-node-type visit methods (default no-op) --

  default void visit(CompilationUnit n, StyleContext ctx) {}

  default void visit(ImportDeclaration n, StyleContext ctx) {}

  default void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {}

  default void visit(EnumDeclaration n, StyleContext ctx) {}

  default void visit(FieldDeclaration n, StyleContext ctx) {}

  default void visit(MethodDeclaration n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.body.ConstructorDeclaration n, StyleContext ctx) {}

  default void visit(IfStmt n, StyleContext ctx) {}

  default void visit(SwitchStmt n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.stmt.SwitchEntry n, StyleContext ctx) {}

  default void visit(SynchronizedStmt n, StyleContext ctx) {}

  default void visit(WhileStmt n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.stmt.AssertStmt n, StyleContext ctx) {}

  default void visit(ForStmt n, StyleContext ctx) {}

  default void visit(ForEachStmt n, StyleContext ctx) {}

  default void visit(DoStmt n, StyleContext ctx) {}

  default void visit(BlockStmt n, StyleContext ctx) {}

  default void visit(EmptyStmt n, StyleContext ctx) {}

  default void visit(ExpressionStmt n, StyleContext ctx) {}

  default void visit(ReturnStmt n, StyleContext ctx) {}

  default void visit(TryStmt n, StyleContext ctx) {}

  default void visit(BinaryExpr n, StyleContext ctx) {}

  default void visit(CatchClause n, StyleContext ctx) {}

  default void visit(LabeledStmt n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.stmt.BreakStmt n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.stmt.ContinueStmt n, StyleContext ctx) {}

  default void visit(MethodCallExpr n, StyleContext ctx) {}

  default void visit(ObjectCreationExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.ArrayAccessExpr n, StyleContext ctx) {}

  default void visit(FieldAccessExpr n, StyleContext ctx) {}

  default void visit(CastExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.type.ClassOrInterfaceType n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.VariableDeclarationExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.NameExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.IntegerLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.LongLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.DoubleLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.CharLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.BooleanLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.StringLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.MarkerAnnotationExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.TextBlockLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.NullLiteralExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.ConditionalExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.ArrayCreationExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.ArrayInitializerExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.body.VariableDeclarator n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.UnaryExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.InstanceOfExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.AssignExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.expr.EnclosedExpr n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.stmt.ThrowStmt n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.body.InitializerDeclaration n, StyleContext ctx) {}

  default void visit(com.github.javaparser.ast.body.Parameter n, StyleContext ctx) {}
}
