package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** field not initialized in constructor (UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR). */
public final class UwfFieldNotInitializedCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-uwf-field-not-init", "UWF_FIELD_NOT_INIT"),
          "field not initialized in constructor (UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UwfFieldNotInitializedCheck",
          true,
          List.of("ast-style-field-not-initialized-in-constructor", "uwf-field-not-init"));

  @Override
  public String name() {
    return "uwf-field-not-init";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (TypeDeclaration<?> td : n.getTypes()) {
      if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) continue;
      com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid =
          (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) td;
      if (cid.getConstructors().isEmpty()) continue;
      for (FieldDeclaration f : cid.getFields()) {
        boolean isStatic =
            f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        boolean isFinal =
            f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
        if (isStatic || isFinal) continue;
        for (VariableDeclarator v : f.getVariables()) {
          if (v.getInitializer().isPresent()) continue;
          int line = v.getBegin().map(p -> p.line).orElse(-1);
          int col = v.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Field '" + v.getNameAsString() + "' not initialized : ensure constructors set it",
              RuleDef.Severity.LOW,
              v.getNameAsString());
        }
      }
    }
  }
}
