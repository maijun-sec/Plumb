package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Non-Serializable field in Serializable class.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreTransient` | BOOLEAN | `true` | Skip transient fields | | `ignoreStatic` | BOOLEAN |
 * `true` | Skip static fields | | `ignorePrimitives` | BOOLEAN | `true` | Skip primitive and
 * common-JDK serializable types |
 */
public final class NonSerializableClassInSerializableContextCheck implements AstChecker {

  private static final Set<String> SERIALIZABLE_TYPES =
      Set.of(
          "Serializable",
          "String",
          "Date",
          "Integer",
          "Long",
          "Double",
          "Float",
          "Short",
          "Byte",
          "Boolean",
          "Character",
          "BigDecimal",
          "BigInteger",
          "UUID",
          "URL");

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreTransient", "Skip transient fields", RuleParameter.Type.BOOLEAN, "true"),
    new RuleParameter("ignoreStatic", "Skip static fields", RuleParameter.Type.BOOLEAN, "true"),
    new RuleParameter(
        "ignorePrimitives",
        "Skip primitive and common-JDK serializable types",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-non-serializable-class", "NON_SERIALIZABLE_CLASS"),
          "Non-Serializable field in Serializable class",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-502"),
          "org.plumb.style.NonSerializableClassInSerializableContextCheck",
          true,List.of("ast-style-non-serializable-class", "non-serializable-class"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "non-serializable-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (!implementsSerializable(n)) return;
    boolean ignoreTransient = paramBool(ctx, "ignoreTransient", true);
    boolean ignoreStatic = paramBool(ctx, "ignoreStatic", true);
    boolean ignorePrimitives = paramBool(ctx, "ignorePrimitives", true);
    for (FieldDeclaration f : n.getFields()) {
      if (ignoreTransient && f.isTransient()) continue;
      if (ignoreStatic && f.isStatic()) continue;
      for (VariableDeclarator v : f.getVariables()) {
        String typeName = v.getTypeAsString();
        if (ignorePrimitives && isProbablySerializable(typeName)) continue;
        int line = f.getBegin().map(p -> p.line).orElse(-1);
        int col = f.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field '"
                + v.getNameAsString()
                + "' of type '"
                + typeName
                + "' is not known to be Serializable",
            RuleDef.Severity.MEDIUM,
            typeName);
      }
    }
  }

  private static boolean implementsSerializable(ClassOrInterfaceDeclaration n) {
    for (ClassOrInterfaceType impl : n.getImplementedTypes()) {
      if (impl.getNameAsString().equals("Serializable")) return true;
    }
    return false;
  }

  private static boolean isProbablySerializable(String typeName) {
    // Primitives and common JDK types
    if (typeName.equals("int")
        || typeName.equals("long")
        || typeName.equals("double")
        || typeName.equals("float")
        || typeName.equals("short")
        || typeName.equals("byte")
        || typeName.equals("boolean")
        || typeName.equals("char")) return true;
    return SERIALIZABLE_TYPES.contains(typeName);
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
