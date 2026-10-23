package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class member should be private if not used outside. */
public final class ClassMembersShouldBePrivateCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-private-members", "PRIVATE_MEMBERS"),
          "class member should be private if not used outside",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassMembersShouldBePrivateCheck",
          true,
          List.of("ast-style-members-should-be-private", "private-members")
      );

  @Override
  public String name() {
    return "private-members";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Heuristic: flag public fields that are only
    // referenced inside the class.
    for (FieldDeclaration f : n.getFields()) {
      if (!f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC))
        continue;
      // Count references outside this class.
      // This is a rough check; we only fire if
      // there's NO reference at all (suggesting
      // it's not used even inside).
      // Skipped for now — would need type resolution.
    }
  }
}
