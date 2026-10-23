package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** "public static field of mutable type : use finaldefensive copy". */
public final class PublicStaticMutableFieldCheck implements AstChecker {

  private static final Set<String> MUTABLE_TYPES =
      Set.of(
          "java.util.Collection",
          "java.util.List",
          "java.util.Map",
          "java.util.Set",
          "java.util.ArrayList",
          "java.util.HashMap",
          "java.util.HashSet",
          "java.util.Hashtable",
          "java.util.Date",
          "java.util.Calendar");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-public-static-mutable-field", "PUBLIC_STATIC_MUTABLE_FIELD"),
          "public static field of mutable type : use final + defensive copy",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-668"),
          "org.plumb.style.PublicStaticMutableFieldCheck",
          true,
          List.of("ast-style-public-static-mutable", "public-static-mutable-field")
      );

  @Override
  public String name() {
    return "public-static-mutable-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    boolean isStatic =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
    if (!isStatic) return;
    // Skip final fields (immutable by declaration).
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) return;
    Type t = n.getElementType();
    // Array types are mutable by definition.
    if (t instanceof ArrayType) {
      for (VariableDeclarator v : n.getVariables()) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Public static array field '" + v.getNameAsString() + "' is mutable",
            RuleDef.Severity.MEDIUM,
            v.getNameAsString());
      }
      return;
    }
    if (t instanceof ClassOrInterfaceType ct && MUTABLE_TYPES.contains(ct.getNameAsString())) {
      for (VariableDeclarator v : n.getVariables()) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Public static field '"
                + v.getNameAsString()
                + "' of mutable type "
                + ct.getNameAsString(),
            RuleDef.Severity.MEDIUM,
            v.getNameAsString());
      }
    }
  }
}
