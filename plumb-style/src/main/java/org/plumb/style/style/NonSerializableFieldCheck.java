package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Serializable class has a non-Serializable field (SE_BAD_FIELD). */
public final class NonSerializableFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-se-bad-field", "SE_BAD_FIELD"),
          "Serializable class has a non-Serializable field (SE_BAD_FIELD)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-684"),
          "org.plumb.style.NonSerializableFieldCheck",
          true,
          List.of("ast-style-bad-field", "se-bad-field"));

  @Override
  public String name() {
    return "se-bad-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (!(n.getParentNode().get() instanceof ClassOrInterfaceDeclaration cid)) return;
    if (!classImplementsSerializable(cid)) return;
    if (!(n.getElementType() instanceof ClassOrInterfaceType t)) return;
    String name = t.getNameAsString();
    if (!isKnownNonSerializable(name)) return;
    n.getVariables()
        .forEach(
            v -> {
              int line = v.getBegin().map(p -> p.line).orElse(-1);
              int col = v.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Field '" + v.getNameAsString() + "' of type '" + name + "' is not Serializable",
                  RuleDef.Severity.MEDIUM,
                  name);
            });
  }

  private static boolean classImplementsSerializable(ClassOrInterfaceDeclaration cid) {
    for (ClassOrInterfaceType impl : cid.getImplementedTypes()) {
      String name = impl.getNameAsString();
      if ("Serializable".equals(name) || "java.io.Serializable".equals(impl.getNameWithScope())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isKnownNonSerializable(String name) {
    switch (name) {
      case "Thread":
      case "java.lang.Thread":
      case "Socket":
      case "java.net.Socket":
      case "ServerSocket":
      case "java.net.ServerSocket":
      case "ClassLoader":
      case "java.lang.ClassLoader":
      case "Process":
      case "java.lang.Process":
      case "System":
      case "java.lang.System":
      case "Runtime":
      case "java.lang.Runtime":
        return true;
      default:
        return false;
    }
  }
}
