package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * call-graph strategy configuration tests. Covers:
 *
 * <ul>
 *   <li>YAML camelCase + snake_case parsing for {@code cgStrategy} / {@code cg-strategy} (legacy
 *       compat convention).
 *   <li>Default value ({@code "cha"}) when key is absent.
 *   <li>Acceptance of all 6 base strategies + the {@code cached:...} wrapper form.
 *   <li>Rejection of unknown strategy names + malformed {@code cached:...} specs at
 *       config-validation time.
 *   <li>CLI flag plumbing (via {@link SastMain} command-line parser; we don't run the full scan,
 *       just check the value lands in {@code ScanConfig}).
 * </ul>
 */
class RcgStrategyTest {

  @Test
  void defaultIsGlobal() {
    // — the project directive is that analysis must be
    // global, so the default is now "global" (not "cha").
    ScanConfig cfg = ConfigLoader.fromMap(Map.of());
    assertThat(cfg.cgStrategy).isEqualTo("global");
  }

  @Test
  void camelCaseCgsAccepted() throws Exception {
    Path tmp = writeYaml("cgStrategy: rta");
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      assertThat(cfg.cgStrategy).isEqualTo("rta");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void snakeCaseCgsAccepted() throws Exception {
    Path tmp = writeYaml("cg-strategy: vta");
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      assertThat(cfg.cgStrategy).isEqualTo("vta");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void camelCaseWinsOverSnakeCase() throws Exception {
    Path tmp = writeYaml("cgStrategy: rta\ncg-strategy: 1cfa");
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      assertThat(cfg.cgStrategy).isEqualTo("rta");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void allBaseStrategiesAccepted() {
    for (String s : new String[] {"cha", "rta", "vta", "1cfa", "stub", "global"}) {
      ScanConfig cfg =
          ConfigLoader.fromMap(Map.of("inputs", java.util.List.of("."), "cgStrategy", s));
      assertThat(cfg.cgStrategy).isEqualTo(s);
      // validate() must not throw
      cfg.validate();
    }
  }

  @Test
  void cachedWrapperAccepted() {
    ScanConfig cfg =
        ConfigLoader.fromMap(
            Map.of("inputs", java.util.List.of("."), "cgStrategy", "cached:cha:/tmp/cg.json"));
    assertThat(cfg.cgStrategy).isEqualTo("cached:cha:/tmp/cg.json");
    cfg.validate();
  }

  @Test
  void unknownStrategyRejected() {
    for (String s : new String[] {"foo", "CHA-FAST", "", "  "}) {
      assertThatThrownBy(() -> ScanConfig.validateCgStrategy(s))
          .as("strategy '%s' should be rejected", s)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void malformedCachedWrapperRejected() {
    // missing path
    assertThatThrownBy(() -> ScanConfig.validateCgStrategy("cached:cha"))
        .isInstanceOf(IllegalArgumentException.class);
    // missing inner name
    assertThatThrownBy(() -> ScanConfig.validateCgStrategy("cached::/tmp/cg.json"))
        .isInstanceOf(IllegalArgumentException.class);
    // non-numeric colon form (just one colon)
    assertThatThrownBy(() -> ScanConfig.validateCgStrategy("cached:cha"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cachedWrapperWithUnknownInnerStrategyRejected() {
    assertThatThrownBy(() -> ScanConfig.validateCgStrategy("cached:bogus:/tmp/cg.json"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validateRejectsEmptyCgStrategy() {
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(Path.of("."));
    cfg.cgStrategy = "";
    assertThatThrownBy(cfg::validate).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validateRejectsNullCgStrategy() {
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(Path.of("."));
    cfg.cgStrategy = null;
    assertThatThrownBy(cfg::validate).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void runUnifiedCheckersGlobalStrategyUsesCrossTuPath(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
    // Single-file Java source that triggers the unified
    // EqualsCalledOnNull checker on `s.equals(null)`.
    Path src = tmp.resolve("Foo.java");
    Files.writeString(
        src,
        "public class Foo {\n"
            + "    boolean isEmpty(String s) { return s.equals(null); }\n"
            + "}\n");
    Path out = tmp.resolve("findings.ndjson");
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(src);
    cfg.outputPath = out;
    cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
    cfg.cgStrategy = "global";
    // Make sure the unified checker we want to fire is on.
    cfg.runTaint = false;
    cfg.runStyle = false;
    // Defaults keep runEqualsCalledOnNull = true.
    int rc = new ScanRunner(cfg).run();
    assertThat(rc).isEqualTo(0);
    String ndjson = Files.readString(out);
    // The EqualsCalledOnNull checker fires iff the cross-TU
    // engine actually ran the unified checkers. If the global
    // path is broken (e.g. falls back to "cha" silently) the
    // finding is missing.
    assertThat(ndjson)
        .as("global path should fire EqualsCalledOnNull " + "via the cross-TU engine")
        .contains("CWE-754");
  }

  @Test
  void defaultCgStrategyIsGlobal() {
    // — global is now the default. "analysis must be
    // global" per the project directive.
    ScanConfig cfg = new ScanConfig();
    assertThat(cfg.cgStrategy).isEqualTo("global");
  }

  private static Path writeYaml(String body) throws Exception {
    Path tmp = Files.createTempFile("cfg", ".yaml");
    Files.writeString(tmp, body);
    return tmp;
  }
}
