/*
 * Coverity <code>REGEX_CONFUSION</code> — flags calls to {@link String#replaceAll(String,
 * String)}, {@link String#replaceFirst(String, String)} and {@link String#split(String)} where
 * the first argument is a string literal that contains a regex metacharacter. The developer most
 * likely intended a literal replacement, not a regex match.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)} before.
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class RegexConfusionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-quality-regex-confusion", "REGEX_CONFUSION"),
          "Literal string passed to a method that takes a regular expression",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-625", "CWE-184"),
          "org.plumb.style.RegexConfusionCheck",
          true,
          List.of("ast-quality-regex-confusion", "regex-confusion"));

  private static final Set<String> TARGET_METHODS = Set.of("replaceAll", "replaceFirst", "split");

  private static final Set<Character> REGEX_METACHARS =
      Set.of('.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|', '^', '$', '\\');

  private boolean reportCharacterHiding = false;

  @Override
  public String name() {
    return "regex-confusion";
  }

  @Override
  public void init(StyleContext ctx) {
    Boolean opt = ctx.paramOverride("REGEX_CONFUSION:report_character_hiding", Boolean.class);
    if (opt != null) reportCharacterHiding = opt;
  }

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (!TARGET_METHODS.contains(m.getNameAsString())) return;
    if (m.getArguments().isEmpty()) return;
    if (!(m.getArgument(0) instanceof StringLiteralExpr lit)) return;
    String value = lit.getValue();
    if (value == null || value.isEmpty()) return;

    String owner = null;
    try {
      Object resolved = m.resolve();
      if (resolved instanceof ResolvedMethodDeclaration rmd) {
        owner = rmd.declaringType().getQualifiedName();
      }
    } catch (RuntimeException ignore) {
      // fall through; we still flag the call if it has a regex metachar.
    }
    if (owner != null && !"java.lang.String".equals(owner)) return;

    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    Set<Character> hits = metacharsIn(value);
    if (!hits.isEmpty()) {
      if (allEscaped(value, hits)) return;
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "REGEX_CONFUSION: literal \""
              + value
              + "\" passed to "
              + m.getNameAsString()
              + "(String) contains regex "
              + "metacharacter(s) "
              + describe(hits)
              + " — use Pattern.quote(...) to match literally",
          RuleDef.Severity.MEDIUM,
          m.toString());
      return;
    }
    if (reportCharacterHiding && value.length() == 1 && REGEX_METACHARS.contains(value.charAt(0))) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "REGEX_CONFUSION: single-character regex \""
              + value
              + "\" in "
              + m.getNameAsString()
              + " hides every character — almost certainly "
              + "a bug",
          RuleDef.Severity.MEDIUM,
          m.toString());
    }
  }

  private static Set<Character> metacharsIn(String s) {
    Set<Character> out = new LinkedHashSet<>();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (REGEX_METACHARS.contains(c)) out.add(c);
    }
    return out;
  }

  /**
   * Returns true when every metachar in {@code hits} is preceded by a backslash (the call is
   * intentional regex usage, not a defect).
   */
  private static boolean allEscaped(String s, Set<Character> hits) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!hits.contains(c)) continue;
      if (c == '\\') continue;
      int bs = 0;
      int j = i - 1;
      while (j >= 0 && s.charAt(j) == '\\') {
        bs++;
        j--;
      }
      if (bs % 2 == 0) return false;
    }
    return true;
  }

  private static String describe(Set<Character> chars) {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (char c : chars) {
      if (!first) sb.append(", ");
      first = false;
      switch (c) {
        case '.':
          sb.append("'.' (any char)");
          break;
        case '*':
          sb.append("'*' (zero or more)");
          break;
        case '+':
          sb.append("'+' (one or more)");
          break;
        case '?':
          sb.append("'?' (zero or one)");
          break;
        case '(':
          sb.append("'(' (group)");
          break;
        case ')':
          sb.append("')' (group end)");
          break;
        case '[':
          sb.append("'[' (char class)");
          break;
        case ']':
          sb.append("']' (char class end)");
          break;
        case '{':
          sb.append("'{' (repetition)");
          break;
        case '}':
          sb.append("'}' (repetition end)");
          break;
        case '|':
          sb.append("'|' (alternation)");
          break;
        case '^':
          sb.append("'^' (anchor)");
          break;
        case '$':
          sb.append("'$' (anchor)");
          break;
        case '\\':
          sb.append("'\\\\' (escape)");
          break;
        default:
          sb.append('\'').append(c).append('\'');
      }
    }
    return sb.append(']').toString();
  }
}
