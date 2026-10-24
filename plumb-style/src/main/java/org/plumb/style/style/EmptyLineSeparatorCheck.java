/*
 * blank line between methods (strict).
 */
package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.
// Note: this checker needs cross-method state (compare each method to the next one),
// so the implementation uses init() to collect the list of methods in document order
// and finish() to fire per-pair reports.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.ArrayList;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class EmptyLineSeparatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-emptylineseparatorcheck-cs", "EMPTYLINESEPARATORCHECK_CS"),
          "blank line between methods (strict)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyLineSeparatorCheck",
          true,
          List.of("ast-style-line-separator-strict", "emptylineseparatorcheck_cs"));

  private final List<MethodDeclaration> methods = new ArrayList<>();

  @Override
  public String name() {
    return "emptylineseparatorcheck-cs";
  }

  @Override
  public void init(StyleContext ctx) {
    methods.clear();
    com.github.javaparser.ast.CompilationUnit unit = ctx.source().unit();
    if (unit != null) {
      methods.addAll(unit.findAll(MethodDeclaration.class));
    }
  }

  @Override
  public void finish(StyleContext ctx) {
    for (int i = 0; i + 1 < methods.size(); i++) {
      MethodDeclaration prev = methods.get(i);
      MethodDeclaration next = methods.get(i + 1);
      int endOfPrev = prev.getEnd().map(p -> p.line).orElse(-1);
      int startOfNext = next.getBegin().map(p -> p.line).orElse(-1);
      if (startOfNext - endOfPrev < 2) {
        int line = next.getBegin().map(p -> p.line).orElse(-1);
        int col = next.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Add blank line before method " + next.getNameAsString(),
            RuleDef.Severity.LOW,
            next.getNameAsString());
      }
    }
    methods.clear();
  }
}
