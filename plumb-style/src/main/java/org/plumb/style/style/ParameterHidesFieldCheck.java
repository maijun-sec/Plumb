package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method parameter shadows a class field. */
public final class ParameterHidesFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-param-hides-field", "PARAM_HIDES_FIELD"),
          "method parameter shadows a class field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-1006"),
          "org.plumb.style.ParameterHidesFieldCheck",
          true,
          List.of("ast-style-hides-field", "param-hides-field")
      );

  @Override
  public String name() {
    return "param-hides-field";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Get the enclosing class's field names. We
    // can't easily get to the parent class from a
    // method without walking up, but the dispatcher
    // gives us the method directly. Instead, we
    // check the immediate parent for ClassOrInterface
    // fields. For simplicity and to avoid coupling
    // to AST walking, we just check the method's
    // own parameters and look for shadowing against
    // the same method's local declarations... no,
    // that's not right. We need class-level fields.
    // Skip this for now if we can't get the class.
    // We CAN get the class via n.getParentNode() in
    // JavaParser 3.26.
    if (n.getParentNode().isEmpty()) return;
    if (!(n.getParentNode().get()
        instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls)) return;
    // Collect field names
    java.util.Set<String> fieldNames = new java.util.HashSet<>();
    for (FieldDeclaration fd : cls.getFields()) {
      for (VariableDeclarator vd : fd.getVariables()) {
        fieldNames.add(vd.getNameAsString());
      }
    }
    if (fieldNames.isEmpty()) return;
    // Check each parameter against field names
    for (Parameter p : n.getParameters()) {
      if (fieldNames.contains(p.getNameAsString())) {
        ctx.report(
            n.getBegin().map(pos -> pos.line).orElse(-1),
            n.getBegin().map(pos -> pos.column).orElse(-1),
            DEFAULT_RULE,
            "Method parameter '" + p.getNameAsString() + "' hides a class field of the same name",
            RuleDef.Severity.MEDIUM,
            p.getNameAsString());
      }
    }
  }
}
