package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Optional.get() without isPresent() : use orElse/orElseThrow. */
public final class OptionalGetCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-optional-get", "OPTIONAL_GET"),
          "Optional.get() without isPresent() : use orElse/orElseThrow",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.OptionalGetCheck",
          true,
          List.of("ast-style-optional-get", "optional-get")
      );

  @Override
  public String name() {
    return "optional-get";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"get".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    if (n.getArguments().size() != 0) return;
    // Any no-arg `.get()` call is potentially
    // an Optional.get() : without symbol
    // resolution we can't tell, but the canonical
    // risk is a get() on a Stream result. The
    // exception is a `Map.get(key)` which is
    // legitimate. We narrow to: any.get() that
    // is not preceded by a NameExpr with a Map
    // shape (key argument). Since we can't tell,
    // we simply flag all no-arg get() and rely
    // on the user to suppress false positives.
    String scopeText = n.getScope().get().toString();
    // Skip `Map.get` : it takes an argument; this
    // method-call has no args, so it can't be Map.get.
    // Skip method-chain.get() on classes (e.g.
    // `Class.getName()`-style) by name heuristic:
    // the receiver is a single word (e.g. `o`).
    if (!scopeText.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return;
    // Walk up to see if we're inside an `if (x.isPresent())`.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    while (parent != null
        && !(parent instanceof com.github.javaparser.ast.stmt.IfStmt)
        && !(parent instanceof com.github.javaparser.ast.body.MethodDeclaration)
        && !(parent instanceof com.github.javaparser.ast.body.ConstructorDeclaration)) {
      parent = parent.getParentNode().orElse(null);
    }
    if (parent instanceof com.github.javaparser.ast.stmt.IfStmt ifs) {
      if (ifs.getCondition().toString().contains(".isPresent()")) {
        return;
      }
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Optional.get() without isPresent() : use orElse(...) instead",
        RuleDef.Severity.MEDIUM,
        "get");
  }
}
