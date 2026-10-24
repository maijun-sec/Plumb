package org.plumb.style.performance;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** collection of URLs : equals triggers DNS (DMI_COLLECTION_OF_URLS). */
public final class DmiCollectionOfUrlsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-dmi-collection-urls", "DMI_COLLECTION_URLS"),
          "collection of URLs : equals triggers DNS (DMI_COLLECTION_OF_URLS)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-400"),
          "org.plumb.style.DmiCollectionOfUrlsCheck",
          true,
          List.of("ast-performance-collection-of-urls", "dmi-collection-urls"));

  @Override
  public String name() {
    return "dmi-collection-urls";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    com.github.javaparser.ast.type.Type t = n.getCommonType();
    if (containsUrl(t)) {
      for (VariableDeclarator v : n.getVariables()) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field of URL collection : equals() triggers DNS",
            RuleDef.Severity.MEDIUM,
            v.getNameAsString());
      }
    }
  }

  private static boolean containsUrl(com.github.javaparser.ast.type.Type t) {
    if (t instanceof ArrayType at) {
      return containsUrl(at.getComponentType());
    }
    if (t instanceof ClassOrInterfaceType coi) {
      String name = coi.getNameAsString();
      return "URL".equals(name) || "java.net.URL".equals(coi.getNameWithScope());
    }
    return false;
  }
}
