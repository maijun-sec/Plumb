package org.plumb.style.error;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** finalize() should be declared final or the class should be final. */
public final class FinalizeOnlyOverriddenCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-finalize-only-overridden", "FINALIZE_ONLY_OVERRIDDEN"),
          "finalize() should be declared final or the class should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-586"),
          "org.plumb.style.FinalizeOnlyOverriddenCheck",
          true,
          List.of("ast-error-finalize-only-overridden", "finalize-only-overridden"));

  @Override
  public String name() {
    return "finalize-only-overridden";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"finalize".equals(n.getNameAsString())) return;
    if (n.getType().toString().equals("void") == false) return;
    // finalize() must be declared final OR the
    // enclosing class must be final.
    boolean methodFinal =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    if (methodFinal) return;
    if (n.getParentNode().isPresent()
        && n.getParentNode().get() instanceof ClassOrInterfaceDeclaration cid
        && cid.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "finalize() should be declared final (or the class should be final)",
        RuleDef.Severity.LOW,
        "finalize");
  }
}
