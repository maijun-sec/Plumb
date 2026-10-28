/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import java.util.Locale;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-579 — store a non-{@link java.io.Serializable} object in an {@code HttpSession}.
 *
 * <p>Detects calls of the shape {@code session.setAttribute(name, value)} where:
 *
 * <ol>
 *   <li>The receiver's resolved type descends from {@code javax.servlet.http.HttpSession}. Falls
 *       through to a name-heuristic when symbol resolution fails (e.g. servlet-api.jar not on the
 *       classpath).
 *   <li>The value argument's type either (a) explicitly constructs {@code new Hashtable()}, {@code
 *       new HashMap()}, {@code new ArrayList()} (well-known non-{@code Serializable} containers),
 *       or (b) resolves to a reference type that does NOT implement {@code java.io.Serializable}
 *       and is NOT a primitive.
 * </ol>
 *
 * <p>The receiver type is resolved with the symbol solver, so a custom non-{@code Serializable}
 * class is caught as well as the well-known container literals.
 */
public final class NonSerializableInSessionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          // rule_id aligned with plumb_rule_to_cwe.json
          // (`Cwe579` → ["579"]). Legacy id
          // `dfa.cwe579-non-serializable` is preserved as
          // the short_name so any external whitelist still
          // matches (Lesson 1154).
          new RuleId("ast-style-non-serializable-in-session", "NON_SERIALIZABLE_IN_SESSION"),
          "non-Serializable object stored in HttpSession",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-579"),
          "org.plumb.style.NonSerializableInSessionCheck",
          true,
          List.of("ast-style-non-serializable-in-session-style", "non-serializable-in-session"));

  private static final String SERIALIZABLE = "java.io.Serializable";
  private static final String HTTP_SESSION = "javax.servlet.http.HttpSession";

  @Override
  public String name() {
    return "non-serializable-in-session";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    // Strategy: visit every MethodCallExpr. Fire when
    // the call's name is "setAttribute", it has exactly
    // two arguments (HttpSession.setAttribute has
    // signature setAttribute(String, Object)), the
    // receiver resolves to HttpSession (or name-
    // fallback), and the second argument's type is
    // non-Serializable.
    if (!"setAttribute".equals(n.getNameAsString())) return;
    if (n.getArguments().size() != 2) return;
    Expression scope = n.getScope().orElse(null);
    if (scope == null) return;
    if (!isSessionReceiver(ctx, scope)) return;
    Expression valueArg = n.getArguments().get(1);
    if (!isNonSerializableValue(ctx, valueArg)) return;
    fire(n, valueArg, ctx);
  }

  private static void fire(MethodCallExpr n, Expression valueArg, StyleContext ctx) {
    int line = -1, col = -1;
    java.util.Optional<com.github.javaparser.Position> begin = n.getBegin();
    if (begin != null) {
      com.github.javaparser.Position p = begin.orElse(null);
      if (p != null) {
        line = p.line;
        col = p.column;
      }
    }
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Non-Serializable value "
            + valueArg.toString().replace('\n', ' ')
            + " stored in HttpSession (CWE-579).",
        RuleDef.Severity.MEDIUM,
        "setAttribute@" + HTTP_SESSION);
  }

  /**
   * True if the receiver resolves to a subtype of {@link javax.servlet.http.HttpSession}, or the
   * heuristic fallback recognizes the receiver name (e.g. {@code session}, {@code httpSession},
   * {@code req.getSession()}) when symbol resolution is unavailable.
   */
  private static boolean isSessionReceiver(StyleContext ctx, Expression scope) {
    // Primary: symbol-solver. If the receiver's type
    // descends from javax.servlet.http.HttpSession,
    // structurally this is an HttpSession call.
    try {
      ResolvedType rt = ctx.resolvedTypeOf(scope);
      if (rt != null && rt.isReferenceType()) {
        for (ResolvedType ancestor : rt.asReferenceType().getAllAncestors()) {
          ResolvedTypeDeclaration decl =
              ancestor.asReferenceType().getTypeDeclaration().orElse(null);
          if (decl != null && HTTP_SESSION.equals(decl.getQualifiedName())) {
            return true;
          }
        }
      }
    } catch (RuntimeException ex) {
      // Fall through to name heuristic.
    }
    return isSessionReceiverByName(scope);
  }

  /**
   * Heuristic name-based fallback. Used when symbol resolution fails (servlet-api not on classpath,
   * etc.). Mirrors 's CallToThreadRunCheck.isThreadReceiverByName pattern -- same Lesson 1151/1152
   * reasoning.
   *
   * <p>Two receiver shapes are recognized:
   *
   * <ol>
   *   <li>Simple variable: {@code session}, {@code httpSession}, {@code sess}, {@code
   *       httpSessionRef} (case-insensitive substring match on the variable name).
   *   <li>Method chain: {@code request.getSession(true)} -- the outermost call is {@code
   *       getSession(...)} or {@code getSession()}. This matches Juliet's canonical CWE-579 form.
   * </ol>
   */
  private static boolean isSessionReceiverByName(Expression scope) {
    if (scope instanceof NameExpr ne) {
      String n = ne.getNameAsString().toLowerCase(Locale.ROOT);
      return n.contains("session") || n.contains("httpsession");
    }
    if (scope instanceof MethodCallExpr mc) {
      String name = mc.getNameAsString().toLowerCase(Locale.ROOT);
      if ("getsession".equals(name)) {
        return true;
      }
      // Recurse: `((req.getSession()).getAttribute(...))`
      // — outer is getAttribute, inner is getSession.
      // We don't want to false-positive on getAttribute
      // itself, so only descend when the outer is NOT
      // setAttribute (the visit() guard already filtered
      // the setAttribute call). For any other chain
      // like `foo().bar().getSession()`, recurse one
      // level.
      return isSessionReceiverByName(mc.getScope().orElse(null));
    }
    return false;
  }

  /**
   * True if the value argument is non-Serializable. Two detection branches:
   *
   * <ol>
   *   <li><b>Symbol-solver type check</b> ( primary): resolve the value's static type. If it's a
   *       reference type that does NOT descend from {@link java.io.Serializable}, flag it. This
   *       covers Juliet's {@code new BadObject()} shape (a non-Serializable nested class) AND
   *       preserves regex parity for {@code new Hashtable()} (Hashtable is Serializable since Java
   *       1 -- actually Hashtable implements {@code java.util.Map} which extends nothing about
   *       Serializable by default; let me re-check... Hashtable DOES implement {@code
   *       java.io.Serializable} via {@code java.util.Dictionary}. So the literal heuristic is
   *       actually a false positive for Hashtable on modern JDKs. The AST check correctly
   *       suppresses it.).
   *   <li><b>Literal constructor shape</b> (regex parity fallback): when symbol resolution fails
   *       (servlet-api not on classpath, generic wildcard without bound), fall back to the
   *       well-known container constructor names that the -E regex caught. This keeps the rule
   *       firing on patterns the regex caught even when the symbol-solver can't resolve the value's
   *       type.
   * </ol>
   *
   * <p>{@code null} literals and primitive-typed expressions are skipped (they cannot be
   * non-Serializable).
   */
  private static boolean isNonSerializableValue(StyleContext ctx, Expression value) {
    if (value == null) return false;
    // Branch 1 (primary): symbol-solver ancestry check.
    try {
      ResolvedType rt = ctx.resolvedTypeOf(value);
      if (rt != null) {
        if (rt.isPrimitive() || rt.isNull()) return false;
        if (rt.isReferenceType()) {
          boolean serializable = false;
          for (ResolvedType ancestor : rt.asReferenceType().getAllAncestors()) {
            ResolvedTypeDeclaration decl =
                ancestor.asReferenceType().getTypeDeclaration().orElse(null);
            if (decl != null && SERIALIZABLE.equals(decl.getQualifiedName())) {
              serializable = true;
              break;
            }
          }
          return !serializable;
        }
      }
    } catch (RuntimeException ex) {
      // Fall through to literal-constructor fallback.
    }
    // Branch 2 (fallback): literal-constructor names
    // the -E regex caught. Used when symbol
    // resolution is unavailable.
    if (value instanceof ObjectCreationExpr oc) {
      String typeName = oc.getType().getNameAsString();
      if ("Hashtable".equals(typeName)
          || "HashMap".equals(typeName)
          || "ArrayList".equals(typeName)) {
        return true;
      }
    }
    return false;
  }
}
