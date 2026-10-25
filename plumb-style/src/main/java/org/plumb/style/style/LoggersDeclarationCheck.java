package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** logger should be private static final. */
public final class LoggersDeclarationCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-loggers-declaration", "LOGGERS_DECLARATION"),
          "logger should be private static final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LoggersDeclarationCheck",
          true,
          List.of("ast-style-loggers-declaration", "loggers-declaration"));

  @Override
  public String name() {
    return "loggers-declaration";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    for (FieldDeclaration f : c.getFields()) {
      if (!(f.getElementType() instanceof ClassOrInterfaceType)) return;
      ClassOrInterfaceType t = (ClassOrInterfaceType) f.getElementType();
      if (!t.getNameAsString().equals("Logger")) return;
      for (VariableDeclarator v : f.getVariables()) {
        if (!f.isStatic() || !f.isFinal() || !f.isPrivate()) {
          int line = v.getBegin().map(p -> p.line).orElse(-1);
          int col = v.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Logger " + v.getNameAsString() + " should be private static final",
              RuleDef.Severity.LOW,
              v.getNameAsString());
        }
      }
    }
  }
}
