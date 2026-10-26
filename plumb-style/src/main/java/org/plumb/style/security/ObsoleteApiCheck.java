/*
 * C3: AST-style CWE-477 (Use of Obsolete
 * Functions) checker.
 *
 * <p>Fires when the source uses a JDK API that has
 * been deprecated or marked obsolete in favour of
 * a more modern, more secure, or more robust
 * alternative. The Juliet CWE477 test cases pin
 * four canonical patterns:</p>
 *
 * <ul>
 *   <li>{@code DataInputStream.readLine()} -- uses
 *       the default platform charset which is not
 *       safe to round-trip UTF-8 / Latin-1 bytes.
 *       {@link java.io.BufferedReader#readLine()}
 *       (with an explicit Charset) is the
 *       replacement.</li>
 *   <li>{@code URL.equals(URL)} / {@code URL.hashCode()}
 * — blocks DNS resolution which makes
 *       {@code http://1.2.3.4/} and
 *       {@code http://hostname/} compare equal even
 *       when the hostnames map to different IPs.
 *       {@link java.net.URI#equals(Object)} or a
 *       hostname check is the replacement.</li>
 *   <li>{@code Runtime.exec(String)} -- passes the
 *       command through the platform shell, which
 *       is vulnerable to argument injection.
 *       {@link java.lang.ProcessBuilder} with an
 *       explicit argument list is the replacement.</li>
 *   <li>{@code new Date(...)} getters —
 *       {@code Date.getYear()}, {@code getMonth()},
 *       {@code getDate()}, {@code getHours()},
 *       {@code getMinutes()}, {@code getSeconds()}
 * — were deprecated in JDK 1.1 in favour of
 *       {@link java.util.Calendar}.</li>
 * </ul>
 *
 * <p>This checker is <strong>general-purpose</strong>:
 * the patterns target JDK APIs, not Juliet-specific
 * constructs. Adding a new obsolete pattern is a
 * single-entry change to {@link #OBSOLETE_METHODS}
 * below; no Juliet-specific hard-coding.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-477: Use of Obsolete Functions.
 *
 * <p>Detects deprecated / discouraged JDK methods and recommends a modern replacement in the AI
 * prompt.
 */
public final class ObsoleteApiCheck implements AstChecker {

  /**
   * Map of (owner-class-substring + "." + method-name) to a human-readable recommendation. The
   * owner substring is matched against the static-call / field-access receiver (e.g.
   * "DataInputStream" matches {@code DataInputStream.readLine()}, {@code
   * java.io.DataInputStream.readLine()}, etc.). The method name is matched against {@link
   * MethodCallExpr#getNameAsString()}.
   *
   * <p>Order matters: more-specific patterns should come first so a regex-style "any URL method"
   * rule doesn't shadow a more specific match.
   */
  private static final Map<String, String> OBSOLETE_METHODS = new LinkedHashMap<>();

  static {
    // CWE-477 canonical Juliet patterns.
    OBSOLETE_METHODS.put(
        "DataInputStream.readLine",
        "DataInputStream.readLine() is deprecated (uses the platform default "
            + "charset which is unsafe). Use BufferedReader.readLine() with an "
            + "explicit Charset (StandardCharsets.UTF_8) instead.");
    OBSOLETE_METHODS.put(
        "URL.equals",
        "URL.equals(URL) blocks DNS resolution which makes hostname-bearing "
            + "URLs compare unequal to their IP-only forms. Compare on URI / "
            + "hostname instead.");
    OBSOLETE_METHODS.put(
        "URL.hashCode",
        "URL.hashCode() blocks DNS resolution. Use URI-based hashing or "
            + "java.net.InetAddress comparison.");
    OBSOLETE_METHODS.put(
        "Runtime.exec",
        "Runtime.exec(String) tokenises via the platform shell (cmd.exe / "
            + "/bin/sh). Use ProcessBuilder with an explicit argv list to avoid "
            + "shell metacharacter injection.");
    // Date legacy getters — JDK 1.1 deprecation.
    OBSOLETE_METHODS.put(
        "Date.getYear",
        "Date.getYear() is deprecated since JDK 1.1; use Calendar.get(Calendar.YEAR) "
            + "or LocalDate.getYear() (java.time) instead.");
    OBSOLETE_METHODS.put(
        "Date.getMonth",
        "Date.getMonth() is deprecated since JDK 1.1; use Calendar.get(Calendar.MONTH) "
            + "or LocalDate.getMonth() (java.time) instead.");
    OBSOLETE_METHODS.put(
        "Date.getDate",
        "Date.getDate() is deprecated since JDK 1.1; use Calendar.get(Calendar.DAY_OF_MONTH) "
            + "or LocalDate.getDayOfMonth() (java.time) instead.");
    OBSOLETE_METHODS.put(
        "Date.getHours",
        "Date.getHours() is deprecated since JDK 1.1; use Calendar.get(Calendar.HOUR_OF_DAY) "
            + "or LocalTime.getHour() (java.time) instead.");
    OBSOLETE_METHODS.put(
        "Date.getMinutes",
        "Date.getMinutes() is deprecated since JDK 1.1; use Calendar.get(Calendar.MINUTE) "
            + "or LocalTime.getMinute() (java.time) instead.");
    OBSOLETE_METHODS.put(
        "Date.getSeconds",
        "Date.getSeconds() is deprecated since JDK 1.1; use Calendar.get(Calendar.SECOND) "
            + "or LocalTime.getSecond() (java.time) instead.");
    // Date.parse(String) — static deprecated since JDK 1.1.
    OBSOLETE_METHODS.put(
        "Date.parse",
        "Date.parse(String) is deprecated since JDK 1.1; use DateFormat.parse(String) "
            + "or LocalDateTime.parse(CharSequence, DateTimeFormatter) instead.");
    // String.getBytes(int, int, byte[], int) — 4-arg form deprecated since JDK 1.1.
    OBSOLETE_METHODS.put(
        "String.getBytes",
        "String.getBytes(int srcBegin, int srcEnd, byte[] dst, int dstBegin) is "
            + "deprecated since JDK 1.1; use String.getBytes(String charsetName) or "
            + "String.getBytes(Charset) instead.");
    // URLEncoder.encode(String) — 1-arg form deprecated since JDK 1.4.
    OBSOLETE_METHODS.put(
        "URLEncoder.encode",
        "URLEncoder.encode(String) is deprecated since JDK 1.4; use "
            + "URLEncoder.encode(String s, String encoding) for explicit charset control.");
  }

  /**
   * Map of method name → expected arg count for legacy multi-arity deprecated methods. The JDK
   * deprecated the 4-arg {@code String.getBytes(int, int, byte[], int)} form since 1.1 but kept the
   * 0-arg / 1-arg / 1-arg(Charset) forms. The 1-arg {@code URLEncoder.encode(String)} form was
   * deprecated since JDK 1.4 in favour of the 2-arg {@code URLEncoder.encode(String, String)} form.
   * We fire only on the deprecated arity, not on the canonical Juliet CWE477 V02+ goodSink form.
   */
  private static final Map<String, Integer> OBSOLETE_ARITY = new LinkedHashMap<>();

  static {
    OBSOLETE_ARITY.put("String.getBytes", 4);
    OBSOLETE_ARITY.put("URLEncoder.encode", 1);
  }

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-obsolete-api", "OBSOLETE_API"),
          "Use of deprecated / obsolete JDK function (CWE-477)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-477"),
          "org.plumb.style.security.ObsoleteApiCheck",
          true,
          List.of("dfa-security-obsolete-function", "obsolete-api"));

  @Override
  public String name() {
    return "obsolete-api";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String mname = n.getNameAsString();
    Expression scope = n.getScope().orElse(null);
    // First try the literal receiver: e.g.
    // {@code DataInputStream.readLine()} →
    // {@code owner = "DataInputStream"}.
    String owner = ownerClassName(scope);
    if (owner == null) return;
    String key = owner + "." + mname;
    if (OBSOLETE_METHODS.get(key) == null && scope instanceof NameExpr ne) {
      // The receiver is a variable name; look
      // up its declared type in the enclosing
      // method / block. This handles the
      // canonical Juliet CWE-477 V01 form
      // `DataInputStream streamDataInput = ...;
      // streamDataInput.readLine()`.
      String resolvedType = resolveVarType(ne, ctx);
      if (resolvedType != null) {
        key = resolvedType + "." + mname;
      }
    }
    String recommendation = OBSOLETE_METHODS.get(key);
    if (recommendation == null) return;
    // Arity-gate: legacy multi-arity methods (e.g.
    // String.getBytes) fire only when the call has the
    // deprecated arity, not on the modern 1-arg
    // charset-name form.
    Integer requiredArity = OBSOLETE_ARITY.get(key);
    if (requiredArity != null && n.getArguments().size() != requiredArity) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, recommendation, RuleDef.Severity.LOW, key);
  }

  /**
   * Extract the receiver's class / variable name from a {@link MethodCallExpr}'s scope. For {@code
   * DataInputStream.readLine()}, returns {@code "DataInputStream"}. For {@code dis.readLine()}
   * (where {@code dis} is declared elsewhere), we look up the variable's declared type in the
   * enclosing method / block so a {@code DataInputStream x = ...} declaration is resolved to its
   * declared class. This is the resolution-light variant -- we don't run the full JavaParser symbol
   * solver (which requires source jars + extra configuration), we just walk the enclosing method
   * body for {@code <Type> <name> = ...} patterns.
   */
  private static String ownerClassName(Expression scope) {
    if (scope == null) return null;
    if (scope instanceof ThisExpr) return null;
    if (scope instanceof NameExpr ne) return ne.getNameAsString();
    if (scope instanceof FieldAccessExpr fa) {
      // Strip package prefix. `java.util.Date` →
      // `Date`. We don't try to resolve imports —
      // just match by simple class name. This is
      // enough for Juliet's
      // `java.util.Date.parse("...")` form.
      String fqn = fa.toString();
      int dot = fqn.lastIndexOf('.');
      return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
    return scope.toString();
  }

  /**
   * Resolve a {@link NameExpr}'s declared type by walking the enclosing method / block for the
   * matching {@code VariableDeclarator}. Returns {@code null} if no match is found or the
   * declaration is not a simple {@code <Type> <name>} form (e.g. parameter declarations in method
   * signature, class fields).
   */
  private String resolveVarType(NameExpr ne, StyleContext ctx) {
    // Walk upward to find the enclosing
    // MethodDeclaration / BlockStmt.
    // JavaParser nodes have a `findAncestor` helper.
    MethodDeclaration md = ne.findAncestor(MethodDeclaration.class).orElse(null);
    if (md == null) return null;
    String varName = ne.getNameAsString();
    return findVarTypeInBody(md.getBody().orElse(null), varName);
  }

  private static String findVarTypeInBody(BlockStmt body, String varName) {
    if (body == null) return null;
    // Walk every VariableDeclarationExpr in
    // the body; match the declarator's name
    // and extract the simple class name from
    // its type.
    for (VariableDeclarationExpr ite : body.findAll(VariableDeclarationExpr.class)) {
      for (VariableDeclarator vd : ite.getVariables()) {
        if (!vd.getNameAsString().equals(varName)) continue;
        String t = vd.getTypeAsString();
        // The type might be "DataInputStream"
        // or "java.io.DataInputStream" — strip
        // the package prefix.
        int dotIdx = t.lastIndexOf('.');
        return dotIdx < 0 ? t : t.substring(dotIdx + 1);
      }
    }
    return null;
  }
}
