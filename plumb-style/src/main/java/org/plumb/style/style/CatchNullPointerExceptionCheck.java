/*
 * R728-C5: AST check for CWE-395 (Use of
 * NullPointerException Catch to Detect NULL Pointer
 * Dereference). Fires when a CatchClause catches
 * NullPointerException directly.
 *
 * <p>Generic ? the pattern "catching NPE to detect
 * null" is universally a code smell; the developer
 * should check for null before dereferencing. The
 * check fires for any CatchClause where the caught
 * type resolves to NullPointerException (FQN
 * java.lang.NullPointerException or short form).
 * Also matches catches of the supertype RuntimeException
 * / Exception that DECLARE a body that immediately
 * tests for null on an object ? but without symbol
 * resolution that heuristic is too noisy. So we
 * just match direct NPE catches.</p>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class CatchNullPointerExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-catch-nullpointer-exception", "CATCH_NULLPOINTER_EXCEPTION_2"),
          "Catch of NullPointerException — check for null" + " before dereferencing (CWE-395)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-395"),
          "org.plumb.style.CatchNullPointerExceptionCheck",
          true,
          List.of("ast-style-catch-nullpointer-exception", "catch-nullpointer-exception"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "catch-nullpointer-exception";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    Type t = n.getParameter().getType();
    if (containsNullPointerException(t)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|npe";
      if (reported.add(key)) {
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Catching NullPointerException to detect"
                + " null dereferences — check for null"
                + " before dereferencing (CWE-395)",
            RuleDef.Severity.MEDIUM,
            "catch (NullPointerException ...)");
      }
    }
  }

  private boolean containsNullPointerException(Type t) {
    if (t instanceof ClassOrInterfaceType cit) {
      String n = cit.getNameAsString();
      if ("NullPointerException".equals(n)) return true;
      // Walk generics if any (rare in catch clauses)
      // and the resolved type itself.
      return false;
    }
    if (t instanceof UnionType ut) {
      for (Type element : ut.getElements()) {
        if (containsNullPointerException(element)) return true;
      }
      return false;
    }
    // Multi-catch (Java 7+) is represented as
    // UnionType in JavaParser. ReferenceType
    // would mean a parameterized exception which
    // we don't expect for NPE.
    return false;
  }
}
