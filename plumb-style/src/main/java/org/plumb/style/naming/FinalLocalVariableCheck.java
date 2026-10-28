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
 * local variable never reassigned should be final.
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(MethodDeclaration)} (1 walk) + per-method
 * {@code m.findAll(VariableDeclarator)} + for EACH variable a
 * {@code m.findAll(AssignExpr)} -- 1 + N_v + N_v walks per file.
 * The new implementation uses {@code visit(MethodDeclaration)} +
 * {@code visit(VariableDeclarationExpr)} +
 * {@code visit(AssignExpr)} dispatched by {@code AstDispatcher}
 * in a single walk. Per-method state: a {@code Set<String>} of
 * reassigned names, populated by
 * {@link #visit(AssignExpr, StyleContext)}; per-variable decision
 * is delayed to {@link #finish} so reassignments that appear
 * AFTER the variable declaration are correctly counted. The
 * 1 N_v + N_v walks → 1 walk per file.</p>
 *
 * <p>Note: {@code AstChecker} exposes
 * {@code visit(VariableDeclarationExpr)} (not
 * {@code visit(VariableDeclarator)}), so we iterate
 * {@code vde.getVariables()} inside that handler.</p>
 */
package org.plumb.style.naming;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class FinalLocalVariableCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-final-local-variable", "FINAL_LOCAL_VARIABLE"),
          "local variable never reassigned should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FinalLocalVariableCheck",
          true,
          List.of("ast-naming-local-variable", "final-local-variable"));

  @Override
  public String name() {
    return "final-local-variable";
  }

  @Override
  public void init(StyleContext ctx) {
    reassignedNames.clear();
    pendingDeclarators.clear();
    inMethod = false;
  }

  /** Per-method set of names that are reassigned anywhere in the current method. */
  private final Set<String> reassignedNames = new HashSet<>();

  /**
   * Per-method queue of declarators seen but not yet evaluated. Decision delayed to {@link #finish}
   * so post-declaration reassignments are correctly counted.
   */
  private final List<PendingDecl> pendingDeclarators = new ArrayList<>();

  /** Whether we are currently inside a method body. */
  private boolean inMethod = false;

  private record PendingDecl(String name, int line, int col) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    flushPending(ctx);
    reassignedNames.clear();
    pendingDeclarators.clear();
    inMethod = true;
  }

  @Override
  public void visit(AssignExpr ae, StyleContext ctx) {
    if (!inMethod) return;
    if (ae.getTarget() instanceof NameExpr ne) {
      reassignedNames.add(ne.getNameAsString());
    }
  }

  @Override
  public void visit(VariableDeclarationExpr vde, StyleContext ctx) {
    if (!inMethod) return;
    if (vde.isFinal()) return;
    for (VariableDeclarator v : vde.getVariables()) {
      // Skip if the declarator's parent is a FieldDeclaration
      // (i.e. this is a class-level field declaration). Local
      // variable declarations never have a FieldDeclaration
      // as their VariableDeclarationExpr's parent — only
      // VariableDeclarator's parent is the FieldDeclaration.
      // So we check the VariableDeclarator's parent.
      if (v.getParentNode().isPresent() && v.getParentNode().get() instanceof FieldDeclaration) {
        continue;
      }
      pendingDeclarators.add(
          new PendingDecl(
              v.getNameAsString(),
              v.getBegin().map(p -> p.line).orElse(-1),
              v.getBegin().map(p -> p.column).orElse(-1)));
    }
  }

  @Override
  public void finish(StyleContext ctx) {
    flushPending(ctx);
    inMethod = false;
  }

  private void flushPending(StyleContext ctx) {
    for (PendingDecl d : pendingDeclarators) {
      if (reassignedNames.contains(d.name())) continue;
      ctx.report(
          d.line(),
          d.col(),
          DEFAULT_RULE,
          "Local variable " + d.name() + " should be final",
          RuleDef.Severity.LOW,
          d.name());
    }
    pendingDeclarators.clear();
  }
}
