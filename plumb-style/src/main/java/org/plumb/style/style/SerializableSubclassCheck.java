package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Serializable parent without Serializable subclass (BSK_SERIALIZABLE_SUBCLASS). */
public final class SerializableSubclassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-serializable-subclass", "SERIALIZABLE_SUBCLASS"),
          "Serializable parent without Serializable subclass (BSK_SERIALIZABLE_SUBCLASS)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.SerializableSubclassCheck",
          true,
          List.of("ast-style-serializable-subclass", "serializable-subclass")
      );

  @Override
  public String name() {
    return "serializable-subclass";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.getExtendedTypes().isEmpty()) return;
    boolean selfIsSerializable = false;
    for (ClassOrInterfaceType impl : n.getImplementedTypes()) {
      String name = impl.getNameAsString();
      if ("Serializable".equals(name) || "java.io.Serializable".equals(impl.getNameWithScope())) {
        selfIsSerializable = true;
        break;
      }
    }
    if (selfIsSerializable) return;
    // Check if any parent is Serializable.
    for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
      if (parentChainHasSerializable(ext)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Class '"
                + n.getNameAsString()
                + "' extends a Serializable parent but does not "
                + "implement Serializable itself",
            RuleDef.Severity.LOW,
            n.getNameAsString());
        return;
      }
    }
  }

  /**
   * We can't see type hierarchy without symbol resolution, but a quick heuristic: if the parent
   * class is named something with "Exception", "Error", or implements "Serializable" by name in the
   * same file, treat it as serializable. The DFA-based checker in the real analysis.
   */
  private static boolean parentChainHasSerializable(ClassOrInterfaceType ext) {
    String name = ext.getNameAsString();
    // Heuristic: Throwable / Exception / Error are
    // serializable by contract.
    if ("Throwable".equals(name) || "Exception".equals(name) || "Error".equals(name)) {
      return true;
    }
    return false;
  }
}
