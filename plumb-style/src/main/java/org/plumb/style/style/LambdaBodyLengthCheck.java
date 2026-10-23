package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** lambda body has too many statements (, default 3). */
public final class LambdaBodyLengthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-lambda-body-length", "LAMBDA_BODY_LENGTH"),
          "lambda body has too many statements (, default 3)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LambdaBodyLengthCheck",
          true,
          List.of("ast-style-lambda-body-length", "lambda-body-length")
      );

  private static final int DEFAULT_MAX = 3;

  @Override
  public String name() {
    return "lambda-body-length";
  }

  @Override
  public void init(StyleContext ctx) {}

    public void visit(LambdaExpr lambda, StyleContext ctx) {
    Statement body = lambda.getBody();
    if (!(body instanceof BlockStmt)) return;
    BlockStmt b = (BlockStmt) body;
    int count = b.getStatements().size();
    if (count > DEFAULT_MAX) {
      int line = lambda.getBegin().map(p -> p.line).orElse(-1);
      int col = lambda.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Lambda body has "
              + count
              + " statements (limit "
              + DEFAULT_MAX
              + ") —extract to a method",
          RuleDef.Severity.LOW,
          "lambda");
    }
  }
}
