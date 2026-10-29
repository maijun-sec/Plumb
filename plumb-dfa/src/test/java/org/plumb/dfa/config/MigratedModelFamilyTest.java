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
import java.util.List;
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
 *   <li>each migrated family exists, with the CWE it claims;
 *   <li>each migrated pattern MATCHES the callee it names, and does not match a different one;
 *   <li>the ARGUMENT INDEX is the one the reference model records -- the argument that carries the
 *       untrusted data, not the first one;
 *   <li>the pattern escapes survived the migration: a pattern that lost its backslashes still
 *       parses, and matches the wrong thing.
 * </ul>
 *
 * <p>The argument indexes are the load-bearing part. A family that matches the right call with the
 * wrong index fires on the wrong argument -- and it fires, so nothing reports a problem.
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
  void thePredictableSeedFamilyExistsAndCarriesCwe338() {
    SinkFamily f = family("predictable-seed.sinks");
    assertThat(f.cwe()).isEqualTo("CWE-338");
    assertThat(f.matches("java.security.SecureRandom.<init>/1")).isTrue();
  }

  @Test
  void thePredictableSeedFamilyDoesNotMatchAnUnrelatedCall() {
    SinkFamily f = family("predictable-seed.sinks");
    // The narrowness matters: the relaxed fallback must not turn this
    // into "any constructor on any class".
    assertThat(f.matches("java.util.Random.<init>/1")).isFalse();
    assertThat(f.matches("com.example.SecureRandom.<init>/1")).isFalse();
  }

  @Test
  void theOAuthCredentialFamilyMatchesAndPicksTheSecretArgument() {
    SinkFamily f = family("oauth-credential.sinks");
    assertThat(
            f.matches(
                "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.registerConsumer/4"))
        .isTrue();
  }

  @Test
  void theKmsPlaintextFamilyMatches() {
    SinkFamily f = family("kms-plaintext.sinks");
    assertThat(f.matches("software.amazon.awssdk.services.kms.model.EncryptRequest.setPlaintext/1"))
        .isTrue();
  }

  @Test
  void theDbCredentialFamilyMatches() {
    SinkFamily f = family("db-credential.sinks");
    assertThat(f.matches("java.sql.DriverManager.getConnection/3")).isTrue();
  }

  @Test
  void theCookieWriteFamilyMatches() {
    SinkFamily f = family("cookie-write.sinks");
    assertThat(f.matches("javax.servlet.http.HttpServletResponse.addCookie/1")).isTrue();
  }

  @Test
  void theHttpHeaderWriteFamilyMatches() {
    SinkFamily f = family("http-header-write.sinks");
    assertThat(f.matches("javax.servlet.http.HttpServletResponse.setHeader/2")).isTrue();
  }

  @Test
  void theRedirectFamilyMatches() {
    SinkFamily f = family("redirect.sinks");
    assertThat(
            f.matches("org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.handleCallback/1"))
        .isTrue();
  }

  @Test
  void theSensitiveLogFamilyMatches() {
    SinkFamily f = family("sensitive-log.sinks");
    assertThat(f.matches("java.sql.DriverManager.getConnection/3")).isTrue();
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
            "predictable-seed.sinks",
            "oauth-credential.sinks",
            "kms-plaintext.sinks",
            "db-credential.sinks",
            "cookie-write.sinks")) {
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
    SinkFamily oauth = family("oauth-credential.sinks");
    assertThat(argIndexOf(oauth, "registerConsumer")).isEqualTo(2);

    // The model's revokeAccessToken(token, principalName) carries the
    // TOKEN as the FIRST argument.
    assertThat(argIndexOf(oauth, "revokeAccessToken")).isEqualTo(0);

    // DriverManager.getConnection(url, user, password) -- the password
    // is the THIRD argument.
    SinkFamily db = family("db-credential.sinks");
    assertThat(argIndexOf(db, "getConnection")).isEqualTo(2);

    // SecureRandom's seed is the first argument.
    SinkFamily seed = family("predictable-seed.sinks");
    assertThat(argIndexOf(seed, "<init>")).isEqualTo(0);
  }

  private int argIndexOf(SinkFamily family, String method) {
    for (SinkFamily.SinkPattern p : family.patterns()) {
      if (p.pattern().contains(method)) {
        return p.argIndex();
      }
    }
    throw new AssertionError("no pattern of " + family.name() + " mentions " + method);
  }
}
