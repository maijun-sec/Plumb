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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * verify the YAML config loader accepts BOTH the legacy snake_case keys (e.g. <code>div_by_zero
 * </code>) and the canonical camelCase keys (e.g. <code>divByZero</code>). This is a
 * backward-compat fix for older sweep configs that were written with the snake_case form.
 */
class R325ConfigLoaderCompatTest {

  @Test
  void snakeCaseDivByZeroIsHonored() {
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          div_by_zero: false
          constant_condition: false
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runDivByZero).isFalse();
    assertThat(cfg.runConstantCondition).isFalse();
    // Untouched checks should keep their default (true).
    assertThat(cfg.runTaint).isTrue();
    assertThat(cfg.runStyle).isTrue();
  }

  @Test
  void snakeCaseTaintStyleIsHonored() {
    // Originally the loader only saw `taint` / `style` and
    // ignored every other snake_case key. After the
    // fix, the legacy yaml is read in full.
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          taint: true
          style: false
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runTaint).isTrue();
    assertThat(cfg.runStyle).isFalse();
  }

  @Test
  void camelCaseStillWins() {
    // When both forms are present, camelCase is the
    // canonical one and wins.
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          divByZero: true
          div_by_zero: false
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runDivByZero).isTrue();
  }

  @Test
  void allSnakeCaseCheckerFlags() {
    // Sanity: every legacy snake_case key from the
    // original 42-key r190_juliet_smoke.yaml is
    // recognised and applied. Without the
    // fix, every one of these would be ignored
    // and the default-true ScanConfig fields
    // would dominate.
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          taint: true
          style: false
          div_by_zero: false
          resource_leak: false
          null_deref: false
          hardcoded_cred: false
          int_overflow: false
          array_bounds: false
          weak_crypto: false
          insecure_random: false
          predictable_seed: false
          predictable_key: false
          static_api_key: false
          insecure_comms: false
          cors_misconfig: false
          insecure_cookie: false
          bad_shift: false
          infinite_loop: false
          constant_condition: false
          sensitive_data_leak: false
          field_hardcoded_password: false
          missing_authorization: false
          missing_input_validation: false
          information_exposure: false
          unchecked_return: false
          incorrect_return: false
          thread_run: false
          string_equality: false
          catch_generic: false
          obsolete_function: false
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runTaint).isTrue();
    assertThat(cfg.runStyle).isFalse();
    assertThat(cfg.runDivByZero).isFalse();
    assertThat(cfg.runResourceLeak).isFalse();
    assertThat(cfg.runNullDeref).isFalse();
    assertThat(cfg.runHardcodedCred).isFalse();
    assertThat(cfg.runIntOverflow).isFalse();
    assertThat(cfg.runArrayBounds).isFalse();
    assertThat(cfg.runWeakCrypto).isFalse();
    assertThat(cfg.runInsecureRandom).isFalse();
    assertThat(cfg.runPredictableSeed).isFalse();
    assertThat(cfg.runPredictableKey).isFalse();
    assertThat(cfg.runStaticApiKey).isFalse();
    assertThat(cfg.runInsecureComms).isFalse();
    assertThat(cfg.runCorsMisconfig).isFalse();
    assertThat(cfg.runInsecureCookie).isFalse();
    assertThat(cfg.runBadShift).isFalse();
    assertThat(cfg.runInfiniteLoop).isFalse();
    assertThat(cfg.runConstantCondition).isFalse();
    assertThat(cfg.runSensitiveDataLeak).isFalse();
    assertThat(cfg.runFieldHardcodedPassword).isFalse();
    assertThat(cfg.runMissingAuthorization).isFalse();
    assertThat(cfg.runMissingInputValidation).isFalse();
    assertThat(cfg.runInformationExposure).isFalse();
    assertThat(cfg.runUncheckedReturn).isFalse();
    assertThat(cfg.runIncorrectReturn).isFalse();
    assertThat(cfg.runThreadRun).isFalse();
    assertThat(cfg.runStringEquality).isFalse();
    assertThat(cfg.runCatchGeneric).isFalse();
    assertThat(cfg.runObsoleteFunction).isFalse();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(String yaml) {
    org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
    return (Map<String, Object>) y.load(yaml);
  }
}
