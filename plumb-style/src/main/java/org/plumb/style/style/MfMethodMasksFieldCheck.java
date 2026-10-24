package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** a method masks an enclosing field of the same name (MF_METHOD_MASKS_FIELD). */
public final class MfMethodMasksFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-mf-method-masks-field", "MF_METHOD_MASKS_FIELD"),
          "a method masks an enclosing field of the same name (MF_METHOD_MASKS_FIELD)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MfMethodMasksFieldCheck",
          true,
          List.of("ast-style-method-masks-field", "mf-method-masks-field"));

  @Override
  public String name() {
    return "mf-method-masks-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (TypeDeclaration<?> td : n.getTypes()) {
      checkType(td, ctx);
    }
  }

  private void checkType(TypeDeclaration<?> td, StyleContext ctx) {
    if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) {
      return;
    }
    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid =
        (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) td;
    Set<String> fields = new HashSet<>();
    for (FieldDeclaration f : cid.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        fields.add(v.getNameAsString());
      }
    }
    for (MethodDeclaration m : cid.getMethods()) {
      if (fields.contains(m.getNameAsString())) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Method '" + m.getNameAsString() + "' masks enclosing field of the same name",
            RuleDef.Severity.LOW,
            m.getNameAsString());
      }
    }
  }
}
