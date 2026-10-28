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

import org.junit.jupiter.api.Test;

/**
 * ConfigLoader unit tests.
 *
 * <p>Verifies that the 5 default YAML files load correctly into the {@link EngineConfig} value
 * objects, and that the loaded data has the expected shape (families match patterns, fact kinds
 * have traits, checkers are enabled, etc.).
 */
class ConfigLoaderTest {

  @Test
  void loadFromClasspath_returnsNonEmptyConfig() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    assertThat(cfg).isNotNull();
    assertThat(cfg.tuning()).isNotNull();
    assertThat(cfg.sinkFamilies().all()).isNotEmpty();
    assertThat(cfg.factKinds().all()).isNotEmpty();
    assertThat(cfg.checkers().all()).isNotEmpty();
  }

  @Test
  void sinkFamilies_loadSqlLdapCommandEtc() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    // SQL: java.sql.Statement.execute/1
    var sql = cfg.sinkFamilies().lookup("java.sql.Statement.execute/1");
    assertThat(sql).isPresent();
    assertThat(sql.get().cwe()).isEqualTo("CWE-89");
    assertThat(sql.get().severityLabel()).isEqualTo("CRITICAL");

    // LDAP
    var ldap = cfg.sinkFamilies().lookup("javax.naming.ldap.LdapContext.search/1");
    assertThat(ldap).isPresent();
    assertThat(ldap.get().cwe()).isEqualTo("CWE-90");

    // Command
    var cmd = cfg.sinkFamilies().lookup("java.lang.Runtime.exec/1");
    assertThat(cmd).isPresent();
    assertThat(cmd.get().cwe()).isEqualTo("CWE-78");
  }

  @Test
  void sinkFamilies_doesNotMatchUnrelatedCall() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    // A user-defined method should not match
    // any sink family pattern.
    var none = cfg.sinkFamilies().lookup("com.example.MyClass#myMethod");
    assertThat(none).isEmpty();
  }

  @Test
  void factKinds_haveTraits() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    var untrusted = cfg.factKinds().byName("UntrustedSource");
    assertThat(untrusted).isPresent();
    assertThat(untrusted.get().has(FactKind.Trait.UNTRUSTED_SOURCE)).isTrue();
    assertThat(untrusted.get().has(FactKind.Trait.PROPAGATED_BY_DECL)).isTrue();
    assertThat(untrusted.get().has(FactKind.Trait.PROPAGATED_THROUGH_CALL)).isTrue();
  }

  @Test
  void checkers_defaultEnabled() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    var divByZero = cfg.checkers().byName("DivideByZero");
    assertThat(divByZero).isPresent();
    assertThat(divByZero.get().enabled()).isTrue();
    assertThat(divByZero.get().severityLabel()).isEqualTo("HIGH");
    assertThat(divByZero.get().cwe()).isEqualTo("CWE-369");
    assertThat(divByZero.get().className())
        .isEqualTo("org.plumb.dfa.checkers.taint.DivByZeroChecker");
  }

  @Test
  void checkers_listEnabled_returnsOnlyEnabled() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    var enabled = cfg.checkers().enabled();
    assertThat(enabled).isNotEmpty();
    for (CheckerConfig c : enabled) {
      assertThat(c.enabled()).isTrue();
    }
  }

  @Test
  void checkerRegistry_canBeDisabledAtRuntime() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    // Disable DivideByZero
    CheckerConfig disabled =
        new CheckerConfig(
            "DivideByZero",
            false,
            "HIGH",
            "CWE-369",
            2,
            "full",
            null,
            "org.plumb.dfa.checkers.taint.DivByZeroChecker",
            "test disabled");
    cfg.checkers().register(disabled);
    assertThat(cfg.checkers().byName("DivideByZero"))
        .isPresent()
        .get()
        .satisfies(c -> assertThat(c.enabled()).isFalse());
  }

  @Test
  void sinkFamilies_canBeAddedAtRuntime() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    SinkFamily custom =
        new SinkFamily(
            "my-project.sinks",
            "CWE-XYZ",
            "CRITICAL",
            true,
            java.util.List.of("^com\\.example\\.DangerousSink#doIt"));
    cfg.sinkFamilies().register(custom);
    var lookup = cfg.sinkFamilies().lookup("com.example.DangerousSink#doIt");
    assertThat(lookup).isPresent();
    assertThat(lookup.get().cwe()).isEqualTo("CWE-XYZ");
  }

  @Test
  void sinkFamilies_canBeRemovedAtRuntime() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    cfg.sinkFamilies().deregister("sql.sinks");
    var sql = cfg.sinkFamilies().lookup("java.sql.Statement#execute(Ljava.lang.String;)Z");
    assertThat(sql).isEmpty();
  }

  @Test
  void tuning_hasExpectedDefaults() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    var t = cfg.tuning();
    assertThat(t.contextDepth()).isEqualTo(2);
    assertThat(t.worklistMaxSize()).isEqualTo(10000);
    assertThat(t.z3TimeoutMs()).isEqualTo(5000);
    assertThat(t.pathSensitivity()).isEqualTo("full");
  }

  @Test
  void cweMappings_haveExpectedEntries() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    assertThat(cfg.cweMappings().get("DivideByZero")).isEqualTo("CWE-369");
    assertThat(cfg.cweMappings().get("SensitiveDataLeak_sql")).isEqualTo("CWE-89");
  }
}
