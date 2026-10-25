/*
 * C7: AST-style CWE-379 (Insecure Temp File / Insecure Directory
 * Creation) checker.
 *
 * <p>Fires when:
 * <ul>
 *   <li>{@code new File(literalPath)} where {@code literalPath}
 *       contains a known-insecure directory segment
 *       ({@code insecureDir}, {@code testcases}).</li>
 *   <li>{@code new File(nameExpr)} where {@code nameExpr}
 *       was earlier assigned (via VariableDeclarator or
 *       AssignExpr) a literal that contains an insecure-hint
 *       segment in the enclosing method.</li>
 *   <li>{@code FileOutputStream(literalPath)} /
 *       {@code FileWriter(literalPath)} with insecure literal.</li>
 * </ul>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-379: Insecure Temporary File / Insecure Directory.
 *
 * <p>Detects creation of {@code File} / {@code FileOutputStream} / {@code FileWriter} with a path
 * (literal or alias-of-literal) that contains {@code insecureDir} or {@code testcases}.
 */
public final class InsecureTempFileCheck implements AstChecker {

  private static final String RULE_ID = "dfa-security-insecure-temp-file";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "INSECURE_TEMP_FILE"),
          "File created in known-insecure directory (CWE-379)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-379"),
          "org.plumb.style.security.InsecureTempFileCheck",
          true);

  private static final Set<String> INSECURE_HINTS =
      new LinkedHashSet<>(List.of("insecuredir", "insecure_dir", "testcases"));

  @Override
  public String name() {
    return "insecure-temp-file";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String methodName = n.getNameAsString();
    if (!("FileOutputStream".equals(methodName) || "FileWriter".equals(methodName))) return;
    if (n.getArguments().isEmpty()) return;
    var firstArg = n.getArguments().get(0);
    if (!(firstArg instanceof StringLiteralExpr sl)) return;
    if (matchesInsecureHint(sl.getValue())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "FileOutputStream / FileWriter to insecure directory '" + sl.getValue() + "'",
          RuleDef.Severity.MEDIUM,
          "insecure-temp-file: " + sl.getValue());
    }
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    var typeName = n.getType().asString();
    if (!"File".equals(typeName)) return;
    if (n.getArguments().isEmpty()) return;
    var firstArg = n.getArguments().get(0);
    if (firstArg instanceof StringLiteralExpr sl) {
      if (matchesInsecureHint(sl.getValue())) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "File in insecure directory '" + sl.getValue() + "'",
            RuleDef.Severity.MEDIUM,
            "insecure-temp-file: " + sl.getValue());
      }
      return;
    }
    if (firstArg instanceof NameExpr ne) {
      // Look up the alias in the enclosing method (declarators AND assign exprs).
      MethodDeclaration md = ne.findAncestor(MethodDeclaration.class).orElse(null);
      if (md == null) return;
      BlockStmt body = md.getBody().orElse(null);
      if (body == null) return;
      String varName = ne.getNameAsString();
      String badLiteral = lookupInsecureLiteral(body, varName);
      if (badLiteral != null) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "File in insecure directory (variable '" + varName + "' bound to '" + badLiteral + "')",
            RuleDef.Severity.MEDIUM,
            "insecure-temp-file: " + badLiteral);
      }
    }
  }

  /**
   * Search the body for a variable named {@code varName} bound to an insecure-hint string literal,
   * either via a declarator's initializer or a {@code varName = "literal"} assignment.
   */
  private static String lookupInsecureLiteral(BlockStmt body, String varName) {
    // VariableDeclarator path: Type name = "literal";
    for (VariableDeclarationExpr vde : body.findAll(VariableDeclarationExpr.class)) {
      for (VariableDeclarator vd : vde.getVariables()) {
        if (!vd.getNameAsString().equals(varName)) continue;
        if (vd.getInitializer().isPresent()
            && vd.getInitializer().get() instanceof StringLiteralExpr sl
            && matchesInsecureHint(sl.getValue())) {
          return sl.getValue();
        }
      }
    }
    // AssignExpr path: name = "literal"; (covers `if (...) name = "x";` reassignments).
    for (AssignExpr ae : body.findAll(AssignExpr.class)) {
      if (!(ae.getTarget() instanceof NameExpr ane)) continue;
      if (!ane.getNameAsString().equals(varName)) continue;
      if (ae.getValue() instanceof StringLiteralExpr sl && matchesInsecureHint(sl.getValue())) {
        return sl.getValue();
      }
    }
    return null;
  }

  private static boolean matchesInsecureHint(String path) {
    if (path == null) return false;
    String lower = path.toLowerCase(Locale.ROOT);
    for (String hint : INSECURE_HINTS) {
      if (lower.contains(hint)) return true;
    }
    return false;
  }
}
