package org.plumb.style.naming;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Outer type filename should match the type name.
 *
 * <p>This rule is intrinsically per-file (one check per {@code CompilationUnit}) -- no useful
 * per-node-type migration here. Kept as {@code visit(CompilationUnit)} since the dispatcher hands
 * the file root directly.
 */
public final class OuterTypeFilenameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-outer-type-filename", "OUTER_TYPE_FILENAME"),
          "Outer type filename should match class name",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OuterTypeFilenameCheck",
          true,
          List.of("ast-naming-type-filename", "outer-type-filename"));

  @Override
  public String name() {
    return "outer-type-filename";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getTypes().isEmpty() || n.getStorage().isEmpty()) return;
    String filename = n.getStorage().get().getFileName();
    String classname = n.getType(0).getNameAsString();
    if (filename == null || filename.equals(classname + ".java")) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Outer type filename should match class name: " + filename,
        RuleDef.Severity.LOW,
        classname);
  }
}
