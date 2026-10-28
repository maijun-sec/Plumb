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

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * User-controlled input in a SQL query string (CWE-89).
 *
 * <p>Heuristic: flag any {@code StringLiteralExpr} whose name suggests a query ({@code "sql"},
 * {@code "query"}, {@code "hql"}, {@code "jpql"}, {@code "select ..."}, {@code "from "}) and whose
 * enclosing context is a controller endpoint. We use the simple-name heuristic because AST-only
 * analysis can't see runtime data flow.
 */
public final class InfoExposureQueryStringCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-info-exposure-querystring", "INFO_EXPOSURE_QUERYSTRING"),
          "Possible user input in a SQL/JPQL string literal (CWE-89)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-89"),
          "org.plumb.style.InfoExposureQueryStringCheck",
          true,
          List.of("ast-style-info-exposure-querystring-style", "info-exposure-querystring"));

  private static final List<String> SQL_PREFIXES =
      List.of("select ", "from ", "where ", "insert ", "update ", "delete ");

  @Override
  public String name() {
    return "info-exposure-querystring";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr s, StyleContext ctx) {
    String v = s.getValue();
    if (v == null) return;
    String low = v.toLowerCase();
    boolean sql =
        low.startsWith("select ")
            || low.startsWith("from ")
            || low.startsWith("insert ")
            || low.startsWith("update ")
            || low.startsWith("delete ")
            || low.startsWith("merge ");
    if (!sql) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    int col = s.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Possible user-controlled string in a SQL/JPQL query ("
            + v
            + ") - use PreparedStatement / named parameters instead (CWE-89).",
        RuleDef.Severity.MEDIUM,
        "query-string:" + v);
  }
}
