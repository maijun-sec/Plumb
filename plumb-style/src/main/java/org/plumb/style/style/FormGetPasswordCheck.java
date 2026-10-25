/*
 * CWE-598 (Use of GET Request Method With Sensitive Query Strings).
 *
 * <p>Detects a file that contains both a form whose {@code method} attribute is
 * {@code "get"} AND a sensitive input field (name attribute matching
 * {@code password} / {@code passwd} / {@code pwd} / {@code ssn} /
 * {@code credit-card}). Credentials in query strings end up in proxy
 * logs, browser history, and server access logs, which the attacker can
 * later read.
 *
 * <p>Detection approach (AST, no dataflow): the file-level pattern is
 * "there exists a StringLiteral containing {@code <form method="get">} (or
 * any variant) AND there exists a StringLiteral containing
 * {@code <input name="<sensitive>">}". Both must be present in the same
 * CompilationUnit.
 *
 * <p>The complementary case (form method="post") is the Juliet good
 * path and is NOT reported.
 *
 * smoke tests; absent this checker the tests were failing
 * with "expected 1 finding, got 0".
 */
package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class FormGetPasswordCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-form-get-password", "FORM_GET_PASSWORD"),
          "Form method=get transmits sensitive field via query string (CWE-598)",
          RuleDef.Severity.MEDIUM,
          org.plumb.rules.RuleDef.Type.SECURITY,
          List.of("CWE-598"),
          "org.plumb.style.FormGetPasswordCheck",
          /* enabled */ true);

  /**
   * Matches a {@code <form ... method="get" ...>} tag (single- or double-quoted value). Operates on
   * {@code StringLiteralExpr.asString()}, which is the canonical un-escaped form (the Java-escape
   * sequences are stripped by the parser, so we get the actual HTML text).
   */
  private static final Pattern FORM_TAG =
      Pattern.compile(
          "<form\\b[^>]*?\\bmethod\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * Matches a sensitive input name attribute. Covers {@code name="password"}, {@code
   * name='passwd'}, {@code name="pwd"}, etc.
   */
  private static final Pattern SENSITIVE_INPUT =
      Pattern.compile(
          "<input\\b[^>]*?\\bname\\s*=\\s*(?:\"(password|passwd|pwd|ssn|credit[_-]?card)\"|'(password|passwd|pwd|ssn|credit[_-]?card)')",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  @Override
  public String name() {
    return "form-get-password";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    int formLine = -1;
    int passwordLine = -1;
    String sensitiveField = null;

    for (StringLiteralExpr s : n.findAll(StringLiteralExpr.class)) {
      // characters and so keeps `\"` in the returned string). For
      // a Java source literal "<form method=\"get\">", getValue()
      // returns "<form method\\\"get\\\">" while asString() returns
      // "<form method=\"get\">" (the un-escaped HTML).
      String unescaped = s.asString();
      if (unescaped != null) {
        int line = s.getBegin().map(p -> p.line).orElse(-1);
        if (formLine < 0 && isFormWithGet(unescaped)) formLine = line;
        if (passwordLine < 0) {
          String f = firstSensitiveFieldName(unescaped);
          if (f != null) {
            passwordLine = line;
            sensitiveField = f;
          }
        }
      }
      if (formLine >= 0 && passwordLine >= 0) break;
    }

    if (formLine < 0 || passwordLine < 0) return;
    int fireLine = formLine;
    int fireCol = 1;
    ctx.report(
        fireLine,
        fireCol,
        DEFAULT_RULE,
        "Form method=get transmits `"
            + (sensitiveField == null ? "password" : sensitiveField)
            + "` field via query string (CWE-598). Switch to method=post or move the field to a"
            + " request body.",
        RuleDef.Severity.MEDIUM,
        "form-get-method:" + (sensitiveField == null ? "password" : sensitiveField));
  }

  private static boolean isFormWithGet(String s) {
    Matcher m = FORM_TAG.matcher(s);
    if (!m.find()) return false;
    String method = m.group(1) != null ? m.group(1) : m.group(2);
    return method != null && method.trim().equalsIgnoreCase("get");
  }

  /** Returns the first sensitive field name matched, or null if none. */
  private static String firstSensitiveFieldName(String s) {
    Matcher m = SENSITIVE_INPUT.matcher(s);
    if (!m.find()) return null;
    return m.group(1) != null ? m.group(1) : m.group(2);
  }
}
