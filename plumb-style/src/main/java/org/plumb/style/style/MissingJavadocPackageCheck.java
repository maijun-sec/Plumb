package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Missing package-info.java or Javadoc on package. */
public final class MissingJavadocPackageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missingjavadocpackage", "MISSINGJAVADOCPACKAGE"),
          "Missing package-info.java or Javadoc on package",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MissingJavadocPackageCheck",
          false,
          List.of(
              "ast-style-missing-javadoc-package",
              "missingjavadocpackage")); // R-834: disabled by default (0 TP / 100% FP on Juliet

  // Disabled by default: this rule reports a style preference rather than a defect.

  @Override
  public String name() {
    return "missingjavadocpackage";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getPackageDeclaration().isPresent()
        && !n.getPackageDeclaration().get().getNameAsString().isEmpty()) {
      boolean hasComment =
          n.getOrphanComments().stream().anyMatch(c -> c.getContent().contains("package"));
      if (!hasComment) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Missing Javadoc on package",
            RuleDef.Severity.LOW,
            n.getPackageDeclaration().get().getNameAsString());
      }
    }
  }
}
