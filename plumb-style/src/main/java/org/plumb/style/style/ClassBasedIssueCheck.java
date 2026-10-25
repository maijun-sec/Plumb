/*
 * CWE-491 (Object hijack: protected clone() not declared final).
 *
 * <p>Detects a class that overrides {@code clone()} with
 * {@code protected} access AND does NOT mark it {@code final}.
 * Subclasses can then re-override and break the prototype-chain
 * contract.</p>
 *
 * <p>Detection: per-node visit on every {@code MethodDeclaration}
 * named {@code clone} (no params). If the modifier set contains
 * {@code protected} but NOT {@code final}, fire CWE-491.</p>
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.walk(MethodDeclaration.class, ...)} (1 explicit walk,
 * redundant with the dispatcher's walk). The new implementation
 * uses {@code visit(MethodDeclaration)} dispatched by
 * {@code AstDispatcher} in its single walk ? 0 redundant walks.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ClassBasedIssueCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-class-based-issue-491", "CLASS_BASED_ISSUE_491"),
          "protected clone() not declared final (CWE-491).",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-491"),
          "org.plumb.style.ClassBasedIssueCheck",
          true,
          List.of("ast-style-class-based-issue-491-style", "class-based-issue-491"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "class-based-issue-491";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!"clone".equals(m.getNameAsString())) return;
    if (m.getParameters().size() != 0) return;
    boolean isProtected =
        m.getModifiers().stream().anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.PROTECTED);
    boolean isFinal =
        m.getModifiers().stream().anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.FINAL);
    if (!isProtected || isFinal) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    String key = "cwe491|" + m.getNameAsString() + "|" + line;
    if (!reported.add(key)) return;
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "protected clone() not declared final (CWE-491)."
            + " Subclasses can re-override and break the"
            + " prototype-chain contract. Add 'final' to"
            + " the clone() modifier list.",
        RuleDef.Severity.LOW,
        "cwe491@clone-line " + line);
  }
}
