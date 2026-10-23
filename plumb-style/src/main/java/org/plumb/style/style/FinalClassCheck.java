package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class with only private constructors should be {@code final}. */
public final class FinalClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-final-class", "FINAL_CLASS"),
          "class with only private ctors should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FinalClassCheck",
          true,
          List.of("ast-style-class-final", "final-class")
      );

  @Override
  public String name() {
    return "final-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (c.isFinal() || c.isAbstract()) return;
    boolean hasNonPrivateCtor = false;
    int ctorCount = 0;
    for (ConstructorDeclaration ctor : c.getConstructors()) {
      ctorCount++;
      if (!ctor.isPrivate()) hasNonPrivateCtor = true;
    }
    if (ctorCount == 0 || hasNonPrivateCtor) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class " + c.getNameAsString() + " has only private constructors; should be final",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}