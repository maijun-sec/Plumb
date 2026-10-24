package org.plumb.style.performance;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** bad cast to abstract collection (BC_BAD_CAST_TO_ABSTRACT_COLLECTION). */
public final class BcBadCastToAbstractCollectionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-bc-bad-cast-abstract", "BC_BAD_CAST_ABSTRACT"),
          "bad cast to abstract collection (BC_BAD_CAST_TO_ABSTRACT_COLLECTION)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-704"),
          "org.plumb.style.BcBadCastToAbstractCollectionCheck",
          true,
          List.of("ast-performance-bad-cast-to-abstract-collection", "bc-bad-cast-abstract"));

  private static final Set<String> ABSTRACT =
      Set.of(
          "Collection",
          "List",
          "Set",
          "Map",
          "AbstractList",
          "AbstractSet",
          "AbstractMap",
          "java.util.Collection",
          "java.util.List",
          "java.util.Set",
          "java.util.Map");

  @Override
  public String name() {
    return "bc-bad-cast-abstract";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr n, StyleContext ctx) {
    if (!(n.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) n.getType();
    if (!ABSTRACT.contains(t.getNameWithScope())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Cast to abstract '" + t.getNameAsString() + "' : verify the source type is compatible",
        RuleDef.Severity.MEDIUM,
        t.getNameAsString());
  }
}
