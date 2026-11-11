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
package org.plumb.tac.ir;

/**
 * The ONE canonical form of a callee key.
 *
 * <p><b>Why this exists.</b> Three frontends, three signature shapes:
 *
 * <ul>
 *   <li>Java emits {@code "run(String):void"} for a FUNCTION and {@code "Repository.helper/1"} for
 *       a CALL.
 *   <li>Kotlin emits {@code "demo.JavaSink.run/1"} for a CALL.
 *   <li>Scala emits {@code "demo.JavaSink.run/1"} for a CALL.
 * </ul>
 *
 * <p>The call graph connects a caller to a callee by comparing a key. When the two sides of a
 * cross-language call compute DIFFERENT keys for the SAME method, the edge is never built -- and a
 * cross-language taint flow is silently not followed. That is not a precision loss; it is a
 * capability that is missing while the scan still reports success.
 *
 * <p>So the key is a SHARED CONVENTION, not a per-frontend detail. This class is that convention.
 * Every frontend normalises its callee key through {@link #canonicalCalleeKey}, and the call graph
 * normalises both sides through the same method, so the two always agree.
 *
 * <p><b>The canonical form is {@code owner.method/arity}.</b>
 *
 * <ul>
 *   <li>{@code "run(String):void"} &rarr; {@code "run/1"} (no owner known)
 *   <li>{@code "demo.JavaSink.run/1"} &rarr; {@code "demo.JavaSink.run/1"}
 *   <li>{@code "Repository.helper/1"} &rarr; {@code "Repository.helper/1"}
 * </ul>
 *
 * <p>The OWNER IS PART OF THE KEY, and that is deliberate. Two overloads in different classes with
 * the same name and arity are DIFFERENT methods, and a key that dropped the owner would merge them.
 * The owner is what makes the key precise; the arity is what makes it cheap.
 *
 * <p><b>Why the owner is matched by SIMPLE NAME.</b> A Java function's owner is the short class
 * name ({@code JavaSink}), while a Kotlin call's owner is the fully qualified name ({@code
 * demo.JavaSink}). Those are the SAME class. The normaliser therefore compares owners by their LAST
 * SEGMENT, so {@code demo.JavaSink} and {@code JavaSink} are recognised as the same owner.
 *
 * <p>That comparison is only safe because it is used to build a MATCH KEY, not to decide identity:
 * the canonical key keeps the owner the CALL SITE saw, so a caller that knows the fully qualified
 * name produces a fully qualified key. The bare-name key is the FALLBACK used to find a candidate,
 * and it is what actually joins the two sides.
 *
 * <p><b>This class is IR-level on purpose.</b> It has no dependency on any frontend, on the DFA
 * engine, or on any language-specific table. It is a convention, and conventions belong where every
 * participant can see them.
 */
public final class CalleeKeyNormalizer {

  private CalleeKeyNormalizer() {}

  /**
   * The canonical key for a callee, in the form {@code owner.method/arity}.
   *
   * <p>Accepts every shape the frontends produce:
   *
   * <ul>
   *   <li>{@code "run(String):void"} -- the Java paren form
   *   <li>{@code "run/1"} -- the bare slash form
   *   <li>{@code "demo.JavaSink.run/1"} -- the qualified slash form
   *   <li>{@code "demo.JavaSink.run(String):void"} -- a qualified paren form
   * </ul>
   *
   * @param sig the signature, in any of the shapes above
   * @return the canonical {@code owner.method/arity} key, or {@code null} when the signature
   *     carries no method name
   */
  public static String canonicalCalleeKey(String sig) {
    if (sig == null) return null;
    String trimmed = sig.trim();
    if (trimmed.isEmpty()) return null;

    String name;
    int arity;

    int paren = trimmed.indexOf('(');
    if (paren >= 0) {
      // The paren form: "owner.method(Param,Param):Ret" or "method(Param):Ret".
      name = trimmed.substring(0, paren);
      int close = trimmed.indexOf(')', paren);
      if (close < 0) return null;
      arity = countArity(trimmed.substring(paren + 1, close));
    } else {
      // The slash form: "owner.method/2" or "method/2".
      int slash = trimmed.lastIndexOf('/');
      if (slash < 0) {
        // No arity and no parameter list. There is nothing to key on beyond the name.
        name = trimmed;
        arity = -1;
      } else {
        name = trimmed.substring(0, slash);
        String arityText = trimmed.substring(slash + 1).trim();
        try {
          arity = Integer.parseInt(arityText);
        } catch (NumberFormatException ex) {
          return null;
        }
      }
    }

    if (name.isEmpty()) return null;

    // The owner is everything before the LAST dot; the method name is after it.
    int dot = name.lastIndexOf('.');
    String owner = dot >= 0 ? name.substring(0, dot) : "";
    String method = dot >= 0 ? name.substring(dot + 1) : name;
    if (method.isEmpty()) return null;

    // A Jimple-style "G#use" form: the hash is the owner separator.
    if (owner.isEmpty() && method.indexOf('#') >= 0) {
      int hash = method.lastIndexOf('#');
      owner = method.substring(0, hash);
      method = method.substring(hash + 1);
      if (method.isEmpty()) return null;
    }

    return arity < 0
        ? (owner.isEmpty() ? method : owner + "." + method)
        : (owner.isEmpty() ? method + "/" + arity : owner + "." + method + "/" + arity);
  }

  /**
   * The BARE key: the canonical key with the owner dropped.
   *
   * <p>This is the key the call graph matches on when one side knows the owner and the other does
   * not. It is deliberately coarser than the canonical key, so it is only used to FIND a candidate;
   * the candidate is then confirmed on the canonical key.
   *
   * @param sig the signature, in any shape
   * @return {@code method/arity}, or {@code null} when the signature carries no method name
   */
  public static String bareCalleeKey(String sig) {
    String canonical = canonicalCalleeKey(sig);
    if (canonical == null) return null;
    int slash = canonical.lastIndexOf('/');
    String method = slash < 0 ? canonical : canonical.substring(0, slash);
    int dot = method.lastIndexOf('.');
    if (dot >= 0) method = method.substring(dot + 1);
    return slash < 0 ? method : method + canonical.substring(slash);
  }

  /**
   * The owner's SIMPLE NAME: the last dot-separated segment of the owner.
   *
   * <p>Two owners that share a simple name are treated as the same owner for MATCHING purposes. A
   * Java function's owner is {@code JavaSink}; a Kotlin call's owner is {@code demo.JavaSink}. They
   * name the same class.
   *
   * @param sig the signature, in any shape
   * @return the simple owner name, or {@code null} when the signature carries no owner
   */
  public static String ownerSimpleName(String sig) {
    String canonical = canonicalCalleeKey(sig);
    if (canonical == null) return null;
    int slash = canonical.lastIndexOf('/');
    String qualified = slash < 0 ? canonical : canonical.substring(0, slash);
    int dot = qualified.lastIndexOf('.');
    if (dot < 0) return null;
    // The OWNER is everything before the last dot; the method name is after it. Taking
    // the last segment of the whole qualified string would answer with the METHOD's name
    // ("run" for "demo.JavaSink$.run/1"), and a caller that builds an owner-qualified key
    // from it would key on "run.run/1" -- a key no declaration is ever published under.
    String owner = qualified.substring(0, dot);
    int ownerDot = owner.lastIndexOf('.');
    return ownerDot < 0 ? owner : owner.substring(ownerDot + 1);
  }

  /**
   * Do two signatures name the same method?
   *
   * <p>Two signatures match when they agree on the method name and the arity, and their owners
   * either agree exactly or agree by SIMPLE NAME. The simple-name comparison is what makes a Kotlin
   * call to a Java method match: the Kotlin side sees {@code demo.JavaSink} and the Java side sees
   * {@code JavaSink}.
   *
   * <p>The owners also match when they agree after the MODULE-CLASS suffix is normalised. That
   * suffix is what makes a Scala call to a Java method match, and the reason is a naming convention
   * of the Scala side rather than a difference between the methods.
   *
   * <p><b>What the module class is.</b> A Java class's STATIC members are not members of the class
   * in Scala's view: dotc models them as members of a synthetic MODULE CLASS, whose name is the
   * Java class's name with a {@code $} appended. So a Scala call to {@code JavaSink.readAll(in)}
   * names the callee {@code demo.JavaSink$.readAll/1}, while the Java declaration is {@code
   * demo.JavaSink.readAll} in {@code demo.JavaSink}. Two spellings of one method.
   *
   * <p><b>Why the suffix is normalised rather than the owner.</b> The alternative -- stripping the
   * {@code $} from the Scala owner -- would be a change to what the frontend emits, and it would be
   * wrong for a Scala class that genuinely ends in {@code $}. A Scala object named {@code Service$}
   * IS a different class from one named {@code Service}, and merging them would merge two real,
   * distinct methods. The suffix is therefore stripped only when COMPARING two owners, where the
   * comparison already tolerates a difference in spelling (the simple-name rule), and never in the
   * key that is emitted.
   *
   * <p>When either side has NO owner, the owners are not compared: a signature without an owner is
   * not evidence of a different owner.
   *
   * @param a one signature
   * @param b the other signature
   * @return true when they name the same method
   */
  public static boolean sameMethod(String a, String b) {
    String ka = canonicalCalleeKey(a);
    String kb = canonicalCalleeKey(b);
    if (ka == null || kb == null) return false;

    String methodA = methodOf(ka);
    String methodB = methodOf(kb);
    if (!methodA.equals(methodB)) return false;

    String arityA = arityOf(ka);
    String arityB = arityOf(kb);
    if (arityA != null && arityB != null && !arityA.equals(arityB)) return false;

    String ownerA = ownerOf(ka);
    String ownerB = ownerOf(kb);
    if (ownerA == null || ownerB == null) return true;
    if (ownerA.equals(ownerB)) return true;
    if (simpleOf(ownerA).equals(simpleOf(ownerB))) return true;
    return simpleOf(ownerA).equals(stripModuleClassSuffix(simpleOf(ownerB)))
        || simpleOf(ownerB).equals(stripModuleClassSuffix(simpleOf(ownerA)));
  }

  /**
   * The owner's name with the Scala module-class suffix removed.
   *
   * <p>A Scala object or a Java class seen from Scala has a MODULE CLASS whose name is the original
   * name with {@code $} appended. The suffix is part of the class's BINARY name, so it cannot be
   * removed in general -- {@code Service$} and {@code Service} are two different classes. It is
   * removed only when comparing two owners for a match, where the comparison is already about "are
   * these the same class, spelled differently".
   *
   * @param simpleOwner the owner's simple name
   * @return the name without a trailing {@code $}
   */
  private static String stripModuleClassSuffix(String simpleOwner) {
    if (simpleOwner == null || simpleOwner.length() < 2) return simpleOwner;
    if (!simpleOwner.endsWith("$")) return simpleOwner;
    return simpleOwner.substring(0, simpleOwner.length() - 1);
  }

  private static String methodOf(String canonical) {
    int slash = canonical.lastIndexOf('/');
    String qualified = slash < 0 ? canonical : canonical.substring(0, slash);
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? qualified : qualified.substring(dot + 1);
  }

  private static String ownerOf(String canonical) {
    int slash = canonical.lastIndexOf('/');
    String qualified = slash < 0 ? canonical : canonical.substring(0, slash);
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? null : qualified.substring(0, dot);
  }

  private static String arityOf(String canonical) {
    int slash = canonical.lastIndexOf('/');
    return slash < 0 ? null : canonical.substring(slash + 1);
  }

  private static String simpleOf(String owner) {
    int dot = owner.lastIndexOf('.');
    return dot < 0 ? owner : owner.substring(dot + 1);
  }

  /**
   * The number of parameters in a parameter list.
   *
   * <p>The list is comma-separated, but a generic type argument may itself contain a comma ({@code
   * Map<String,Integer>}), so the angle-bracket depth is tracked.
   *
   * @param paramList the text between the parentheses
   * @return the parameter count
   */
  private static int countArity(String paramList) {
    if (paramList.isEmpty()) return 0;
    int depth = 0;
    int arity = 0;
    int last = 0;
    for (int i = 0; i < paramList.length(); i++) {
      char c = paramList.charAt(i);
      if (c == '<') depth++;
      else if (c == '>') depth--;
      else if (c == ',' && depth == 0) {
        if (i > last) arity++;
        last = i + 1;
      }
    }
    if (paramList.length() > last) arity++;
    return arity;
  }
}
