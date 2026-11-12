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
 * about, and an API that is not on this JDK is skipped for the same reason.
 */
class CataloguePositionReachabilityTest {

  /**
   * The catalogue positions that no call can supply, with the reason each one is still there.
   *
   * <p><b>Every entry here was MEASURED, and every entry is a defect that is still open.</b> The
   * list is a work order, not an excuse: each entry says what the position SHOULD be, so the fix is
   * a decision, not a search. The staleness test below fails when an entry is fixed and left behind
   * -- a list that keeps entries it no longer needs is a list nobody reads.
   *
   * <p><b>The key is the family and the pattern text.</b> The same pattern text can sit in two
   * families with two different positions, and each is its own entry: {@code
   * java.io.ObjectInputStream.readObject} is right in {@code deserialization.sinks}, which reads
   * the receiver, and wrong in {@code code-execution.sinks}, which reads a position that no
   * argument list has.
   */
  private static final Map<String, String> KNOWN_UNREACHABLE =
      new LinkedHashMap<>() {
        {
          put(
              "ssl.sinks|^org\\.apache\\.http\\.ssl\\.SSLContexts\\.custom(/\\d+)?",
              "custom() takes no argument: the material reaches loadTrustMaterial and"
                  + " loadKeyMaterial, so the position has to move to those calls.");
          put(
              "random.sinks|^java\\.util\\.Random\\.nextLong(/\\d+)?",
              "nextLong() takes no argument: what is weak is the generator, so the position"
                  + " has to be the receiver.");
          put(
              "random.sinks|^java\\.util\\.Random\\.nextDouble(/\\d+)?",
              "nextDouble() takes no argument: what is weak is the generator, so the position"
                  + " has to be the receiver.");
          put(
              "random.sinks|^java\\.util\\.Random\\.nextFloat(/\\d+)?",
              "nextFloat() takes no argument: what is weak is the generator, so the position"
                  + " has to be the receiver.");
          put(
              "random.sinks|^java\\.util\\.Random\\.nextBoolean(/\\d+)?",
              "nextBoolean() takes no argument: what is weak is the generator, so the position"
                  + " has to be the receiver.");
          put(
              "random.sinks|^java\\.lang\\.Math\\.random(/\\d+)?",
              "Math.random() takes no argument and is static: there is no receiver to read"
                  + " either, so the entry has to be re-modelled or dropped.");
          put(
              "code-execution.sinks|^java\\.beans\\.Expression\\.getValue(/\\d+)?",
              "getValue() takes no argument: the method name the caller asked for came from the"
                  + " constructor's argument, so the position has to move there.");
          put(
              "code-execution.sinks|^java\\.beans\\.Statement\\.execute(/\\d+)?",
              "execute() takes no argument: the method name the caller asked for came from the"
                  + " constructor's argument, so the position has to move there.");
          put(
              "code-execution.sinks|^java\\.io\\.ObjectInputStream\\.readObject(/\\d+)?",
              "readObject() takes no argument: deserialization.sinks already reads the receiver"
                  + " for the same API, so this copy has to say the same.");
          put(
              "credential.sinks|^java\\.net\\.URL\\.set(/\\d+)?",
              "URL.set is not a public method: the pattern reaches setURLStreamHandlerFactory by"
                  + " prefix, and position 4 is past every overload that name has.");
          put(
              "credential.sinks|^java\\.sql\\.Driver\\.connect(/\\d+)?",
              "connect(String url, Properties info) takes two arguments: the password is a"
                  + " property of the second one, not a position.");
          put(
              "file-permission.sinks|^java\\.io\\.File\\.setReadOnly(/\\d+)?",
              "setReadOnly() takes no argument: the path is in the receiver.");
          put(
              "reflection.sinks|^java\\.lang\\.reflect\\.Array\\.newInstance(/\\d+)?",
              "newInstance(Class componentType, int length) takes two arguments: position 5"
                  + " names nothing.");
          put(
              "sql.sinks|^java\\.sql\\.Statement\\.executeBatch(/\\d+)?",
              "executeBatch() takes no argument: the text was already claimed where the"
                  + " statement was given it.");
          put(
              "sql.sinks|^java\\.sql\\.Statement\\.clearBatch(/\\d+)?",
              "clearBatch() takes no argument: the text was already claimed where the statement"
                  + " was given it.");
          put(
              "sql.sinks|^java\\.sql\\.PreparedStatement\\.executeBatch(/\\d+)?",
              "executeBatch() takes no argument: the text was already claimed where the"
                  + " statement was prepared.");
          put(
              "ssrf.sinks|^java\\.net\\.SocketAddress\\.\\<init\\>(/\\d+)?",
              "the SocketAddress constructor takes no argument and the class is abstract: the"
                  + " host is in InetSocketAddress's constructor.");
        }
      };

  /**
   * The entries whose verdict depends on the JDK the build runs on.
   *
   * <p>An interface default method is inherited rather than declared, so whether it exists at all
   * depends on the JDK: {@code java.beans.Expression} grew and lost {@code getValue(Object)} across
   * releases, and {@code java.net.SocketAddress}'s constructor changed visibility. These two are
   * measured on the JDK in front of the build, and the list is re-measured when the JDK moves --
   * which is what the staleness test is for.
   */
  private static final Set<String> JDK_DEPENDENT =
      Set.of(
          "code-execution.sinks|^java\\.beans\\.Expression\\.getValue(/\\d+)?",
          "ssrf.sinks|^java\\.net\\.SocketAddress\\.\\<init\\>(/\\d+)?");

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

    OwnerMethod(String owner, String method) {
      this.owner = owner;
      this.method = method;
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
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\\') {
        if (i + 1 >= body.length()) {
          return null;
        }
        char next = body.charAt(++i);
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
    return new OwnerMethod(owner, method);
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
    int max = -1;
    if (api.isConstructor()) {
      for (Constructor<?> constructor : type.getConstructors()) {
        max = Math.max(max, constructor.getParameterCount());
      }
      return max;
    }
    for (Method method : type.getMethods()) {
      // An interface default method is inherited, not declared: whether it exists
      // depends on the JDK the build runs on, so a gate that counted it would
      // change its verdict with the JDK.
      if (method.isDefault()) {
        continue;
      }
      // The matcher uses find(), so a pattern reaches every method whose name
      // starts with the name it names.
      if (!method.getName().startsWith(api.method)) {
        continue;
      }
      max = Math.max(max, method.getParameterCount());
    }
    return max;
  }
}
