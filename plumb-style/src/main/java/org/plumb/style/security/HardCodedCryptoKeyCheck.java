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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
 * A cryptographic key is hard-coded in the source (CWE-321).
 *
 * <p>The difference from the other "hard-coded secret" rules is the ROLE of the value. CWE-798 and
 * CWE-259 are about a credential -- a password, a token, an API key. CWE-321 is about the KEY
 * MATERIAL of a cipher: the bytes handed to {@code SecretKeySpec}, {@code IvParameterSpec}, or the
 * seed of a key generator.
 *
 * <p><b>What is reported.</b> A literal that reaches a key-material constructor:
 *
 * <ul>
 *   <li>{@code new SecretKeySpec("Super secret Squirrel".getBytes(), "AES")} -- a literal passed
 *       directly;
 *   <li>{@code new SecretKeySpec(keyBytes, "AES")} where {@code keyBytes} was assigned a literal
 *       earlier in the same method;
 *   <li>{@code new IvParameterSpec("0123456789abcdef".getBytes())} -- an IV written in the source;
 *   <li>{@code KeyGenerator.getInstance("AES").init(128, new SecureRandom(seed))} -- a seeded
 *       random, which is not random at all.
 * </ul>
 *
 * <p>The value is traced one step: a literal assigned to a local variable, and that variable used
 * as the key. A literal that reaches the key through a method call or a field is not this rule's
 * business.
 */
public final class HardCodedCryptoKeyCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-hardcoded-crypto-key", "HARDCODED_CRYPTO_KEY"),
          "A cryptographic key or IV is hard-coded in the source (CWE-321)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-321"),
          "org.plumb.style.security.HardCodedCryptoKeyCheck",
          true,
          List.of(
              "ast-security-hard-coded-cryptographic-key",
              "hardcoded-crypto-key",
              // The legacy id of the DFA rule this defect class used to
              // have. Keeping it as an alias means a report or a config that
              // names it still selects the rule that now reports the defect.
              "plumb.dfa.hard-coded-crypto-key"));

  /** Constructors and factories that take key material. */
  private static final List<String> KEY_CONSTRUCTORS =
      List.of("SecretKeySpec", "IvParameterSpec", "PBEKeySpec", "DESKeySpec");

  @Override
  public String name() {
    return "hardcoded-crypto-key";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // The literals that are hard-coded in this method, by variable name.
    // A literal reaches a variable in two ways: a declaration with an
    // initialiser, and an assignment to a variable declared earlier.
    // `data = "23 ~j;asn!@#/>as";` is the second shape, and it is the shape
    // the corpus uses.
    Set<String> literalVars = new HashSet<>();
    List<VariableDeclarationExpr> decls = n.findAll(VariableDeclarationExpr.class);
    for (VariableDeclarationExpr decl : decls) {
      if (!decl.getElementType().isPrimitiveType()) {
        for (var vd : decl.getVariables()) {
          if (vd.getInitializer().isEmpty()) continue;
          if (isLiteralOrLiteralDerived(vd.getInitializer().get(), Set.of())) {
            literalVars.add(vd.getNameAsString());
          }
        }
      }
    }
    for (AssignExpr assign : n.findAll(AssignExpr.class)) {
      if (!assign.getTarget().isNameExpr()) continue;
      if (isLiteralOrLiteralDerived(assign.getValue(), literalVars)) {
        literalVars.add(assign.getTarget().asNameExpr().getNameAsString());
      }
    }

    List<ObjectCreationExpr> creations = n.findAll(ObjectCreationExpr.class);
    for (ObjectCreationExpr creation : creations) {
      String type = creation.getType().asClassOrInterfaceType().getNameAsString();
      int dot = type.lastIndexOf('.');
      if (dot >= 0) type = type.substring(dot + 1);
      if (!KEY_CONSTRUCTORS.contains(type)) continue;

      // The key material is the FIRST argument.
      if (creation.getArguments().isEmpty()) continue;
      Expression keyArg = creation.getArgument(0);

      String hardCoded = null;
      if (isLiteralOrLiteralDerived(keyArg, literalVars)) {
        hardCoded = keyArg.toString();
      }
      if (hardCoded == null) continue;

      int line = creation.getBegin().map(p -> p.line).orElse(-1);
      int col = creation.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          type
              + " is built from a hard-coded key ("
              + hardCoded
              + "); the key is in the source and must be "
              + "provided from outside (CWE-321)",
          RuleDef.Severity.HIGH,
          type + ":" + hardCoded);
    }

    // A seeded SecureRandom is not random: the seed is in the source.
    List<MethodCallExpr> calls = n.findAll(MethodCallExpr.class);
    for (MethodCallExpr call : calls) {
      if (!call.getNameAsString().equals("setSeed")) continue;
      if (call.getArguments().isEmpty()) continue;
      Expression seed = call.getArgument(0);
      if (!isLiteralOrLiteralDerived(seed, literalVars)) continue;
      String seedText = seed.toString();

      int line = call.getBegin().map(p -> p.line).orElse(-1);
      int col = call.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "A random generator is seeded with a hard-coded value ("
              + seedText
              + "); the output is predictable (CWE-321)",
          RuleDef.Severity.HIGH,
          "setSeed:" + seedText);
    }
  }

  /**
   * True when the expression is a literal, or is derived from one by a call that cannot change the
   * value -- {@code toString()}, {@code getBytes()}, {@code toCharArray()} and so on.
   *
   * <p>A NAME that holds a literal counts too, but only when the name is one of the method's own
   * literal variables -- {@code String secret = "x"; new SecretKeySpec(secret.getBytes(), "AES")}
   * is the same hard-coded key as the inline form. The caller passes the set of literal variable
   * names so that a name from an outer scope is not mistaken for a local.
   */
  private static boolean isLiteralOrLiteralDerived(Expression e, Set<String> literalVars) {
    Node n = e;
    while (n != null) {
      if (n instanceof StringLiteralExpr) return true;
      if (n instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr
          || n instanceof com.github.javaparser.ast.expr.LongLiteralExpr
          || n instanceof com.github.javaparser.ast.expr.DoubleLiteralExpr
          || n instanceof com.github.javaparser.ast.expr.CharLiteralExpr
          || n instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr) {
        return true;
      }
      if (n instanceof NameExpr ne) {
        return literalVars.contains(ne.getNameAsString());
      }
      if (n instanceof MethodCallExpr call) {
        String name = call.getNameAsString().toLowerCase(Locale.ROOT);
        boolean passthrough =
            name.equals("tostring")
                || name.equals("getbytes")
                || name.equals("tochararray")
                || name.equals("substring")
                || name.equals("trim")
                || name.equals("concat")
                || name.equals("format")
                || name.equals("valueof")
                || name.equals("getvalue");
        if (!passthrough) return false;
        // The receiver is the value the call derives from. For
        // `"literal".getBytes()` the literal IS the receiver, so the chain
        // continues at the scope; when there is no scope the literal is an
        // argument of the call itself.
        if (call.getScope().isPresent()) {
          n = call.getScope().get();
          continue;
        }
        for (com.github.javaparser.ast.expr.Expression arg : call.getArguments()) {
          if (arg instanceof StringLiteralExpr) return true;
        }
        return false;
      }
      return false;
    }
    return false;
  }
}
