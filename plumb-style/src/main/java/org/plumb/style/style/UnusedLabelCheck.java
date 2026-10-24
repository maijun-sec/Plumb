package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** label is never used (dead code). */
public final class UnusedLabelCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-label", "UNUSED_LABEL"),
          "label is never used (dead code)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedLabelCheck",
          true,
          List.of("ast-style-unused-label", "unused-label"));

  @Override
  public String name() {
    return "unused-label";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    Set<String> labels = new HashSet<>();
    n.findAll(LabeledStmt.class).forEach(l -> labels.add(l.getLabel().asString()));
    if (labels.isEmpty()) return;
    Set<String> used = new HashSet<>();
    n.findAll(BreakStmt.class)
        .forEach(b -> b.getLabel().ifPresent(name -> used.add(name.asString())));
    n.findAll(ContinueStmt.class)
        .forEach(c -> c.getLabel().ifPresent(name -> used.add(name.asString())));
    for (LabeledStmt l : n.findAll(LabeledStmt.class)) {
      if (!used.contains(l.getLabel().asString())) {
        int line = l.getBegin().map(p -> p.line).orElse(-1);
        int col = l.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Label '" + l.getLabel().asString() + "' is never used",
            RuleDef.Severity.LOW,
            l.getLabel().asString());
      }
    }
  }
}
