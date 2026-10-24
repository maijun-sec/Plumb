package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** subclass constructor should call super(...) explicitly. */
public final class CallSuperInConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-call-super-in-constructor", "CALL_SUPER_IN_CONSTRUCTOR"),
          "subclass constructor should call super(...) explicitly",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.CallSuperInConstructorCheck",
          true,
          List.of("ast-style-super-in-constructor", "call-super-in-constructor"));

  @Override
  public String name() {
    return "call-super-in-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    // If the class has no extends, skip : there's no
    // super to call.
    if (n.getParentNode().isEmpty()) return;
    var parent = n.getParentNode().get();
    if (parent instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) {
      com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls =
          (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) parent;
      if (cls.getExtendedTypes().isEmpty()) return;
    }
    // getBody() returns BlockStmt directly (not Optional).
    var stmts = n.getBody().getStatements();
    if (stmts.isEmpty()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Constructor should call super(...) or this(...) explicitly",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
      return;
    }
    Statement first = stmts.get(0);
    if (!(first instanceof ExplicitConstructorInvocationStmt)) {
      int line = first.getBegin().map(p -> p.line).orElse(-1);
      int col = first.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "First statement of constructor should be super(...) or this(...)",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }
}
