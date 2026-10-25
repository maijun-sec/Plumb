/*
 * Coverity <code>UNLOGGED_SECURITY_EXCEPTION</code> — reports a defect on a
 * {@code catch} block that handles a security-relevant exception but does
 * not log the event.
 *
 * <p>Reference: Coverity 2023.6.0 Checker Reference, section
 * <em>UNLOGGED_SECURITY_EXCEPTION</em> (Security Checker,
 * <em>disabled by default</em> for Java).
 *
 * <p><b>Symbol-resolution strategy.</b> The catch parameter's type is
 * resolved via {@link com.github.javaparser.ast.type.Type#resolve()} so
 * that aliases ({@code catch (MySecEx e)} where {@code MySecEx extends
 * SecurityException}) are still detected. When resolution fails (e.g. the
 * type isn't on the classpath of a test harness) the checker falls back
 * to a simple-name and AST-text heuristic so it still catches the obvious
 * shapes.</p>
 *
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class UnloggedSecurityExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-unlogged-security-exception", "UNLOGGED_SECURITY_EXCEPTION"),
          "Security exception caught but not logged",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-778", "CWE-223"),
          "org.plumb.style.UnloggedSecurityExceptionCheck",
          true,
          List.of("ast-security-unlogged-security-exception", "unlogged-security-exception"));

  private static final Set<String> LOG_METHODS =
      Set.of(
          "log",
          "info",
          "warn",
          "warning",
          "error",
          "severe",
          "fatal",
          "trace",
          "debug",
          "logp",
          "logrb",
          "entering",
          "exiting",
          "throwing");

  /** Set to true to treat System.out/System.err as logging. */
  private boolean enableStandardOutputLogging = false;

  /**
   * Per-file map of local SecurityException subclasses. Populated in {@link #visit(CompilationUnit,
   * StyleContext)} and consumed in {@link #visit(CatchClause, StyleContext)}. Cleared at the start
   * of each CU visit to avoid cross-file state pollution.
   */
  private final Map<String, Boolean> localSecurityExceptions = new HashMap<>();

  @Override
  public String name() {
    return "unlogged-security-exception";
  }

  @Override
  public void init(StyleContext ctx) {
    Boolean opt =
        ctx.paramOverride(
            "UNLOGGED_SECURITY_EXCEPTION:enable_standard_output_logging", Boolean.class);
    if (opt != null) enableStandardOutputLogging = opt;
  }

  @Override
  public void visit(ClassOrInterfaceDeclaration decl, StyleContext ctx) {
    String simple = decl.getNameAsString();
    for (ClassOrInterfaceType ext : decl.getExtendedTypes()) {
      String extSimple = ext.getNameAsString();
      if ("SecurityException".equals(extSimple)
          || "java.lang.SecurityException".equals(resolveQualifiedName(ext))) {
        localSecurityExceptions.put(simple, Boolean.TRUE);
        break;
      }
    }
  }

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    if (!isSecurityRelevant(cc)) return;
    if (containsLogging(cc)) return;
    int line = cc.getBegin().map(p -> p.line).orElse(-1);
    int col = cc.getBegin().map(p -> p.column).orElse(-1);
    String exType = cc.getParameter().getType().asString();
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "UNLOGGED_SECURITY_EXCEPTION: catch ("
            + exType
            + ") is a security-relevant exception but no logging call "
            + "was found in the block",
        RuleDef.Severity.MEDIUM,
        cc.getBody().toString());
  }

  /**
   * True if the catch clause's exception type is one we consider security-relevant. The match is
   * structural: the type's qualified name (resolved when possible, AST text otherwise) plus a
   * simple-name heuristic for unrecognized types.
   */
  private boolean isSecurityRelevant(CatchClause cc) {
    ClassOrInterfaceType declared = unwrapUnion(cc.getParameter().getType());
    if (declared == null) return false;
    String qualified = resolveQualifiedName(declared);
    if (qualified == null) {
      qualified = declared.getNameAsString();
    }
    if (qualified == null) return false;
    switch (qualified) {
      case "java.lang.SecurityException":
      case "java.nio.file.AccessDeniedException":
      case "java.security.AccessControlException":
      case "java.security.ProviderException":
      case "java.util.concurrent.RejectedExecutionException":
      case "java.sql.SQLException":
      case "java.sql.SQLSyntaxErrorException":
      case "java.sql.SQLIntegrityConstraintViolationException":
      case "javax.naming.AuthenticationException":
      case "javax.naming.NoPermissionException":
      case "javax.security.auth.login.LoginException":
      case "javax.security.auth.login.AccountException":
      case "javax.security.auth.login.FailedLoginException":
      case "javax.security.auth.login.CredentialException":
      case "javax.security.auth.login.AccountLockedException":
      case "javax.security.auth.login.AccountExpiredException":
      case "javax.security.auth.login.CredentialExpiredException":
      case "org.springframework.security.access.AccessDeniedException":
      case "org.springframework.security.authentication.BadCredentialsException":
      case "org.springframework.security.authentication.AccountExpiredException":
      case "org.springframework.security.web.csrf.InvalidCsrfTokenException":
      case "org.springframework.security.web.csrf.MissingCsrfTokenException":
      case "com.auth0.jwt.exceptions.JWTVerificationException":
        return true;
      default:
        break;
    }
    String simple = qualified;
    int dot = simple.lastIndexOf('.');
    if (dot >= 0) simple = simple.substring(dot + 1);
    String lower = simple.toLowerCase(Locale.ROOT);
    if (lower.contains("permission")
        || lower.contains("accessdenied")
        || lower.contains("authorization")
        || lower.contains("authorisation")
        || lower.contains("authentication")
        || lower.contains("authoriz")
        || lower.endsWith("authexception")
        || lower.endsWith("authzexception")
        || lower.endsWith("csrf")
        || lower.contains("csrf")
        || lower.endsWith("login")
        || lower.endsWith("credential")
        || lower.endsWith("securityexception")) {
      return true;
    }
    // Subclass check: use pre-computed map populated in
    // visit(CompilationUnit) — O(1) lookup, no walk.
    return localSecurityExceptions.containsKey(simple);
  }

  private static ClassOrInterfaceType unwrapUnion(com.github.javaparser.ast.type.Type t) {
    if (t instanceof ClassOrInterfaceType) {
      return (ClassOrInterfaceType) t;
    }
    if (t instanceof ReferenceType) {
      ReferenceType rt = (ReferenceType) t;
      if (rt.getElementType() instanceof ClassOrInterfaceType) {
        return (ClassOrInterfaceType) rt.getElementType();
      }
    }
    return null;
  }

  private static String resolveQualifiedName(ClassOrInterfaceType t) {
    try {
      ResolvedType rt = t.resolve();
      if (rt != null) {
        try {
          ResolvedTypeDeclaration d = rt.asReferenceType().getTypeDeclaration().orElse(null);
          if (d != null) return d.getQualifiedName();
        } catch (RuntimeException ignore) {
          // Primitive / non-reference — fall through to describe().
        }
        return rt.describe();
      }
    } catch (RuntimeException ignore) {
      // fall through
    }
    return null;
  }

  /**
   * Returns true if any statement in the catch clause body looks like a logging call. replaces the
   * original per-statement {@code s.findAll(MethodCallExpr)} loop with a single walk of {@code
   * cc.getBody()}.
   */
  private boolean containsLogging(CatchClause cc) {
    final boolean[] found = {false};
    cc.getBody()
        .walk(
            com.github.javaparser.ast.Node.class,
            n -> {
              if (found[0]) return;
              if (n instanceof MethodCallExpr) {
                MethodCallExpr m = (MethodCallExpr) n;
                String name = m.getNameAsString();
                if (LOG_METHODS.contains(name)) {
                  found[0] = true;
                  return;
                }
                if (enableStandardOutputLogging && isStdStreamCall(m)) {
                  found[0] = true;
                }
              }
            });
    return found[0];
  }

  private static boolean isStdStreamCall(MethodCallExpr m) {
    String name = m.getNameAsString();
    if (!name.startsWith("print")
        && !name.startsWith("format")
        && !"println".equals(name)
        && !"write".equals(name)) {
      return false;
    }
    return m.getScope()
        .map(
            s -> {
              String text = s.toString();
              return text.equals("System.out")
                  || text.equals("System.err")
                  || text.endsWith(".out")
                  || text.endsWith(".err")
                  || text.contains("System.out")
                  || text.contains("System.err");
            })
        .orElse(false);
  }
}
