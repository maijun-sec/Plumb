package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** enum field is not final (ME_MUTABLE_ENUM_FIELD) : globally mutable state. */
public final class MutableEnumFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-mutable-enum-field", "MUTABLE_ENUM_FIELD"),
          "enum field is not final (ME_MUTABLE_ENUM_FIELD) : globally mutable state",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.MutableEnumFieldCheck",
          true,
          List.of("ast-style-mutable-enum-field", "mutable-enum-field")
      );

  @Override
  public String name() {
    return "mutable-enum-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnumDeclaration n, StyleContext ctx) {
    for (FieldDeclaration fd : n.getFields()) {
      if (isEnumConstant(fd)) continue;
      if (isStaticFinal(fd)) continue;
      for (VariableDeclarator v : fd.getVariables()) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Enum field '" + v.getNameAsString() + "' is not final",
            RuleDef.Severity.LOW,
            v.getNameAsString());
      }
    }
  }

  private static boolean isEnumConstant(FieldDeclaration fd) {
    return fd.getVariables().size() == 1 && fd.getVariables().get(0).getInitializer().isPresent();
  }

  private static boolean isStaticFinal(FieldDeclaration fd) {
    boolean isStatic =
        fd.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
    boolean isFinal =
        fd.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    return isStatic && isFinal;
  }
}
