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
package org.plumb.dfa.cg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fully qualified function signature.
 *
 * <p>A {@code FunctionSignature} decomposes a method reference into its structured parts:
 *
 * <pre>
 *   package          e.g. "com.example.foo"
 *   className        e.g. "Helper"            (no package prefix; for inner classes
 *                                              may itself contain '$' such as
 *                                              "Outer$Inner" or "Outer$1")
 *   method           e.g. "doIt"              (no class prefix)
 *   paramTypes       e.g. ["java.lang.String", "int"]
 *   returnType       e.g. "void"              (or null for ctor)
 * </pre>
 *
 * <p><b>Inner-class support.</b> Java inner classes are encoded in the IR with {@code '$'} as a
 * separator, e.g. {@code com.example.Outer$Inner#compute(int):int}. The class name field preserves
 * the full chain ({@code "Outer$Inner"}); the {@link #enclosingClasses()} list breaks the chain
 * into its parts ({@code ["Outer", "Inner"]}) so CHA / RTA can reason about the outer-enclosing
 * scope. For anonymous classes the leaf segment is a numeric index such as {@code "Outer$1"}; the
 * parser preserves it verbatim.
 *
 * <p>Combined, these produce a canonical string form: {@code
 * com.example.foo.Outer$Inner#compute(int):void} (lowercased). Two signatures are equal iff every
 * part matches case-insensitively. The structured form lets strategies (CHA, RTA) reason about
 * class membership and overriding methods without re-parsing strings.
 *
 * <h2>String format</h2>
 *
 * <p>Canonical: {@code <package>.<class>#<method>(<param-types>):<return>}. The {@code <class>}
 * segment may itself contain {@code '$'} for inner / nested / anonymous classes. Examples:
 *
 * <ul>
 *   <li>{@code com.example.Helper#doIt(java.lang.String):void}
 *   <li>{@code com.example.Foo#main(java.lang.String[]):void}
 *   <li>{@code com.example.Outer$Inner#compute(int):int}
 *   <li>{@code com.example.Outer$1#run():void} (anonymous Runnable)
 *   <li>{@code com.example.Foo#<init>():void} (a constructor)
 * </ul>
 *
 * <p>Acceptable inputs: any string parseable by {@link #parse(String)} -- supports the JNI-style
 * {@code Lcom/example/Helper;.doIt(Ljava/lang/String;)V} and the older {@code com.example.Foo#doIt}
 * (no param types) for backward compat.
 */
public final class FunctionSignature {

  private final String packageName;

  /** Full class name with '$' as inner-class separator. */
  private final String className;

  /** Simple (leaf) class name — last '$'-segment. */
  private final String simpleClassName;

  /**
   * Outer-class chain from outermost to innermost; empty if the class is top-level. For
   * "Outer$Middle$Inner" returns ["Outer", "Middle"].
   */
  private final List<String> enclosingClasses;

  private final String method;
  private final List<String> paramTypes;
  private final String returnType;

  public FunctionSignature(
      String packageName,
      String className,
      String method,
      List<String> paramTypes,
      String returnType) {
    this.packageName = packageName == null ? "" : packageName;
    this.className = Objects.requireNonNull(className, "className");
    this.method = Objects.requireNonNull(method, "method");
    this.paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
    this.returnType = returnType;
    // Decompose className into simple + enclosing chain
    String[] parts = this.className.split("\\$", -1);
    List<String> enclosing = new ArrayList<>(parts.length - 1);
    for (int i = 0; i < parts.length - 1; i++) {
      if (!parts[i].isEmpty()) enclosing.add(parts[i]);
    }
    this.enclosingClasses = Collections.unmodifiableList(enclosing);
    this.simpleClassName = parts[parts.length - 1];
  }

  public String packageName() {
    return packageName;
  }

  /** Full class name with '$' as inner-class separator. */
  public String className() {
    return className;
  }

  /** Leaf segment of {@link #className()} (e.g. "Inner" for "Outer$Inner"). */
  public String simpleClassName() {
    return simpleClassName;
  }

  /**
   * True if the class is a member / anonymous / local class (i.e. has at least one '$' in {@link
   * #className()}).
   */
  public boolean isInnerClass() {
    return !enclosingClasses.isEmpty();
  }

  /** Outer-class chain from outermost to innermost; empty for top-level classes. */
  public List<String> enclosingClasses() {
    return enclosingClasses;
  }

  public String method() {
    return method;
  }

  public List<String> paramTypes() {
    return paramTypes;
  }

  public String returnType() {
    return returnType;
  }

  /**
   * Returns the fully qualified class name (package + class, with '$' preserved for inner classes).
   * E.g. {@code "com.example.Outer$Inner"}.
   */
  public String fqClassName() {
    return packageName.isEmpty() ? className : packageName + "." + className;
  }

  /**
   * Returns the canonical string form, lowercased. The canonical form is stable across runs and is
   * used as a cache key.
   */
  public String canonical() {
    return toString(/* lowerCase */ true);
  }

  @Override
  public String toString() {
    return toString(false);
  }

  private String toString(boolean lowerCase) {
    StringBuilder sb = new StringBuilder();
    if (!packageName.isEmpty()) {
      sb.append(lowerCase ? packageName.toLowerCase() : packageName);
      sb.append('.');
    }
    sb.append(lowerCase ? className.toLowerCase() : className);
    sb.append('#');
    sb.append(lowerCase ? method.toLowerCase() : method);
    sb.append('(');
    for (int i = 0; i < paramTypes.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(lowerCase ? paramTypes.get(i).toLowerCase() : paramTypes.get(i));
    }
    sb.append(')');
    if (returnType != null) {
      sb.append(':');
      sb.append(lowerCase ? returnType.toLowerCase() : returnType);
    }
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FunctionSignature other)) return false;
    return canonical().equals(other.canonical());
  }

  @Override
  public int hashCode() {
    return canonical().hashCode();
  }

  // ===== Parsers =====

  /**
   * Parse a canonical-form string. Returns null if the string is not in a recognizable format.
   *
   * <p>Supported inputs:
   *
   * <ul>
   *   <li>Canonical: {@code com.example.Outer$Inner#doIt(int):int}
   *   <li>Canonical without return type: {@code com.example.Outer$Inner#doIt(int)}
   *   <li>Canonical without params: {@code com.example.Outer$Inner#doIt}
   *   <li>JNI-style: {@code Lcom/example/Outer$Inner;.doIt(I)I} (the {@code $} is preserved
   *       verbatim)
   * </ul>
   */
  public static FunctionSignature parse(String sig) {
    if (sig == null || sig.isEmpty()) return null;
    // JNI-style: Lpkg/Class;.method(args)ReturnType
    if (sig.startsWith("L") && sig.contains("/")) {
      return parseJni(sig);
    }
    // Canonical: pkg.class#method(args):return
    return parseCanonical(sig);
  }

  /** Class name can include '$' for inner classes. */
  private static final Pattern CANONICAL =
      Pattern.compile(
          "^(?:([\\w$.]+?)\\.)?([\\w$]+(?>\\$[\\w$]+)*)#([\\w$<>]+)(?:\\(([^)]*)\\))?(?::(.*))?$");

  private static FunctionSignature parseCanonical(String sig) {
    Matcher m = CANONICAL.matcher(sig);
    if (!m.matches()) return null;
    String pkg = m.group(1);
    String cls = m.group(2);
    String method = m.group(3);
    String params = m.group(4);
    String ret = m.group(5);
    List<String> paramTypes = new ArrayList<>();
    if (params != null && !params.isEmpty()) {
      for (String p : params.split(",")) {
        String t = p.trim();
        if (!t.isEmpty()) paramTypes.add(t);
      }
    }
    return new FunctionSignature(pkg == null ? "" : pkg, cls, method, paramTypes, ret);
  }

  private static FunctionSignature parseJni(String sig) {
    // Lpkg/Class;.method(args)Return
    int l = sig.indexOf('(');
    int r = sig.indexOf(')');
    if (l < 0 || r < 0 || r < l) return null;
    String params = sig.substring(l + 1, r);
    String ret = r + 1 < sig.length() ? sig.substring(r + 1) : null;
    String head = sig.substring(1, l);
    // head is "pkg/Inner;.method" or "Class;.method"
    int semi = head.indexOf(";.");
    if (semi < 0) return null;
    String classPath = head.substring(0, semi);
    String method = head.substring(semi + 2);
    String pkg = "";
    int lastSlash = classPath.lastIndexOf('/');
    if (lastSlash >= 0) {
      pkg = classPath.substring(0, lastSlash).replace('/', '.');
      classPath = classPath.substring(lastSlash + 1);
    }
    // classPath may be "Outer$Inner" — keep as a single
    // class name (it is preserved verbatim so the enclosing-chain
    // computation in the constructor can split it on '$').
    String cls = classPath;
    // Parse JNI params: e.g. "Ljava/lang/String;I"
    List<String> paramTypes = new ArrayList<>();
    int i = 0;
    while (i < params.length()) {
      char c = params.charAt(i);
      int start = i;
      if (c == 'L') {
        int end = params.indexOf(';', i);
        if (end < 0) return null;
        String t = params.substring(start + 1, end).replace('/', '.');
        paramTypes.add(t);
        i = end + 1;
      } else if (c == '[') {
        // Array; skip all '[' then read element type
        while (i < params.length() && params.charAt(i) == '[') i++;
        if (i >= params.length()) return null;
        if (params.charAt(i) == 'L') {
          int end = params.indexOf(';', i);
          if (end < 0) return null;
          String t = params.substring(start, end + 1).replace('/', '.');
          paramTypes.add(t);
          i = end + 1;
        } else {
          String t = params.substring(start, i + 1);
          paramTypes.add(t);
          i++;
        }
      } else {
        // Primitive: B / S / I / J / F / D / Z / C
        String t = jniPrimToJava(c);
        paramTypes.add(t);
        i++;
      }
    }
    // Convert JNI return type
    if (ret != null) {
      if (ret.length() == 1) ret = jniPrimToJava(ret.charAt(0));
      else if (ret.startsWith("L")) ret = ret.substring(1, ret.length() - 1).replace('/', '.');
      else if (ret.startsWith("[")) ret = ret; // keep array descriptor
    }
    return new FunctionSignature(pkg, cls, method, paramTypes, ret);
  }

  private static String jniPrimToJava(char c) {
    switch (c) {
      case 'B':
        return "byte";
      case 'S':
        return "short";
      case 'I':
        return "int";
      case 'J':
        return "long";
      case 'F':
        return "float";
      case 'D':
        return "double";
      case 'Z':
        return "boolean";
      case 'C':
        return "char";
      case 'V':
        return "void";
      default:
        return String.valueOf(c);
    }
  }
}
