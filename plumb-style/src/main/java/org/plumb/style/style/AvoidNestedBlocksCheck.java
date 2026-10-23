package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Avoid nested blocks. */
public final class AvoidNestedBlocksCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-nested-blocks", "AVOID_NESTED_BLOCKS"),
          "avoid nested blocks",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidNestedBlocksCheck",
          true,
          List.of("ast-style-nested-blocks", "avoid-nested-blocks")
      );

  @Override
  public String name() {
    return "avoid-nested-blocks";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BlockStmt b, StyleContext ctx) {
    if (!b.getParentNode().isPresent()) return;
    Node parent = b.getParentNode().get();
    if (parent instanceof IfStmt
        || parent instanceof ForStmt
        || parent instanceof ForEachStmt
        || parent instanceof WhileStmt
        || parent instanceof TryStmt
        || parent instanceof MethodDeclaration
        || parent instanceof ConstructorDeclaration
        || parent instanceof SwitchEntry
        || parent instanceof LabeledStmt
        || parent instanceof SynchronizedStmt) {
      return;
    }
    int line = b.getBegin().map(p -> p.line).orElse(-1);
    int col = b.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid nested block in " + parent.getClass().getSimpleName(),
        RuleDef.Severity.LOW,
        "block");
  }
}