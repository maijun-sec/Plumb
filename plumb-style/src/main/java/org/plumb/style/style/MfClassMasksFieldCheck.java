package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** a nested class masks an enclosing field of the same name (MF_CLASS_MASKS_FIELD). */
public final class MfClassMasksFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-mf-class-masks-field", "MF_CLASS_MASKS_FIELD"),
          "a nested class masks an enclosing field of the same name (MF_CLASS_MASKS_FIELD)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MfClassMasksFieldCheck",
          true,
          List.of("ast-style-class-masks-field", "mf-class-masks-field")
      );

  @Override
  public String name() {
    return "mf-class-masks-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (com.github.javaparser.ast.body.TypeDeclaration<?> top : n.getTypes()) {
      checkType(top, ctx);
    }
  }

  private void checkType(com.github.javaparser.ast.body.TypeDeclaration<?> td, StyleContext ctx) {
    if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) {
      return;
    }
    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid =
        (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) td;
    // Collect all field names.
    Set<String> fields = new HashSet<>();
    for (FieldDeclaration f : cid.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        fields.add(v.getNameAsString());
      }
    }
    // For each nested class, check if its name matches a field.
    for (com.github.javaparser.ast.body.BodyDeclaration<?> nested : cid.getMembers()) {
      if (!(nested instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) continue;
      com.github.javaparser.ast.body.ClassOrInterfaceDeclaration nc =
          (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) nested;
      if (fields.contains(nc.getNameAsString())) {
        int line = nc.getBegin().map(p -> p.line).orElse(-1);
        int col = nc.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Nested class '" + nc.getNameAsString() + "' masks enclosing field of the same name",
            RuleDef.Severity.LOW,
            nc.getNameAsString());
      }
    }
  }
}
