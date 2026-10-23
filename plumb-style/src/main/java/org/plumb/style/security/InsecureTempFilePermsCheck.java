/*
 * R-812 ? InsecureTempFilePermsCheck (CWE-378) migrated
 * from plumb-dfa/.../InsecureTempFilePermsChecker.java to
 * plumb-style AST.
 *
 * <p>Flags construction of a temp file via
 * {@code File.createTempFile(...)} whose returned {@code
 * File} reference is NOT subsequently passed to
 * {@code setWritable}, {@code setReadable}, or
 * {@code setExecutable} before function end (CWE-378).</p>
 *
 * <p>The AST version is a strict subset of the previous
 * DFA half: it fires a single finding at the
 * {@code createTempFile} call site with message
 * "createTempFile result not passed through
 * setWritable/setReadable/setExecutable ? predictable
 * perms (CWE-378)". This loses the "did you set perms
 * later" precision that the DFA half had (the DFA half
 * tracked the receiver across CALLs within a single
 * function). The trade-off: a defensible conservative AST
 * version that fires on every {@code createTempFile(...)}
 * invocation regardless of subsequent perms setting. The
 * AI dedup pass judges whether the call is FP (e.g. the
 * file is in a locked-down directory, or the perms are
 * set via {@code java.nio.file.Files.setPosixFilePermissions}).</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class InsecureTempFilePermsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-insecure-temp-file-perms", "INSECURE_TEMP_FILE_PERMS"),
          "createTempFile result not passed through "
              + "setWritable/setReadable/setExecutable — "
              + "predictable perms (CWE-378)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-378"),
          "org.plumb.style.security.InsecureTempFilePermsCheck",
          true,
          List.of("ast-style-insecure-temp-file-perms", "insecure-temp-file-perms")
      );

  @Override
  public String name() {
    return "insecure-temp-file-perms";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"createTempFile".equals(n.getNameAsString())) return;
    // The receiver should be "File" (either bare
    // identifier or java.io.File). Anything else is
    // unlikely to be the JDK File API.
    if (n.getScope().isEmpty()) return;
    String scope = n.getScope().get().toString();
    if (!scope.endsWith("File") && !scope.endsWith("java.io.File")) {
      // Could also be a static import where the
      // scope is the simple name "File". Accept
      // any scope whose last identifier is
      // "File" — this matches the common case.
      String last = lastIdentifier(scope);
      if (!"File".equals(last)) return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "createTempFile result not passed through "
            + "setWritable/setReadable/setExecutable — "
            + "predictable perms (CWE-378). The temp file "
            + "inherits the JVM umask, which is typically "
            + "world-readable.",
        RuleDef.Severity.MEDIUM,
        "insecure-temp-perms: " + n.toString());
  }

  private static String lastIdentifier(String scope) {
    if (scope == null || scope.isEmpty()) return null;
    int dot = scope.lastIndexOf('.');
    return dot < 0 ? scope : scope.substring(dot + 1);
  }
}
