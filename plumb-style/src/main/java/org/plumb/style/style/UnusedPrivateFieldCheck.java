package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * private field is never read.
 *
 * <p>Pattern D R-818: per-class visit + single body walk that collects a {@code name -> count} map
 * for {@link NameExpr}. For each private field, look up its uses in the pre-computed map (O(1)
 * instead of N_field × c.findAll(NameExpr)).
 *
 * <p>Replaces the original visit(CU) + findAll(ClassOrInterfaceDeclaration) + N_field ×
 * c.findAll(NameExpr) ? N×M walks/file down to 2 walks/file.
 */
public final class UnusedPrivateFieldCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-private-field", "UNUSED_PRIVATE_FIELD"),
          "private field is never read",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedPrivateFieldCheck",
          true,
          List.of("ast-style-unused-private-field", "unused-private-field")
      );

  @Override
  public String name() {
    return "unused-private-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    // 1) collect candidate private field names
    java.util.List<VariableDeclarator> privates = new java.util.ArrayList<>();
    for (FieldDeclaration f : c.getFields()) {
      if (!f.isPrivate()) continue;
      for (VariableDeclarator v : f.getVariables()) {
        privates.add(v);
      }
    }
    if (privates.isEmpty()) return;
    // 2) single walk of class body — count NameExpr occurrences
    Map<String, Integer> uses = new HashMap<>();
    c.walk(
        Node.class,
        n -> {
          if (n instanceof NameExpr) {
            NameExpr ne = (NameExpr) n;
            uses.merge(ne.getNameAsString(), 1, Integer::sum);
          }
        });
    // 3) For each private field, check its count.
    // Note: the field's own initializer (if it references itself)
    // doesn't typically happen for private fields — but a
    // single use anywhere in the class counts. (Original was
    // findAll(NameExpr), which also counted initializer.)
    for (VariableDeclarator v : privates) {
      String name = v.getNameAsString();
      int count = uses.getOrDefault(name, 0);
      if (count == 0) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Private field " + name + " is never used",
            RuleDef.Severity.LOW,
            name);
      }
    }
  }
}
