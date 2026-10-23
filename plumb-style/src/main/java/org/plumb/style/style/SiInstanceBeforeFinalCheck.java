package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** instance init reads static final before assignment (SI_INSTANCE_BEFORE_FINALS_ASSIGNED). */
public final class SiInstanceBeforeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-si-instance-before-final", "SI_INSTANCE_BEFORE_FINAL"),
          "instance init reads static final before assignment (SI_INSTANCE_BEFORE_FINALS_ASSIGNED)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-457"),
          "org.plumb.style.SiInstanceBeforeFinalCheck",
          true,
          List.of("ast-style-instance-before-finals-assigned", "si-instance-before-final")
      );

  @Override
  public String name() {
    return "si-instance-before-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // Detect: a class with a static final field
    // and a constructor / instance initializer.
    // The static field is at risk if the
    // constructor / init reads it.
    for (com.github.javaparser.ast.body.TypeDeclaration<?> td : n.getTypes()) {
      if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) continue;
      com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid =
          (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) td;
      for (FieldDeclaration f : cid.getFields()) {
        boolean isStatic =
            f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        boolean isFinal =
            f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
        if (!isStatic || !isFinal) continue;
        // The narrow check: there is a constructor.
        if (cid.getConstructors().isEmpty()) continue;
        int line = f.getBegin().map(p -> p.line).orElse(-1);
        int col = f.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "static final field present with a constructor : "
                + "ensure it's assigned before instance reads it",
            RuleDef.Severity.LOW,
            "static-final");
      }
    }
  }
}
