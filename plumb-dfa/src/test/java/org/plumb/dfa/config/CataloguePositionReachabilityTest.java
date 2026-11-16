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
package org.plumb.dfa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A catalogue position that no call can supply makes the rule structurally unreachable.
 *
 * <p><b>The defect this pins.</b> A sink pattern says which argument carries the tainted value:
 * {@code arg_index}. The engine compares that number with the argument list of the call it is
 * looking at, and a position past the end of that list is skipped. So a position that NO overload
 * of the named API can supply is a rule that can never fire. Nothing says so: the family is in the
 * catalogue, the capability report counts it, and the finding never appears.
 *
 * <p><b>Why this is not the same defect as {@code SinkFamilyReachabilityTest}.</b> That test asks
 * whether a family has a WATCHER. This one asks whether the family's POSITION is reachable. A
 * watched family with an unsuppliable position reports nothing, and an unwatched family with a
 * perfect position also reports nothing; the two gates are independent.
 *
 * <p><b>What the check measures.</b> The catalogue's pattern is a regex the engine matches with
 * {@code Matcher.find()}, not {@code matches()} -- so a pattern names a PREFIX of the callee
 * signature, and {@code ^java\.util\.logging\.Logger\.warn} reaches {@code
 * java.util.logging.Logger.warning/1}. The check therefore asks the same question the engine asks:
 * is there an overload of the API, visible on the named type, whose argument count is past the
 * position? An interface default method does not count, because whether it exists at all depends on
 * the JDK the build runs on, and a gate whose verdict moves with the JDK is not a gate.
 *
 * <p><b>What it deliberately does not judge.</b> A family with no {@code cwe} is a taint SOURCE,
 * and the source matcher looks at the callee NAME only -- it never reads a position, so a source
 * family's position cannot be unreachable. A pattern that is not a plain {@code owner.method} (an
 * alternation, a group, a regular expression) names something the JDK's method table says nothing
 * about, and an API that is not on this JDK is skipped for the same reason. So is a LIBRARY class:
 * only a class in a named module is judged, because whether a library is on the classpath is a
 * property of the build, not of the catalogue.
 */
class CataloguePositionReachabilityTest {

  /**
   * The catalogue positions that no call can supply, with the reason each one is still there.
   *
   * <p><b>The list is empty.</b> Every position it used to name has been repaired: the ones that
   * named an argument no overload can supply were moved to the argument that carries the weakness
   * -- the receiver, the method name of a reflective bean, the host of a socket address -- and the
   * ones that named an API that does not exist were dropped. The check below is therefore
   * zero-tolerance: a position no call can supply now fails the build instead of waiting for a list
   * entry.
   *
   * <p><b>The list is the gate's escape hatch, and it stays empty unless it has to.</b> An entry is
   * a work order, not an excuse: each one says what the position SHOULD be, so the fix is a
   * decision, not a search. The staleness test below fails when an entry is fixed and left behind
   * -- a list that keeps entries it no longer needs is a list nobody reads.
   *
   * <p><b>The key is the family and the pattern text.</b> The same pattern text can sit in two
   * families with two different positions, and each is its own entry: {@code
   * java.io.ObjectInputStream.readObject} is right in {@code deserialization.sinks}, which reads
   * the receiver, and was wrong in {@code code-execution.sinks}, which read a position that no
   * argument list has.
   */
  private static final Map<String, String> KNOWN_UNREACHABLE = new LinkedHashMap<>();

  /**
   * The entries whose verdict depends on the JDK the build runs on.
   *
   * <p>An interface default method is inherited rather than declared, so whether it exists at all
   * depends on the JDK: {@code java.beans.Expression} grew and lost {@code getValue(Object)} across
   * releases, and {@code java.net.SocketAddress}'s constructor changed visibility. An entry lands
   * here when the position is supplyable on one JDK and not on another: the check then measures it
   * on the JDK in front of the build, and the staleness test re-measures it when the JDK moves.
   *
   * <p><b>The set is empty.</b> Both entries it held were repaired -- the bean's method name moved
   * to the constructor argument that carries it, and the socket address moved to the constructor
   * that has a host -- and the positions they name sit inside the arity of those constructors on
   * every JDK that has them.
   */
  private static final Set<String> JDK_DEPENDENT = Set.of();

  /** The catalogue's arity suffixes, as the catalogue writes them. */
  private static final String[] ARITY_SUFFIXES = {"(/\\d+)?", "(/\\d+)"};

  /** The characters a plain {@code owner.method} name may not contain. */
  private static final String METACHARACTERS = "()[]{}|*+?$";

  /** One position that no overload of the named API can supply. */
  private static final class Unreachable {
    final String family;
    final String pattern;
    final int position;
    final int maxArity;

    Unreachable(String family, String pattern, int position, int maxArity) {
      this.family = family;
      this.pattern = pattern;
      this.position = position;
      this.maxArity = maxArity;
    }

    String key() {
      return family + "|" + pattern;
    }

    @Override
    public String toString() {
      return key() + " (position " + position + ", the API supplies at most " + maxArity + ")";
    }
  }

  /** The API a pattern names, when the pattern names a plain one. */
  private static final class OwnerMethod {
    final String owner;
    final String method;

    /**
     * Whether the pattern spelled a word boundary after the method name.
     *
     * <p>A boundary says "this method and no other": without it, a pattern for {@code Exec} also
     * claims {@code ExecContext}, because the matcher uses {@code find()}. The check therefore has
     * to measure the method the pattern names, not the family of methods whose name starts with it.
     */
    final boolean exact;

    OwnerMethod(String owner, String method) {
      this(owner, method, false);
    }

    OwnerMethod(String owner, String method, boolean exact) {
      this.owner = owner;
      this.method = method;
      this.exact = exact;
    }

    boolean isConstructor() {
      return "<init>".equals(method);
    }
  }

  @Test
  @DisplayName("every catalogue position is one some overload can supply")
  void everyCataloguePositionIsSupplyable() {
    List<Unreachable> violations = unreachable(ConfigLoader.loadFromClasspath().sinkFamilies());
    List<String> unlisted = new ArrayList<>();
    for (Unreachable v : violations) {
      if (!KNOWN_UNREACHABLE.containsKey(v.key())) {
        unlisted.add(v.toString());
      }
    }
    assertThat(unlisted)
        .as(
            "catalogue positions no call can supply -- a rule in this state can never fire,"
                + " and nothing in the catalogue says so")
        .isEmpty();
  }

  @Test
  @DisplayName("the list of known-unreachable positions has no stale entry")
  void theKnownListHasNoStaleEntry() {
    List<Unreachable> violations = unreachable(ConfigLoader.loadFromClasspath().sinkFamilies());
    List<String> current = new ArrayList<>();
    for (Unreachable v : violations) {
      current.add(v.key());
    }
    List<String> stale = new ArrayList<>();
    for (String key : KNOWN_UNREACHABLE.keySet()) {
      // A JDK-dependent entry is measured on the JDK in front of the build; it
      // is not a stale entry when this JDK says the position IS reachable.
      if (JDK_DEPENDENT.contains(key)) {
        continue;
      }
      if (!current.contains(key)) {
        stale.add(key);
      }
    }
    assertThat(stale)
        .as(
            "these positions are no longer unreachable -- remove them from the list, or the"
                + " list stops meaning anything")
        .isEmpty();
  }

  @Test
  @DisplayName("the check reports a position no overload can supply")
  void theCheckReportsAnUnreachablePosition() {
    // The negative control. Without it, a check that reported nothing would look
    // exactly like a check that works.
    Map<String, Object> yaml = new LinkedHashMap<>();
    Map<String, Object> family = new LinkedHashMap<>();
    family.put("cwe", "CWE-89");
    family.put(
        "patterns",
        List.of(Map.of("pattern", "^java\\.sql\\.Driver\\.connect(/\\d+)?", "arg_index", 2)));
    yaml.put("families", Map.of("probe.sinks", family));

    List<Unreachable> violations = unreachable(ConfigLoader.parseSinkFamiliesForTest(yaml));
    assertThat(violations).as("the unreachable position must be reported").hasSize(1);
    assertThat(violations.get(0).family).isEqualTo("probe.sinks");
    assertThat(violations.get(0).position).isEqualTo(2);
    assertThat(violations.get(0).maxArity).isEqualTo(2);
  }

  @Test
  @DisplayName("the check accepts a position some overload can supply")
  void theCheckAcceptsASupplyablePosition() {
    // The other half of the control: a check that reported EVERY position would
    // pass the first control too.
    Map<String, Object> yaml = new LinkedHashMap<>();
    Map<String, Object> family = new LinkedHashMap<>();
    family.put("cwe", "CWE-89");
    family.put(
        "patterns",
        List.of(Map.of("pattern", "^java\\.sql\\.Driver\\.connect(/\\d+)?", "arg_index", 1)));
    yaml.put("families", Map.of("probe.sinks", family));

    assertThat(unreachable(ConfigLoader.parseSinkFamiliesForTest(yaml)))
        .as("a position the API can supply must not be reported")
        .isEmpty();
  }

  @Test
  @DisplayName("a word boundary narrows the check to the method it names")
  void aWordBoundaryNarrowsTheCheckToTheMethodItNames() {
    // java.io.PrintStream.print takes ONE argument in every overload, and
    // printf takes three. Without a boundary the check measures the whole
    // print* family, so position 1 looks supplyable. With the boundary it
    // measures print alone, and one argument cannot supply position 1.
    Map<String, Object> yaml = new LinkedHashMap<>();
    Map<String, Object> family = new LinkedHashMap<>();
    family.put("cwe", "CWE-89");
    family.put(
        "patterns",
        List.of(Map.of("pattern", "^java\\.io\\.PrintStream\\.print\\b(/\\d+)?", "arg_index", 1)));
    yaml.put("families", Map.of("probe.sinks", family));

    List<Unreachable> violations = unreachable(ConfigLoader.parseSinkFamiliesForTest(yaml));
    assertThat(violations)
        .as("the boundary names print, whose one argument cannot supply position 1")
        .hasSize(1);
    assertThat(violations.get(0).position).isEqualTo(1);
    assertThat(violations.get(0).maxArity).isEqualTo(1);

    // The other half of the control: without the boundary the same position IS
    // supplyable, because printf takes three arguments.
    Map<String, Object> wide = new LinkedHashMap<>();
    Map<String, Object> wideFamily = new LinkedHashMap<>();
    wideFamily.put("cwe", "CWE-89");
    wideFamily.put(
        "patterns",
        List.of(Map.of("pattern", "^java\\.io\\.PrintStream\\.print(/\\d+)?", "arg_index", 1)));
    wide.put("families", Map.of("probe.sinks", wideFamily));

    assertThat(unreachable(ConfigLoader.parseSinkFamiliesForTest(wide)))
        .as(
            "without the boundary the print* family is measured, and printf's three arguments"
                + " supply position 1")
        .isEmpty();
  }

  @Test
  @DisplayName("a method the type declares is measured even when it is not public")
  void aMethodTheTypeDeclaresIsMeasuredEvenWhenItIsNotPublic() {
    // java.util.Random.next(int) is protected. A subclass calls it, and the
    // analyser sees the call site, so the check has to measure it. The PUBLIC
    // method table alone has no method called "next", and the entry would have
    // looked like an API this JDK says nothing about -- and the gate would
    // have skipped it without a word.
    Map<String, Object> yaml = new LinkedHashMap<>();
    Map<String, Object> family = new LinkedHashMap<>();
    family.put("cwe", "CWE-89");
    family.put(
        "patterns",
        List.of(Map.of("pattern", "^java\\.util\\.Random\\.next\\b(/\\d+)?", "arg_index", 1)));
    yaml.put("families", Map.of("probe.sinks", family));

    List<Unreachable> violations = unreachable(ConfigLoader.parseSinkFamiliesForTest(yaml));
    assertThat(violations).as("next(int) is protected, and it takes one argument").hasSize(1);
    assertThat(violations.get(0).position).isEqualTo(1);
    assertThat(violations.get(0).maxArity).isEqualTo(1);
  }

  @Test
  @DisplayName("a source family is not judged on its position")
  void aSourceFamilyIsNotJudged() {
    // A family with no CWE is a taint SOURCE. The source matcher reads the callee
    // NAME and never reads a position, so a source family's position cannot be
    // unreachable -- judging it would report a defect that cannot happen.
    Map<String, Object> yaml = new LinkedHashMap<>();
    Map<String, Object> family = new LinkedHashMap<>();
    family.put(
        "patterns",
        List.of(Map.of("pattern", "^java\\.util\\.Scanner\\.nextLine(/\\d+)?", "arg_index", 0)));
    yaml.put("families", Map.of("probe.sources", family));

    assertThat(unreachable(ConfigLoader.parseSinkFamiliesForTest(yaml)))
        .as("a source family's position has no meaning")
        .isEmpty();
  }

  /**
   * Every position in the catalogue that no overload of the API it names can supply.
   *
   * @param registry the catalogue
   * @return the violations, in catalogue order
   */
  private static List<Unreachable> unreachable(SinkFamilyRegistry registry) {
    List<Unreachable> out = new ArrayList<>();
    for (SinkFamily family : registry.all()) {
      // A family with no CWE is a source: the matcher reads the callee name only.
      if (family.cweOrNull() == null) {
        continue;
      }
      for (SinkFamily.SinkPattern pattern : allPatterns(family)) {
        OwnerMethod api = ownerMethodOf(pattern.pattern());
        if (api == null) {
          continue;
        }
        int maxArity = maxArity(api);
        if (maxArity < 0) {
          // The API is not on this JDK, so the JDK's method table says nothing
          // about it.
          continue;
        }
        for (int index : pattern.argIndexes()) {
          int effective = index == -1 ? family.defaultArgIndex() : index;
          if (effective == SinkFamily.RECEIVER_ARG_INDEX) {
            // The receiver is not in the argument list, so an argument count
            // says nothing about it.
            continue;
          }
          if (effective == SinkFamily.LAST_ARG_INDEX) {
            if (maxArity < 1) {
              out.add(new Unreachable(family.name(), pattern.pattern(), effective, maxArity));
            }
            continue;
          }
          if (effective >= maxArity) {
            out.add(new Unreachable(family.name(), pattern.pattern(), effective, maxArity));
          }
        }
      }
    }
    return out;
  }

  /**
   * Every pattern of a family, including the ones its per-language tables add.
   *
   * <p>The registry hands out a family's patterns per language, so a family whose per-language
   * table holds an entry the default table does not is only visible when every language is asked.
   */
  private static List<SinkFamily.SinkPattern> allPatterns(SinkFamily family) {
    Map<String, SinkFamily.SinkPattern> byKey = new LinkedHashMap<>();
    for (SinkFamily.SinkPattern p : family.patternsFor(null)) {
      byKey.putIfAbsent(keyOf(p), p);
    }
    for (SourceLanguage language : SourceLanguage.values()) {
      for (SinkFamily.SinkPattern p : family.patternsFor(language)) {
        byKey.putIfAbsent(keyOf(p), p);
      }
    }
    return new ArrayList<>(byKey.values());
  }

  private static String keyOf(SinkFamily.SinkPattern pattern) {
    return pattern.pattern() + "|" + Arrays.toString(pattern.argIndexes());
  }

  /**
   * The API a pattern names, when the pattern names a plain one.
   *
   * @param raw the pattern text
   * @return the API, or null when the pattern names something else
   */
  private static OwnerMethod ownerMethodOf(String raw) {
    if (raw == null) {
      return null;
    }
    String body = raw;
    for (String suffix : ARITY_SUFFIXES) {
      if (body.endsWith(suffix)) {
        body = body.substring(0, body.length() - suffix.length());
        break;
      }
    }
    if (!body.startsWith("^")) {
      return null;
    }
    body = body.substring(1);
    StringBuilder plain = new StringBuilder();
    // Where the word boundary sat, if the pattern spelled one. A boundary is
    // not part of the name, so it is not appended -- but it tells the check
    // that the pattern names ONE method.
    int boundaryAt = -1;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\\') {
        if (i + 1 >= body.length()) {
          return null;
        }
        char next = body.charAt(++i);
        if (next == 'b') {
          boundaryAt = plain.length();
          continue;
        }
        if (METACHARACTERS.indexOf(next) >= 0) {
          return null;
        }
        plain.append(next);
        continue;
      }
      if (METACHARACTERS.indexOf(c) >= 0) {
        return null;
      }
      plain.append(c);
    }
    String text = plain.toString();
    int dot = text.lastIndexOf('.');
    if (dot <= 0 || dot == text.length() - 1) {
      return null;
    }
    String owner = text.substring(0, dot);
    String method = text.substring(dot + 1);
    if (!owner.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
      return null;
    }
    if (!"<init>".equals(method) && !method.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
      return null;
    }
    // The boundary has to sit at the end of the method name: a boundary
    // anywhere else says nothing about which method the pattern names.
    boolean exact = boundaryAt == text.length();
    return new OwnerMethod(owner, method, exact);
  }

  /**
   * The largest argument count any overload of the API can take.
   *
   * @param api the API
   * @return the count, or -1 when this JDK says nothing about the API
   */
  private static int maxArity(OwnerMethod api) {
    Class<?> type;
    try {
      type = Class.forName(api.owner);
    } catch (Throwable t) {
      return -1;
    }
    // Only a class in a NAMED module is judged. A JDK class lives in one
    // (java.base, java.sql, java.desktop, ...), so its method table is the
    // JDK's and the same on every machine that builds this project. A class
    // on the classpath lives in the unnamed module, and whether it is there
    // at all depends on what the build happened to put on the classpath --
    // a gate whose verdict depends on that is not a gate.
    if (!type.getModule().isNamed()) {
      return -1;
    }
    int max = -1;
    if (api.isConstructor()) {
      for (Constructor<?> constructor : type.getConstructors()) {
        max = Math.max(max, constructor.getParameterCount());
      }
      return max;
    }
    // The PUBLIC methods, and the ones the type DECLARES whatever their
    // visibility. A protected method is still called -- by a subclass, or by
    // a class in the same package -- and the analyser sees the call site, not
    // the caller's access rights. Leaving the non-public ones out would hide
    // an API whose name a pattern names exactly: java.util.Random.next(int)
    // is protected, and the public table alone has no method called "next",
    // so the entry would have looked like an API this JDK says nothing about
    // and the gate would have skipped it.
    Set<Method> candidates = new LinkedHashSet<>();
    candidates.addAll(Arrays.asList(type.getMethods()));
    candidates.addAll(Arrays.asList(type.getDeclaredMethods()));
    for (Method method : candidates) {
      // An interface default method is inherited, not declared: whether it exists
      // depends on the JDK the build runs on, so a gate that counted it would
      // change its verdict with the JDK.
      if (method.isDefault()) {
        continue;
      }
      // A synthetic method is a bridge the compiler made, not an API anybody
      // calls by that name.
      if (method.isSynthetic()) {
        continue;
      }
      // The matcher uses find(), so a pattern reaches every method whose name
      // starts with the name it names -- unless the pattern spelled a word
      // boundary, which says it names ONE method.
      if (api.exact) {
        if (!method.getName().equals(api.method)) {
          continue;
        }
      } else if (!method.getName().startsWith(api.method)) {
        continue;
      }
      max = Math.max(max, method.getParameterCount());
    }
    return max;
  }
}
