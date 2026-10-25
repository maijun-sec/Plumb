/*
 * modifier order should be public/protected/private static final abstract.
 *
 * <p><b>pattern A rewrite.</b> The previous
 * implementation used 2 findAll (FieldDeclaration +
 * MethodDeclaration). The new implementation uses a single
 * walk with instanceof dispatch. Total: 1 walk per file.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.ArrayList;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ModifierOrderCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-modifier-order", "MODIFIER_ORDER"),
          "modifier order should be public/protected/private static final abstract",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ModifierOrderCheck",
          true,
          List.of("ast-style-modifier-order", "modifier-order"));

  @Override
  public String name() {
    return "modifier-order";
  }

  @Override
  public void init(StyleContext ctx) {}

  private void checkOrder(List<Modifier> mods, int line, int col, String name, StyleContext ctx) {
    int lastP = -1, lastS = -1, lastF = -1, lastA = -1;
    for (int i = 0; i < mods.size(); i++) {
      Modifier m = mods.get(i);
      Modifier.Keyword k = m.getKeyword();
      if (k == Modifier.Keyword.PUBLIC
          || k == Modifier.Keyword.PROTECTED
          || k == Modifier.Keyword.PRIVATE) lastP = i;
      else if (k == Modifier.Keyword.STATIC) lastS = i;
      else if (k == Modifier.Keyword.FINAL) lastF = i;
      else if (k == Modifier.Keyword.ABSTRACT) lastA = i;
    }
    if (lastP >= 0 && lastS >= 0 && lastP > lastS) {
      report(line, col, name, ctx);
      return;
    }
    if (lastS >= 0 && lastF >= 0 && lastS > lastF) {
      report(line, col, name, ctx);
      return;
    }
    if (lastF >= 0 && lastA >= 0 && lastF > lastA) {
      report(line, col, name, ctx);
      return;
    }
    if (lastP >= 0 && lastF >= 0 && lastP > lastF) {
      report(line, col, name, ctx);
      return;
    }
  }

  private void report(int line, int col, String name, StyleContext ctx) {
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Modifier order should be public/protected/private static final abstract: " + name,
        RuleDef.Severity.LOW,
        name);
  }

  @Override
  public void visit(com.github.javaparser.ast.CompilationUnit n, StyleContext ctx) {
    n.walk(
        com.github.javaparser.ast.Node.class,
        node -> {
          if (node instanceof FieldDeclaration f) {
            checkOrder(
                new ArrayList<>(f.getModifiers()),
                f.getBegin().map(p -> p.line).orElse(-1),
                f.getBegin().map(p -> p.column).orElse(-1),
                "field",
                ctx);
          } else if (node instanceof MethodDeclaration m) {
            checkOrder(
                new ArrayList<>(m.getModifiers()),
                m.getBegin().map(p -> p.line).orElse(-1),
                m.getBegin().map(p -> p.column).orElse(-1),
                m.getNameAsString(),
                ctx);
          }
        });
  }
}
