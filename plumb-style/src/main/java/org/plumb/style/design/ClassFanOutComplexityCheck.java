/*
 * class has too many other class references (fan-out).
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ClassOrInterfaceDeclaration)} (1 walk) + for
 * each class {@code c.findAll(ClassOrInterfaceType)} (1 walk per
 * class). The new implementation uses
 * {@code visit(ClassOrInterfaceDeclaration)} dispatched by
 * {@code AstDispatcher} + a single walk with instanceof dispatch
 * to collect the ref set. Total: 1 walk per file (was 1 + N_cls
 * walks).</p>
 */
package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ClassFanOutComplexityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-classfanoutcomplexity", "CLASSFANOUTCOMPLEXITY"),
          "class has too many other class references (fan-out)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassFanOutComplexityCheck",
          true,
          List.of("ast-design-fanout-complexity", "classfanoutcomplexitycheck"));

  @Override
  public String name() {
    return "classfanoutcomplexity";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    Set<String> refs = new HashSet<>();
    c.walk(
        com.github.javaparser.ast.Node.class,
        n -> {
          if (n instanceof ClassOrInterfaceType t) {
            refs.add(t.getNameAsString());
          }
        });
    if (refs.size() > 20) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Class " + c.getNameAsString() + " has " + refs.size() + " class refs; refactor",
          RuleDef.Severity.LOW,
          c.getNameAsString());
    }
  }
}
