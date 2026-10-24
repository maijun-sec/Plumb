package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Abstract class name should start with {@code Abstract}.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback directly; was {@code
 * visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)} before.
 */
public final class BadAbstractClassNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-bad-abstract-class-name", "BAD_ABSTRACT_CLASS_NAME"),
          "abstract class name should start with 'Abstract'",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.BadAbstractClassNameCheck",
          true,
          List.of("ast-naming-abstract-class-name", "bad-abstract-class-name"));

  @Override
  public String name() {
    return "bad-abstract-class-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isAbstract()) return;
    if (c.getNameAsString().toLowerCase().startsWith("abstract")) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Abstract class " + c.getNameAsString() + " should be prefixed with 'Abstract'",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
