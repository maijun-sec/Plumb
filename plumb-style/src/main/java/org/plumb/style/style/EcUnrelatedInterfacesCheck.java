package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** implements unrelated interfaces (EC_UNRELATED_INTERFACES). */
public final class EcUnrelatedInterfacesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ec-unrelated-iface", "EC_UNRELATED_IFACE"),
          "implements unrelated interfaces (EC_UNRELATED_INTERFACES)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EcUnrelatedInterfacesCheck",
          true,
          List.of("ast-style-unrelated-interfaces", "ec-unrelated-iface"));

  @Override
  public String name() {
    return "ec-unrelated-iface";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.getImplementedTypes().size() < 2) return;
    // Heuristic: if the class implements both
    // Comparator AND something unrelated (not
    // Comparable, Iterable, etc.), flag.
    boolean hasComparator = false;
    boolean hasOther = false;
    for (ClassOrInterfaceType impl : n.getImplementedTypes()) {
      String name = impl.getNameAsString();
      if ("Comparator".equals(name)) hasComparator = true;
      else if (!"Comparable".equals(name)
          && !"Iterable".equals(name)
          && !"Serializable".equals(name)
          && !"Closeable".equals(name)
          && !"AutoCloseable".equals(name)) {
        hasOther = true;
      }
    }
    if (hasComparator && hasOther) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Class implements Comparator and an unrelated interface",
          RuleDef.Severity.LOW,
          "Comparator");
    }
  }
}
