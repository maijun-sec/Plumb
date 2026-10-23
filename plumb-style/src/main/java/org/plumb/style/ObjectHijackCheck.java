package org.plumb.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * CWE-491: Object Hijack.
 *
 * <p>Fires when a class implements {@code Cloneable} but is not declared {@code final}, allowing a
 * subclass to override the (non-final) {@code clone()} method and hijack the cloned instance.
 *
 * <p><b>R-818 (2026-10-14): pattern D+ cleanup.</b> The previous implementation had an empty {@code
 * visit(CompilationUnit, ...)} stub ("// Per-class dispatch below.") alongside the per-class {@code
 * visit(ClassOrInterfaceDeclaration, ...)} handler. This was a D+ anti-pattern (both overloads
 * fire, doubling the walk). The empty stub has been removed; only the {@code
 * visit(ClassOrInterfaceDeclaration)} overload remains, dispatched by {@code AstDispatcher} once
 * per class.
 */
public final class ObjectHijackCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-style-object-hijack", "OBJECT_HIJACK"),
          "Non-final class with non-final clone() allows subclass hijack",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-491"),
          "org.plumb.style.ObjectHijackCheck",
          true,
          List.of("ast-misc-object-hijack", "object-hijack")
      );

  @Override
  public String name() {
    return "object-hijack";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration cls, StyleContext ctx) {
    boolean classIsFinal =
        cls.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    boolean implementsCloneable =
        cls.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("Cloneable"));
    if (classIsFinal || !implementsCloneable) return;
    for (MethodDeclaration md : cls.getMethodsByName("clone")) {
      boolean cloneIsFinal =
          md.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
      if (cloneIsFinal) continue;
      int line = md.getBegin().map(p -> p.line).orElse(-1);
      int col = md.getBegin().map(p -> p.column).orElse(-1);
      String sinkFunction =
          cls.getNameAsString() + "." + md.getNameAsString() + "():" + md.getType().asString();
      ctx.report(
          line,
          col,
          RULE,
          "Class '"
              + cls.getNameAsString()
              + "' is not final and its clone() is not final — subclass can hijack the clone",
          RuleDef.Severity.MEDIUM,
          sinkFunction);
    }
  }
}
