package org.plumb.style.design;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Use the interface type, not the concrete class.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `allowedConcreteTypes` | STRING | `` | Comma-separated list of concrete type names to skip |
 */
public final class LooseCouplingCheck implements AstChecker {

  private static final Set<String> CONCRETE_TO_INTERFACE =
      Set.of(
          "ArrayList",
          "LinkedList",
          "Vector",
          "Stack",
          "HashSet",
          "LinkedHashSet",
          "TreeSet",
          "HashMap",
          "LinkedHashMap",
          "TreeMap",
          "Hashtable",
          "Properties");

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "allowedConcreteTypes",
        "Comma-separated list of concrete type names to skip",
        RuleParameter.Type.STRING,
        ""),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-loose-coupling", "LOOSE_COUPLING"),
          "Use the interface type, not the concrete class",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LooseCouplingCheck",
          true,
          List.of("ast-design-loose-coupling", "design-loose-coupling", "loose-type-coupling"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "loose-type-coupling";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getType() instanceof ClassOrInterfaceType ct) {
      checkType(
          n.getType(),
          ct.getNameAsString(),
          n.getNameAsString(),
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          ctx);
    }
  }

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // n.getElementType() returns the *component* type for
    // arrays, e.g. `int` for `int[]`. Use getCommonType()
    // for the declared type.
    Type t = n.getCommonType();
    if (t instanceof ClassOrInterfaceType ct) {
      String name = ct.getNameAsString();
      checkType(
          t,
          name,
          n.getVariables().get(0).getNameAsString(),
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          ctx);
    }
  }

  private void checkType(Type t, String name, String owner, int line, int col, StyleContext ctx) {
    if (!CONCRETE_TO_INTERFACE.contains(name)) return;
    String allowed = paramString(ctx, "allowedConcreteTypes", "");
    for (String a : allowed.split(",")) {
      if (a.trim().equals(name)) return;
    }
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Type '"
            + name
            + "' (in '"
            + owner
            + "') should be replaced with the corresponding interface",
        RuleDef.Severity.LOW,
        name);
  }

  private static String paramString(StyleContext ctx, String name, String def) {
    String override = ctx.paramOverride(name, String.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.defaultValue();
    }
    return def;
  }
}
