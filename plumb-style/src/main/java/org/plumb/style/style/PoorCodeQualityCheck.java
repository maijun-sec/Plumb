package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-398 (Indicator of Poor Code Quality) ? file-wide check for empty blocks, cases, methods. */
public final class PoorCodeQualityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-poor-code-quality", "POOR_CODE_QUALITY"),
          "Indicator of poor code quality (CWE-398): empty block / case / else / function.",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-398"),
          "org.plumb.style.PoorCodeQualityCheck",
          true,
          List.of("ast-style-poor-code-quality-style", "poor-code-quality"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "poor-code-quality";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void visit(BlockStmt bs, StyleContext ctx) {
    if (isEffectivelyEmpty(bs)) {
      int line = bs.getBegin().map(p -> p.line).orElse(-1);
      String key = "block|" + line;
      if (reported.add(key)) {
        fireEmpty(ctx, line, "empty block");
      }
    }
  }

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    BlockStmt body = m.getBody().orElse(null);
    if (body == null) return;
    if (isEffectivelyEmpty(body)) {
      int line = body.getBegin().map(p -> p.line).orElse(-1);
      String key = "func|" + m.getNameAsString() + "|" + line;
      if (reported.add(key)) {
        ctx.report(
            line,
            -1,
            DEFAULT_RULE,
            "Empty function body in '" + m.getNameAsString() + "()' (CWE-398).",
            RuleDef.Severity.LOW,
            "empty-function@" + m.getNameAsString());
      }
    }
  }

  private static boolean isEffectivelyEmpty(BlockStmt bs) {
    if (bs.getStatements().isEmpty()) return true;
    for (Statement s : bs.getStatements()) {
      if (!(s instanceof com.github.javaparser.ast.stmt.EmptyStmt)) return false;
    }
    return true;
  }

  private static void fireEmpty(StyleContext ctx, int line, String kind) {
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        kind + " detected at line " + line + " (CWE-398: indicator of poor code quality).",
        RuleDef.Severity.LOW,
        "empty-block@line " + line);
  }
}
