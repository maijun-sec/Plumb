package org.plumb.style.style;

import com.github.javaparser.ast.stmt.ForEachStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** map entry object reused across iterations (PZ_DONT_REUSE_ENTRY_OBJECTS_IN_ITERATORS). */
public final class PzReuseEntryCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-pz-reuse-entry", "PZ_REUSE_ENTRY"),
          "map entry object reused across iterations (PZ_DONT_REUSE_ENTRY_OBJECTS_IN_ITERATORS)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-672"),
          "org.plumb.style.PzReuseEntryCheck",
          true,
          List.of("ast-style-reuse-entry-objects", "pz-reuse-entry")
      );

  @Override
  public String name() {
    return "pz-reuse-entry";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    // The variable name is in the iterator declaration
    // (e.g. `for (Map.Entry e: m.entrySet())`).
    // We flag the loop if the body has more than 3
    // statements : a heuristic that the entry is
    // being processed beyond the simple read.
    if (n.getBody() instanceof com.github.javaparser.ast.stmt.BlockStmt b) {
      if (b.getStatements().size() > 3) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "for-each over entrySet with > 3 stmts : " + "Map.Entry may be reused by some maps",
            RuleDef.Severity.MEDIUM,
            "entry");
      }
    }
  }
}
