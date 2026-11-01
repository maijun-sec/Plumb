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

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * A {@code public static final} field holds a mutable value (CWE-582 / CWE-607).
 *
 * <p>{@code final} fixes the REFERENCE, not the object. An array, a collection or a {@code Date}
 * behind a {@code public static final} field is reachable and writable by every caller, so any of
 * them can change what every other caller sees.
 *
 * <p>The two CWE ids name the same defect class: CWE-582 is "Array Public Final Static" and CWE-607
 * is "Public Static Final Mutable". A rule keeps ONE id per defect class, so both numbers are
 * carried on the same rule.
 *
 * <p><b>What counts as mutable here.</b>
 *
 * <ul>
 *   <li>an ARRAY -- the elements are writable through the reference;
 *   <li>a value of a known MUTABLE type -- a collection, a date, a calendar, a buffer, a map, a
 *       set, a list.
 * </ul>
 *
 * <p>A known immutable type (a String, a boxed primitive, a UUID) is not reported: {@code final}
 * really does make it fixed. An unknown type is not reported either: a type the analyser cannot
 * classify is not evidence of mutability, and reporting it would be a guess.
 */
public final class MutableStaticFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-mutable-static-field", "MUTABLE_STATIC_FIELD"),
          "A public static final field holds a mutable value that any caller "
              + "can change (CWE-582)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-582", "CWE-607"),
          "org.plumb.style.security.MutableStaticFieldCheck",
          true,
          List.of("ast-security-public-static-final-mutable", "mutable-static-field"));

  /** Simple names of types that are mutable even though the reference is final. */
  private static final Set<String> MUTABLE_SIMPLE_NAMES =
      Set.of(
          "ArrayList",
          "LinkedList",
          "List",
          "Vector",
          "Stack",
          "HashMap",
          "LinkedHashMap",
          "TreeMap",
          "Map",
          "HashSet",
          "LinkedHashSet",
          "TreeSet",
          "Set",
          "Collection",
          "ArrayDeque",
          "Deque",
          "PriorityQueue",
          "Hashtable",
          "Properties",
          "Date",
          "Calendar",
          "GregorianCalendar",
          "SimpleTimeZone",
          "StringBuilder",
          "StringBuffer",
          "ByteBuffer",
          "CharBuffer",
          "RandomAccessFile");

  @Override
  public String name() {
    return "mutable-static-field";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (!n.isPublic() || !n.isStatic() || !n.isFinal()) return;

    for (VariableDeclarator vd : n.getVariables()) {
      String mutableReason = mutabilityReason(vd);
      if (mutableReason == null) continue;

      int line = vd.getBegin().map(p -> p.line).orElse(-1);
      int col = vd.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "'"
              + vd.getNameAsString()
              + "' is public static final but holds a "
              + mutableReason
              + ", so any caller can change it (CWE-582)",
          RuleDef.Severity.MEDIUM,
          vd.getNameAsString());
    }
  }

  /**
   * Why the declared value is mutable, or null when it is not mutable in a way this rule reports.
   */
  private static String mutabilityReason(VariableDeclarator vd) {
    // An array type is mutable by definition.
    if (vd.getType() instanceof ArrayType) {
      return "an array";
    }
    // An array created inline: `static final int[] X = {1, 2};`
    if (vd.getInitializer().isPresent() && vd.getInitializer().get() instanceof ArrayCreationExpr) {
      return "an array";
    }
    // The DECLARED type is what the field promises. `public static final
    // List<String> NAMES = new ArrayList<>()` declares a List -- an interface
    // whose instances are mutable -- so the declared type is enough.
    if (vd.getType() instanceof ClassOrInterfaceType cit) {
      String simple = simpleTypeName(cit.getNameAsString());
      if (MUTABLE_SIMPLE_NAMES.contains(simple)) {
        return "a " + simple;
      }
    }
    // The declared type may be an interface that says nothing about
    // mutability; the INITIALISER says what the value actually is.
    if (vd.getInitializer().isPresent()
        && vd.getInitializer().get() instanceof ObjectCreationExpr oce) {
      if (oce.getType().isClassOrInterfaceType()) {
        String simple = simpleTypeName(oce.getType().asClassOrInterfaceType().getNameAsString());
        if (MUTABLE_SIMPLE_NAMES.contains(simple)) {
          return "a " + simple;
        }
      }
    }
    return null;
  }

  /**
   * The bare type name: no package prefix, no type arguments.
   *
   * <p>JavaParser's {@code getNameAsString()} on a {@code ClassOrInterfaceType} returns the name
   * WITH its type arguments -- {@code List<String>}, not {@code List}. Comparing that against a
   * list of known type names never matches, so the arguments have to come off first.
   */
  private static String simpleTypeName(String name) {
    String s = name;
    int lt = s.indexOf('<');
    if (lt >= 0) {
      int gt = s.lastIndexOf('>');
      s = gt > lt ? s.substring(0, lt) : s.substring(0, lt);
    }
    int dot = s.lastIndexOf('.');
    if (dot >= 0) s = s.substring(dot + 1);
    return s.trim();
  }
}
