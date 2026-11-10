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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The families migrated from the reference library models.
 *
 * <p><b>Why this needs its own test.</b> A family that is added to the YAML and never checked is a
 * family that loads, matches nothing, and reads like coverage. The example corpus proves the DSL's
 * matcher language; nothing proved that these families are REACHABLE.
 *
 * <p><b>What is pinned.</b>
 *
 * <ul>
 *   <li>every migrated pattern is REACHABLE -- the family that owns it today matches the callee it
 *       names, and does not match a different one;
 *   <li>the ARGUMENT INDEX is the one the reference model records -- the argument that carries the
 *       untrusted data, not the first one;
 *   <li>the pattern escapes survived the migration: a pattern that lost its backslashes still
 *       parses, and matches the wrong thing;
 *   <li>the callees whose defect class does NOT hold are claimed by no family at all.
 * </ul>
 *
 * <p>The argument indexes are the load-bearing part. A family that matches the right call with the
 * wrong index fires on the wrong argument -- and it fires, so nothing reports a problem.
 *
 * <p><b>The families were reorganised; the contract moved with the content.</b> The migrated
 * families no longer keep their own names. The ones that describe a defect class the catalogue
 * already owns now live in the family that owns that class, and the ones whose defect class does
 * not hold were retired. A test that still asked for the old names would only prove that the
 * catalogue had not changed, so every assertion below names the family that owns the content TODAY
 * -- and the argument index the model recorded at the time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigratedModelFamilyTest {

  private static final Path RESOURCES = Paths.get("src/main/resources");

  private SinkFamilyRegistry registry;

  @BeforeAll
  void load() {
    registry = ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  private SinkFamily family(String name) {
    SinkFamily f = registry.byName(name).orElse(null);
    assertThat(f).as("the family %s", name).isNotNull();
    return f;
  }

  // ---------------------------------------------------------------- the sinks

  @Test
  void theSeedPatternsLiveInTheInsecureRandomFamily() {
    SinkFamily f = family("insecure-random.sinks");
    assertThat(f.cwe()).contains("CWE-338");
    assertThat(f.matches("java.security.SecureRandom.<init>/1")).isTrue();
    assertThat(f.matches("java.security.SecureRandom.setSeed/1")).isTrue();
  }

  @Test
  void theSeedFamilyDoesNotMatchAnUnrelatedCall() {
    SinkFamily f = family("insecure-random.sinks");
    // The narrowness matters: the relaxed fallback must not turn this
    // into "any constructor on any class".
    assertThat(f.matches("com.example.SecureRandom.<init>/1")).isFalse();
    // java.util.Random IS the insecure generator, so this family claims it.
    assertThat(f.matches("java.util.Random.<init>/1")).isTrue();
  }

  @Test
  void theOAuthCredentialPatternsLiveInTheCredentialFamily() {
    SinkFamily f = family("credential.sinks");
    assertThat(
            f.matches(
                "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.registerConsumer/4"))
        .isTrue();
    assertThat(
            f.matches(
                "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.revokeAccessToken/2"))
        .isTrue();
  }

  @Test
  void theDbCredentialPatternLivesInTheCredentialFamily() {
    SinkFamily f = family("credential.sinks");
    assertThat(f.matches("java.sql.DriverManager.getConnection/3")).isTrue();
  }

  @Test
  void theCookieWritePatternsLiveInTheCookieFamily() {
    SinkFamily f = family("cookie.sinks");
    assertThat(f.matches("javax.servlet.http.HttpServletResponse.addCookie/1")).isTrue();
    assertThat(f.matches("play.mvc.Http.response.setCookie/2")).isTrue();
  }

  @Test
  void theCookieWriteFiresOnTheCookieItIsGiven() {
    // The reference model recorded the SECOND argument of addCookie. addCookie takes ONE
    // argument -- the Cookie -- so that index pointed at a parameter that is not there, and
    // the pattern could never fire on anything. The catalogue points it at the Cookie.
    SinkFamily f = family("cookie.sinks");
    assertThat(argIndexesOf(f, "HttpServletResponse", "addCookie")).containsExactly(0);
  }

  @Test
  void theHeaderWritePatternsLiveInTheHeaderFamily() {
    SinkFamily f = family("header.sinks");
    assertThat(f.matches("javax.servlet.http.HttpServletResponse.setHeader/2")).isTrue();
    assertThat(f.matches("play.mvc.Http.response.setHeader/2")).isTrue();
  }

  @Test
  void theRedirectPatternsLiveInTheOpenRedirectFamily() {
    SinkFamily f = family("open-redirect.sinks");
    assertThat(
            f.matches("org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.handleCallback/1"))
        .isTrue();
  }

  @Test
  void theKmsEncryptDirectionIsNotASink() {
    // Handing plaintext to a key management service IS the encrypted path: the service
    // encrypts it. A rule that fired here would report the fix as the bug, so no family
    // claims that callee.
    for (SinkFamily f : registry.all()) {
      assertThat(
              f.matches("software.amazon.awssdk.services.kms.model.EncryptRequest.setPlaintext/1"))
          .as("the family %s", f.name())
          .isFalse();
    }
  }

  // ---------------------------------------------------------------- the sources

  @Test
  void theIpAddressSourceFamilyExists() {
    SinkFamily f = family("ip-address.sources");
    assertThat(f.matches("java.net.URL.getHost/0")).isTrue();
  }

  @Test
  void theDnsSourceFamilyExists() {
    SinkFamily f = family("dns.sources");
    assertThat(f.matches("java.net.InetAddress.getByName/1")).isTrue();
  }

  @Test
  void thePrincipalSourceFamilyExists() {
    SinkFamily f = family("principal.sources");
    assertThat(f.matches("java.security.Principal.getName/0")).isTrue();
  }

  @Test
  void theRpcSourceFamilyExists() {
    SinkFamily f = family("rpc.sources");
    assertThat(f.matches("org.apache.dubbo.rpc.RpcContext.getAttachment/1")).isTrue();
  }

  // ---------------------------------------------------------------- the escapes

  @Test
  void theMigratedPatternsKeptTheirEscapes() {
    // A pattern that lost its backslashes would match
    // "javaXutilXloggingXLogger" -- and it would MATCH NOTHING, which
    // looks exactly like a family that works.
    for (String name :
        List.of(
            "insecure-random.sinks",
            "credential.sinks",
            "cookie.sinks",
            "header.sinks",
            "open-redirect.sinks")) {
      for (SinkFamily.SinkPattern p : family(name).patterns()) {
        assertThat(p.pattern())
            .as("the pattern of %s", name)
            .doesNotContain("java.util")
            .doesNotContain("org.glassfish")
            .doesNotContain("software.amazon")
            .doesNotContain("javax.servlet");
      }
    }
  }

  // ---------------------------------------------------------------- the argument indexes

  @Test
  void theArgumentIndexesAreTheOnesTheModelRecords() {
    // The model's registerConsumer(owner, key, secret, attributes)
    // carries the SECRET as the THIRD argument, so the index is 2.
    SinkFamily credential = family("credential.sinks");
    assertThat(argIndexesOf(credential, "DefaultOAuth1Provider", "registerConsumer")).contains(2);

    // The model's revokeAccessToken(token, principalName) carries the
    // TOKEN as the FIRST argument.
    assertThat(argIndexesOf(credential, "DefaultOAuth1Provider", "revokeAccessToken")).contains(0);

    // DriverManager.getConnection(url, user, password) -- the password
    // is the THIRD argument. The catalogue also watches the URL, because a
    // connection URL can carry a credential of its own.
    assertThat(argIndexesOf(credential, "DriverManager", "getConnection")).contains(2);

    // SecureRandom's seed is the first argument. The pattern writes the
    // constructor the way the catalogue spells it: escaped.
    SinkFamily seed = family("insecure-random.sinks");
    assertThat(argIndexesOf(seed, "SecureRandom", "\\<init\\>")).contains(0);
  }

  private Set<Integer> argIndexesOf(SinkFamily family, String... parts) {
    Set<Integer> indexes = new LinkedHashSet<>();
    for (SinkFamily.SinkPattern p : family.patterns()) {
      boolean mentions = true;
      for (String part : parts) {
        if (!p.pattern().contains(part)) {
          mentions = false;
          break;
        }
      }
      if (mentions) {
        indexes.add(p.argIndex());
      }
    }
    assertThat(indexes)
        .as("the argument indexes of %s in %s", String.join(" + ", parts), family.name())
        .isNotEmpty();
    return indexes;
  }
}
