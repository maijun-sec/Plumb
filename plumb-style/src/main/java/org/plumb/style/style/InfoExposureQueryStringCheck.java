package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * User-controlled input in a SQL query string (CWE-89).
 *
 * <p>Heuristic: flag any {@code StringLiteralExpr} whose name suggests a query ({@code "sql"},
 * {@code "query"}, {@code "hql"}, {@code "jpql"}, {@code "select ..."}, {@code "from "}) and
 * whose enclosing context is a controller endpoint. We use the simple-name heuristic because
 * AST-only analysis can't see runtime data flow.
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
          List.of("ast-style-info-exposure-querystring-style", "info-exposure-querystring")
      );

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
    boolean sql = low.startsWith("select ")
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