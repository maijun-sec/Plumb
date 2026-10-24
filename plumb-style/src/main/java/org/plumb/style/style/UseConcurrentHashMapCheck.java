package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** HashMap field is not thread-safe : use ConcurrentHashMap. */
public final class UseConcurrentHashMapCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-concurrenthashmap", "USE_CONCURRENTHASHMAP"),
          "HashMap field is not thread-safe : use ConcurrentHashMap",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-362"),
          "org.plumb.style.UseConcurrentHashMapCheck",
          true,
          List.of("ast-style-concurrent-hashmap", "use-concurrenthashmap"));

  @Override
  public String name() {
    return "use-concurrenthashmap";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (n.getElementType() instanceof ClassOrInterfaceType t) {
      String name = t.getNameAsString();
      if (!"HashMap".equals(name) && !"java.util.HashMap".equals(name)) return;
      // Skip if the field is final (immutable reference
      // at least).
      boolean isFinal =
          n.getModifiers().stream()
              .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.FINAL);
      if (isFinal) return;
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "HashMap field is not thread-safe : use ConcurrentHashMap",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}
