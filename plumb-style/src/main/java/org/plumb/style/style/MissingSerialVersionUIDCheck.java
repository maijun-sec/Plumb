package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class implements Serializable but is missing serialVersionUID. */
public final class MissingSerialVersionUIDCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missing-serial-version-uid", "MISSING_SERIAL_VERSION_UID"),
          "Class implements Serializable but is missing serialVersionUID",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-609"),
          "org.plumb.style.MissingSerialVersionUIDCheck",
          true,
          List.of("ast-style-missing-serial-version-uid", "missing-serial-version-uid"));

  @Override
  public String name() {
    return "missing-serial-version-uid";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.getImplementedTypes().stream()
        .anyMatch(t -> t.getNameAsString().equals("Serializable"))) {
      return;
    }
    boolean hasSerialVersionUID =
        c.getFields().stream()
            .flatMap(f -> f.getVariables().stream())
            .anyMatch(v -> v.getNameAsString().equals("serialVersionUID"));
    if (!hasSerialVersionUID) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Serializable class " + c.getNameAsString() + " is missing serialVersionUID",
          RuleDef.Severity.MEDIUM,
          c.getNameAsString());
    }
  }
}
