package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** hashCode(Object) : clashes with Object.hashCode (HE_SIGNATURE_DECLARES_HASHCODE_OF_OBJECT). */
public final class HeSignatureDeclaresHashcodeOfObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-he-hashcode-signature", "HE_HASHCODE_SIGNATURE"),
          "hashCode(Object) : clashes with Object.hashCode"
              + " (HE_SIGNATURE_DECLARES_HASHCODE_OF_OBJECT)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.HeSignatureDeclaresHashcodeOfObjectCheck",
          true,
          List.of("ast-style-signature-declares-hashcode-of-object", "he-hashcode-signature"));

  @Override
  public String name() {
    return "he-hashcode-signature";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"hashCode".equals(n.getNameAsString())) return;
    if (n.getParameters().size() != 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "hashCode(Object) signature : clashes with Object.hashCode()",
        RuleDef.Severity.HIGH,
        "hashCode");
  }
}
