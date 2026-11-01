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
package org.plumb.style.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * A cookie stores a value that was never hashed or encrypted (CWE-315).
 *
 * <p>A cookie is readable by the browser and by anything that can read the browser's cookie store,
 * and it travels over the wire. A credential stored in one in plaintext is therefore exposed. The
 * fix is to hash or encrypt the value first.
 *
 * <p><b>What the rule checks.</b> A {@code new Cookie(name, value)} whose value is not the result
 * of a hash. The value counts as hashed when the method has already run it through a digest --
 * {@code MessageDigest.digest}, {@code SHA-256} of a password, a BCrypt/Argon2/PBKDF2 call, or a
 * {@code Base64} of an encrypted value.
 *
 * <p>The check is on the VALUE, not on the name: a cookie called {@code "theme"} holding a colour
 * is not a defect, and a cookie called {@code "auth"} holding a hash is fine.
 */
public final class PlaintextCookieCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-plaintext-cookie", "PLAINTEXT_COOKIE"),
          "A cookie stores a value that was never hashed or encrypted " + "(CWE-315)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-315"),
          "org.plumb.style.security.PlaintextCookieCheck",
          true,
          List.of("ast-security-plaintext-storage-in-cookie", "plaintext-cookie"));

  /** Method names whose result is a hash or a cipher output. */
  private static final List<String> HASH_METHODS =
      List.of(
          "digest",
          "hash",
          "hashpw",
          "hashcode",
          "scrypt",
          "bcrypt",
          "argon2",
          "pbkdf2",
          "derivekey",
          "encrypt",
          "doFinal");

  /** Types whose instances are a digest or a cipher. */
  private static final Set<String> HASH_TYPES =
      Set.of("MessageDigest", "Mac", "Cipher", "SecretKeyFactory", "KeyGenerator");

  @Override
  public String name() {
    return "plaintext-cookie";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // The names that hold a hashed value in this method.
    Set<String> hashedVars = hashedVariables(n);
    // The names that hold a plain value in this method.
    Set<String> plainVars = plainVariables(n);

    List<ObjectCreationExpr> creations = n.findAll(ObjectCreationExpr.class);
    for (ObjectCreationExpr creation : creations) {
      if (!creation.getType().isClassOrInterfaceType()) continue;
      String type = creation.getType().asClassOrInterfaceType().getNameAsString();
      int dot = type.lastIndexOf('.');
      if (dot >= 0) type = type.substring(dot + 1);
      if (!type.equals("Cookie")) continue;
      if (creation.getArguments().size() < 2) continue;

      // The VALUE is the second argument.
      com.github.javaparser.ast.expr.Expression value = creation.getArgument(1);
      if (isHashed(value, hashedVars)) continue;

      String valueName = valueName(value);
      // A value nobody can name is not something this rule can judge.
      if (valueName == null) continue;

      // Only a value that is plainly a plain value is reported: the rule
      // says "this is stored in plaintext", so it needs evidence.
      if (!plainVars.contains(valueName) && !value.isNameExpr()) continue;

      int line = creation.getBegin().map(p -> p.line).orElse(-1);
      int col = creation.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "The cookie value '"
              + valueName
              + "' is stored in plaintext; hash "
              + "or encrypt it before putting it in a cookie (CWE-315)",
          RuleDef.Severity.MEDIUM,
          "cookie:" + valueName);
    }
  }

  /** The name of the variable the expression refers to, or null. */
  private static String valueName(com.github.javaparser.ast.expr.Expression e) {
    if (e.isNameExpr()) return e.asNameExpr().getNameAsString();
    if (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee && ee.getInner().isNameExpr()) {
      return ee.getInner().asNameExpr().getNameAsString();
    }
    return null;
  }

  /**
   * True when the expression is the result of a digest, a hash or a cipher.
   *
   * <p>A value counts as hashed when it is produced by a digest, a hash or a cipher -- directly, or
   * through a name that already holds such a result. A call that CONSUMES a hashed value and
   * returns its text form -- {@code IO.toHex(hashed)} -- produces a hashed value too, so the
   * arguments are followed as well.
   */
  private static boolean isHashed(
      com.github.javaparser.ast.expr.Expression e, Set<String> hashedVars) {
    Node n = e;
    while (n != null) {
      if (n instanceof NameExpr ne) {
        return hashedVars.contains(ne.getNameAsString());
      }
      if (n instanceof MethodCallExpr call) {
        String name = call.getNameAsString().toLowerCase(Locale.ROOT);
        for (String candidate : HASH_METHODS) {
          if (name.equals(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        // A call on a digest or a cipher is a hash even when the method name
        // is not one of the known ones.
        if (call.getScope().isPresent()) {
          String scope = call.getScope().get().toString();
          for (String t : HASH_TYPES) {
            if (scope.contains(t)) return true;
          }
        }
        // A call whose argument is already hashed keeps the value hashed --
        // `IO.toHex(hashed)` is the text form of a digest.
        for (com.github.javaparser.ast.expr.Expression arg : call.getArguments()) {
          if (isHashed(arg, hashedVars)) return true;
        }
        if (call.getScope().isPresent()) {
          n = call.getScope().get();
          continue;
        }
        return false;
      }
      return false;
    }
    return false;
  }

  /**
   * The local variables that hold a hashed value: a variable assigned from a digest, a hash or a
   * cipher call.
   *
   * <p>The set is a FIXPOINT. A value can be hashed through a chain -- {@code byte[] h =
   * digest.digest(...); String s = IO.toHex(h);} -- and the second link can only be recognised once
   * the first is known. One pass over the assignments, repeated until nothing new is added,
   * resolves the chain without needing an order.
   */
  private static Set<String> hashedVariables(Node scope) {
    Set<String> out = new HashSet<>();
    List<AssignExpr> assigns = scope.findAll(AssignExpr.class);
    List<VariableDeclarationExpr> decls = scope.findAll(VariableDeclarationExpr.class);

    boolean changed = true;
    while (changed) {
      changed = false;
      for (AssignExpr assign : assigns) {
        if (!assign.getTarget().isNameExpr()) continue;
        String name = assign.getTarget().asNameExpr().getNameAsString();
        if (out.contains(name)) continue;
        if (isHashed(assign.getValue(), out)) {
          out.add(name);
          changed = true;
        }
      }
      for (VariableDeclarationExpr decl : decls) {
        for (var vd : decl.getVariables()) {
          if (vd.getInitializer().isEmpty()) continue;
          String name = vd.getNameAsString();
          if (out.contains(name)) continue;
          if (isHashed(vd.getInitializer().get(), out)) {
            out.add(name);
            changed = true;
          }
        }
      }
    }
    return out;
  }

  /**
   * The local variables that hold a plain value: a variable assigned from something that is not a
   * digest.
   */
  private static Set<String> plainVariables(Node scope) {
    Set<String> out = new HashSet<>();
    for (AssignExpr assign : scope.findAll(AssignExpr.class)) {
      if (!assign.getTarget().isNameExpr()) continue;
      if (!isHashed(assign.getValue(), Set.of())) {
        out.add(assign.getTarget().asNameExpr().getNameAsString());
      }
    }
    return out;
  }
}
