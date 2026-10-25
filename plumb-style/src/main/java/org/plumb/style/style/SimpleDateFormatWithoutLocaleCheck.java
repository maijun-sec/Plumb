package org.plumb.style.style;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** SimpleDateFormat should use Locale-specific constructor. */
public final class SimpleDateFormatWithoutLocaleCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-simpledateformatwithoutlocale", "SIMPLEDATEFORMATWITHOUTLOCALE"),
          "SimpleDateFormat should use Locale-specific constructor",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.SimpleDateFormatWithoutLocaleCheck",
          true,
          List.of("ast-style-simple-date-format", "simpledateformatwithoutlocalecheck"));

  @Override
  public String name() {
    return "simpledateformatwithoutlocale";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (oc.getType() instanceof com.github.javaparser.ast.type.ClassOrInterfaceType) {
      String name =
          ((com.github.javaparser.ast.type.ClassOrInterfaceType) oc.getType()).getNameAsString();
      if (name.equals("SimpleDateFormat") && oc.getArguments().size() == 1) {
        int line = oc.getBegin().map(p -> p.line).orElse(-1);
        int col = oc.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "SimpleDateFormat should specify Locale for thread-safety",
            RuleDef.Severity.MEDIUM,
            name);
      }
    }
  }
}
