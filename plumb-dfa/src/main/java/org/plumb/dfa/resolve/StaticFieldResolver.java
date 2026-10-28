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
package org.plumb.dfa.resolve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-file static field value resolver.
 *
 * <p>Walks every file registered in a {@link SourcePathResolver} and extracts the value of every
 * {@code public static final} field whose initializer is a literal. The result is a map from {@code
 * FQCN.fieldName} to a {@link LiteralValue} -- the kind + numeric / string payload of the literal.
 *
 * <p>The resolver does not know what project it is pointed at. For Juliet this means that {@code
 * testcasesupport.IO.STATIC_FIVE} is resolved to the {@code int 5} declared in {@code
 * testcasesupport/IO.java} -- the resolution is <i>driven by the project source</i>, not by a
 * hardcoded mapping. Any other project that uses the same source-path pattern (a project root with
 * {@code import} statements pointing at sibling files) gets the same benefit at zero configuration
 * cost.
 */
public final class StaticFieldResolver {

  private final SourcePathResolver sources;

  /** FQCN.field → literal value. */
  private final Map<String, LiteralValue> values = new LinkedHashMap<>();

  /** FQCN → source file. */
  private final Map<String, Path> sourcesFor = new LinkedHashMap<>();

  /**
   * Match a {@code public static final} field declaration. The body of the field is captured
   * greedily up to the next semicolon on the same line; the {@code ^...$} anchors with {@link
   * Pattern#DOTALL} ensure we never cross a newline. Initializer can be a string literal, a number,
   * a boolean, a cast expression like {@code (int) 5}, or nothing (parsed later).
   */
  private static final Pattern FIELD_PATTERN =
      Pattern.compile(
          "(?m)^\\s*public\\s+static\\s+final\\s+"
              + "([\\w$.<>\\[\\]]+)\\s+([\\w$]+)\\s*"
              + "=\\s*(.+?);\\s*$");

  /**
   * Strict string-literal pattern: opening quote, any number of (escaped or non-quote) characters,
   * closing quote, nothing else. Used to reject {@code "x" + "y"} as a string literal (the
   * surrounding quotes do belong to the operands, but the concatenation is not a literal).
   */
  private static final Pattern STRICT_STRING_LITERAL =
      Pattern.compile("^\"(?:[^\"\\\\]|\\\\.)*\"$");

  public StaticFieldResolver(SourcePathResolver sources) {
    this.sources = sources;
  }

  /** Walk every indexed class and parse out the {@code public static final} initialisers. */
  public void index() throws IOException {
    if (sources == null) return;
    for (var e : allClasses()) {
      String fqcn = e.getKey();
      Path file = e.getValue();
      indexOne(fqcn, file);
    }
  }

  private Iterable<Map.Entry<String, Path>> allClasses() {
    java.util.List<Map.Entry<String, Path>> out = new java.util.ArrayList<>();
    try (var stream = Files.walk(sources.projectRoot())) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".java"))
          .forEach(p -> collectInto(out, p));
    } catch (IOException ignore) {
      // best effort — partial index is fine
    }
    return out;
  }

  private void collectInto(java.util.List<Map.Entry<String, Path>> out, Path p) {
    String content;
    try {
      content = Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    String pkg = readPackage(content);
    if (pkg == null) return;
    for (String cn : readClassNames(content)) {
      out.add(new java.util.AbstractMap.SimpleEntry<>(pkg + "." + cn, p));
    }
  }

  private static String readPackage(String src) {
    Matcher m = Pattern.compile("\\bpackage\\s+([\\w.$]+)\\s*;").matcher(src);
    return m.find() ? m.group(1) : null;
  }

  private static java.util.Set<String> readClassNames(String src) {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    Matcher m =
        Pattern.compile("\\b(?:public\\s+)?(?:class|interface|enum)\\s+([\\w$]+)").matcher(src);
    while (m.find()) out.add(m.group(1));
    return out;
  }

  private void indexOne(String fqcn, Path file) {
    sourcesFor.put(fqcn, file);
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    Matcher m = FIELD_PATTERN.matcher(content);
    while (m.find()) {
      String type = m.group(1);
      String name = m.group(2);
      String init = m.group(3).trim();
      LiteralValue lv = parseLiteral(type, init);
      if (lv != null) {
        values.put(fqcn + "." + name, lv);
      }
    }
  }

  /**
   * Parse the right-hand side of a {@code public static final} field. Returns {@code null} for
   * anything that is not a constant expression the engine can fold at compile time (string
   * concatenation, method calls, casts of non-literals, qualified references like {@code
   * Integer.MAX_VALUE}, …).
   */
  private static LiteralValue parseLiteral(String type, String init) {
    init = init.trim();

    // Strict string literal:
    // must be a complete
    // "..." with no
    // concatenation or
    // interpolation. This
    // rejects `"x" + "y"`
    // because the body
    // contains a `+` and is
    // therefore not a single
    // literal token.
    if (STRICT_STRING_LITERAL.matcher(init).matches()) {
      return new LiteralValue(
          LiteralValue.Kind.STRING, unescapeString(init.substring(1, init.length() - 1)));
    }

    // Strip a single leading
    // sign so negative-number
    // literals survive
    // arithmetic-operator
    // filtering below. `+42`
    // and `-42` are valid
    // numeric literals; `+`
    // and `-` in any other
    // position mean
    // arithmetic.
    boolean negative = false;
    if (init.startsWith("-") || init.startsWith("+")) {
      if (init.startsWith("-")) negative = true;
      init = init.substring(1);
    }

    // Reject any expression
    // that contains an
    // arithmetic operator
    // mid-token. This catches
    // `a + b`, `a * b`,
    // `a - b`, and the
    // already-filtered
    // string concat
    // `"x" + "y"`. The `+`
    // and `-` checks happen
    // <i>after</i> the sign
    // strip so a literal like
    // `-42` is still
    // accepted.
    if (init.contains("+") || init.contains("-") || init.contains("*") || init.contains("/")) {
      return null;
    }

    // Reject any qualified
    // reference: `Integer.MAX_VALUE`,
    // `Color.RED`, `Enum.A`,
    // … . Numeric literals
    // like `3.14` are
    // detected by trying
    // parseDouble below, so
    // the qualified-reference
    // rejection is safe to do
    // <i>after</i> the
    // numeric parse.
    boolean isFloating =
        type.startsWith("double")
            || type.startsWith("Double")
            || type.startsWith("float")
            || type.startsWith("Float");
    // Strip the optional
    // numeric suffix (L, l,
    // d, D, f, F) so that
    // `1000L`, `3.14d`, `2.0f`
    // are recognised as plain
    // numbers.
    String numericBody = init;
    if (!numericBody.isEmpty()) {
      char last = numericBody.charAt(numericBody.length() - 1);
      if (last == 'L' || last == 'l' || last == 'D' || last == 'd' || last == 'F' || last == 'f') {
        numericBody = numericBody.substring(0, numericBody.length() - 1);
      }
    }
    // A dot in the body
    // could be a double
    // (`3.14`) or a qualified
    // reference
    // (`Integer.MAX_VALUE`).
    // Decide by trying
    // parseDouble first.
    if (numericBody.contains(".")) {
      if (isFloating || looksLikeDecimal(numericBody)) {
        try {
          double d = Double.parseDouble(negative ? "-" + numericBody : numericBody);
          return new LiteralValue(LiteralValue.Kind.DOUBLE, d);
        } catch (NumberFormatException ignore) {
          // not a number — fall through
        }
      }
      // qualified ref
      return null;
    }

    // Boolean literal.
    if (numericBody.equals("true") || numericBody.equals("false")) {
      return new LiteralValue(
          LiteralValue.Kind.BOOLEAN,
          Boolean.parseBoolean(negative ? "-" + numericBody : numericBody));
    }

    // Integer / long literal.
    try {
      long v = Long.parseLong(negative ? "-" + numericBody : numericBody);
      if (isFloating) {
        // `int x = 5.0` is a
        // type error in Java
        // but the test data
        // sometimes has it;
        // report as DOUBLE for
        // safety.
        return new LiteralValue(LiteralValue.Kind.DOUBLE, (double) v);
      }
      if (type.startsWith("long") || type.startsWith("Long")) {
        return new LiteralValue(LiteralValue.Kind.LONG, v);
      }
      return new LiteralValue(LiteralValue.Kind.INT, v);
    } catch (NumberFormatException ignore) {
      return null;
    }
  }

  /**
   * Quick check for "looks like a decimal number": starts with an optional digit, has a dot, ends
   * with a digit. Used to distinguish {@code 3.14} (decimal) from {@code Integer.MAX_VALUE}
   * (qualified ref) when we haven't yet attempted the parse.
   */
  private static boolean looksLikeDecimal(String s) {
    if (s.isEmpty()) return false;
    int dot = s.indexOf('.');
    if (dot <= 0 || dot != s.lastIndexOf('.')) return false;
    // first half and second
    // half must be all digits
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '.') continue;
      if (!Character.isDigit(c)) return false;
    }
    return true;
  }

  /**
   * Translate a Java string literal (with {@code \"}, {@code \\}, {@code \n}, …) to the unescaped
   * value. Only the common escape sequences are handled; unknown ones are passed through verbatim.
   */
  private static String unescapeString(String raw) {
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '\\' && i + 1 < raw.length()) {
        char n = raw.charAt(i + 1);
        switch (n) {
          case 'n':
            sb.append('\n');
            i++;
            break;
          case 't':
            sb.append('\t');
            i++;
            break;
          case 'r':
            sb.append('\r');
            i++;
            break;
          case '"':
            sb.append('"');
            i++;
            break;
          case '\\':
            sb.append('\\');
            i++;
            break;
          default:
            sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Look up {@code fqcn.field} and return its literal value, or {@code null} if the field is not a
   * known constant.
   */
  public LiteralValue lookup(String fqcn, String field) {
    if (fqcn == null || field == null) return null;
    LiteralValue v = values.get(fqcn + "." + field);
    if (v != null) return v;
    // tolerate short class
    // names. The DFA engine passes
    // `recv` straight from the IR
    // (which strips the package for
    // class-ref fields like
    // {@code IO.STATIC_FINAL_FIVE}),
    // but the indexer keys on
    // `package.Foo`. Fall back to
    // scanning every
    // {@code *.field} key whose
    // class segment ends with the
    // given short name — O(n) but
    // n is small (the static-field
    // index is built from a
    // narrow resolver scope, ~100
    // classes max).
    if (fqcn.indexOf('.') < 0) {
      String suffix = "." + fqcn + "." + field;
      for (var e : values.entrySet()) {
        if (e.getKey().endsWith(suffix)) {
          return e.getValue();
        }
      }
    }
    return null;
  }

  public int size() {
    return values.size();
  }

  /** A primitive / String constant extracted from a source file. */
  public static final class LiteralValue {
    public enum Kind {
      INT,
      LONG,
      DOUBLE,
      BOOLEAN,
      STRING
    }

    public final Kind kind;
    public final Object value;

    public LiteralValue(Kind kind, Object value) {
      this.kind = kind;
      this.value = value;
    }

    @Override
    public String toString() {
      return kind + ":" + value;
    }
  }
}
