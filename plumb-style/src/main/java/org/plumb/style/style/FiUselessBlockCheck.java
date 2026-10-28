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
 * useless single-statement block (FI_USELESS_BLOCK) -- drop the braces.
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} + 5 separate
 * {@code n.findAll} calls (IfStmt / WhileStmt / ForStmt / ForEachStmt
 * / DoStmt), each triggering a full AST walk: 5 walks per file.
 * The new implementation uses 5 per-node visit overloads dispatched
 * by {@code AstDispatcher} in a single walk -- 0 redundant walks.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class FiUselessBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-fi-useless-block", "FI_USELESS_BLOCK"),
          "useless single-statement block (FI_USELESS_BLOCK) — drop the braces",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FiUselessBlockCheck",
          true,
          List.of("ast-style-useless-block", "fi-useless-block"));

  @Override
  public String name() {
    return "fi-useless-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt ifs, StyleContext ctx) {
    check(ifs.getThenStmt(), ctx);
    if (ifs.getElseStmt().isPresent() && !(ifs.getElseStmt().get() instanceof IfStmt)) {
      check(ifs.getElseStmt().get(), ctx);
    }
  }

  @Override
  public void visit(WhileStmt w, StyleContext ctx) {
    check(w.getBody(), ctx);
  }

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    check(f.getBody(), ctx);
  }

  @Override
  public void visit(ForEachStmt fe, StyleContext ctx) {
    check(fe.getBody(), ctx);
  }

  @Override
  public void visit(DoStmt d, StyleContext ctx) {
    check(d.getBody(), ctx);
  }

  private void check(Statement s, StyleContext ctx) {
    if (!(s instanceof BlockStmt b)) return;
    if (b.getStatements().size() != 1) return;
    int line = b.getBegin().map(p -> p.line).orElse(-1);
    int col = b.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Useless block with a single statement — drop the braces",
        RuleDef.Severity.LOW,
        "block");
  }
}
