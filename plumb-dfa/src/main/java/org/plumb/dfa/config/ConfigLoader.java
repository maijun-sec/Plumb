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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads all 5 YAML configuration files and produces an immutable {@link EngineConfig}.
 *
 * <p>Two loading modes:
 *
 * <ul>
 *   <li>{@link #loadFromClasspath()} -- uses the bundled defaults in {@code
 *       plumb-dfa/src/main/resources/}. This is the default mode for production scans.
 *   <li>{@link #loadFromDirectory(Path)} -- uses a user-provided directory of YAML files (one per
 *       category). User files override the defaults; missing files fall back to classpath defaults.
 * </ul>
 *
 * <p>Files loaded (in order; later wins):
 *
 * <ol>
 *   <li>{@code sink-families.yaml}
 *   <li>{@code fact-kinds.yaml}
 *   <li>{@code checkers.yaml}
 *   <li>{@code cwe-mappings.yaml}
 *   <li>{@code tuning.yaml}
 * </ol>
 *
 * <p>Adding a new YAML file: extend the {@code FILES} list and add a {@code parse*} method.
 */
public final class ConfigLoader {

  private static final String[] FILES = {
    "sink-families.yaml", "fact-kinds.yaml", "checkers.yaml", "cwe-mappings.yaml", "tuning.yaml",
  };

  private ConfigLoader() {}

  /**
   * Load defaults from classpath. Returns an {@code EngineConfig} populated from the bundled YAMLs.
   */
  public static EngineConfig loadFromClasspath() {
    return loadFromClasspath(Map.of());
  }

  /**
   * Load defaults from classpath with overrides. {@code overrides} maps a filename (e.g.
   * "sink-families.yaml") to a replacement path; the replacement is loaded instead of the classpath
   * default.
   */
  public static EngineConfig loadFromClasspath(Map<String, Path> overrides) {
    Map<String, Object> sinkYaml = loadYamlFromClasspath("sink-families.yaml", overrides);
    Map<String, Object> kindYaml = loadYamlFromClasspath("fact-kinds.yaml", overrides);
    Map<String, Object> chkYaml = loadYamlFromClasspath("checkers.yaml", overrides);
    Map<String, Object> cweYaml = loadYamlFromClasspath("cwe-mappings.yaml", overrides);
    Map<String, Object> tunYaml = loadYamlFromClasspath("tuning.yaml", overrides);
    Map<String, Object> rcvYaml = loadYamlFromClasspath("receiver-passthrough.yaml", overrides);
    return build(sinkYaml, kindYaml, chkYaml, cweYaml, tunYaml, rcvYaml);
  }

  /**
   * Load from a directory of YAML files. Each expected file is loaded by name; a missing file is
   * silently skipped (defaults are incomplete but no error).
   */
  public static EngineConfig loadFromDirectory(Path yamlDir) {
    Objects.requireNonNull(yamlDir, "yamlDir");
    Map<String, Object> sinkYaml = loadYamlFromFile(yamlDir.resolve("sink-families.yaml"));
    Map<String, Object> kindYaml = loadYamlFromFile(yamlDir.resolve("fact-kinds.yaml"));
    Map<String, Object> chkYaml = loadYamlFromFile(yamlDir.resolve("checkers.yaml"));
    Map<String, Object> cweYaml = loadYamlFromFile(yamlDir.resolve("cwe-mappings.yaml"));
    Map<String, Object> tunYaml = loadYamlFromFile(yamlDir.resolve("tuning.yaml"));
    Map<String, Object> rcvYaml = loadYamlFromFile(yamlDir.resolve("receiver-passthrough.yaml"));
    return build(sinkYaml, kindYaml, chkYaml, cweYaml, tunYaml, rcvYaml);
  }

  @SuppressWarnings("unchecked")
  private static EngineConfig build(
      Map<String, Object> sinkYaml,
      Map<String, Object> kindYaml,
      Map<String, Object> chkYaml,
      Map<String, Object> cweYaml,
      Map<String, Object> tunYaml,
      Map<String, Object> rcvYaml) {
    SinkFamilyRegistry families = parseSinkFamilies(sinkYaml);
    FactKindRegistry kinds = parseFactKinds(kindYaml);
    CheckerRegistry checkers = parseCheckers(chkYaml);
    Map<String, String> cweMappings = parseCweMappings(cweYaml);
    TuningConfig tuning = parseTuning(tunYaml);
    List<Pattern> receiverPassthroughPatterns = parseReceiverPassthrough(rcvYaml);
    return new EngineConfig(
        families, kinds, checkers, cweMappings, tuning, receiverPassthroughPatterns);
  }

  /**
   * parse {@code receiver-passthrough.yaml}. Returns a compiled-regex list whose entries gate the
   * receiver → dest passthrough fallback in {@code
   * TaintedCheckerBase.propagateTaintFromCalleeSummary}.
   *
   * <p>YAML shape mirrors {@code sink-families.yaml} (top-level {@code families} map; each entry
   * has {@code enabled} + {@code patterns} list). The loader iterates all enabled families and
   * compiles each pattern.
   */
  @SuppressWarnings("unchecked")
  private static List<Pattern> parseReceiverPassthrough(Map<String, Object> yaml) {
    if (yaml == null || yaml.isEmpty()) return List.of();
    Object fams = yaml.get("families");
    if (!(fams instanceof Map)) return List.of();
    Map<String, Object> famsMap = (Map<String, Object>) fams;
    List<Pattern> out = new ArrayList<>();
    for (Map.Entry<String, Object> e : famsMap.entrySet()) {
      if (!(e.getValue() instanceof Map)) continue;
      Map<String, Object> spec = (Map<String, Object>) e.getValue();
      // enabled defaults to true; explicit
      // `enabled: false` skips the family.
      if (Boolean.FALSE.equals(spec.get("enabled"))) continue;
      Object rawPatterns = spec.get("patterns");
      if (!(rawPatterns instanceof List)) continue;
      for (Object item : (List<Object>) rawPatterns) {
        if (item instanceof String s) {
          out.add(Pattern.compile(s));
        }
      }
    }
    return out;
  }

  // ===== YAML loaders =====

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYamlFromClasspath(
      String filename, Map<String, Path> overrides) {
    Path override = overrides.get(filename);
    if (override != null) return loadYamlFromFile(override);
    // Try with leading slash and without (the
    // classpath resource conventions differ
    // between SnakeYAML and java.io).
    try (InputStream in = ConfigLoader.class.getResourceAsStream("/" + filename)) {
      if (in == null) {
        throw new IllegalStateException("Bundled YAML not found on classpath: " + filename);
      }
      Yaml yaml = new Yaml();
      Object loaded = yaml.load(in);
      if (loaded == null) return Map.of();
      return (Map<String, Object>) loaded;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load " + filename, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYamlFromFile(Path file) {
    if (!Files.exists(file)) return Map.of();
    try (InputStream in = Files.newInputStream(file)) {
      Yaml yaml = new Yaml();
      Object loaded = yaml.load(in);
      if (loaded == null) return Map.of();
      return (Map<String, Object>) loaded;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load " + file, e);
    }
  }

  // ===== Parsers =====

  @SuppressWarnings("unchecked")
  private static SinkFamilyRegistry parseSinkFamilies(Map<String, Object> yaml) {
    Object fams = yaml.get("families");
    if (!(fams instanceof Map)) return SinkFamilyRegistry.empty();
    Map<String, Object> famsMap = (Map<String, Object>) fams;
    Map<String, SinkFamily> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : famsMap.entrySet()) {
      String name = e.getKey();
      if (!(e.getValue() instanceof Map)) continue;
      Map<String, Object> spec = (Map<String, Object>) e.getValue();
      String cwe = asString(spec.get("cwe"));
      String sev = asString(spec.get("severity"));
      boolean enabled = !asBoolean(spec.get("enabled")) || Boolean.TRUE.equals(spec.get("enabled"));
      // The default of `enabled` is true if
      // missing; set false only if explicitly
      // present and false.
      enabled = !Boolean.FALSE.equals(spec.get("enabled"));
      // optional family-wide default
      // arg index (used when no per-pattern
      // arg_index is set).
      int defaultArgIdx = asInt(spec.get("default_arg_index"), 0);
      // each pattern may be a bare
      // string or {pattern: "...", arg_index: N}.
      List<SinkFamily.SinkPattern> parsedPatterns = parsePatterns(spec.get("patterns"));
      out.put(name, new SinkFamily(name, cwe, sev, enabled, defaultArgIdx, parsedPatterns));
    }
    return new SinkFamilyRegistry(out);
  }

  /**
   * parse a patterns list whose entries are either bare strings or maps with {@code pattern} and
   * optional {@code arg_index}.
   */
  @SuppressWarnings("unchecked")
  private static List<SinkFamily.SinkPattern> parsePatterns(Object raw) {
    if (!(raw instanceof List)) return List.of();
    List<Object> list = (List<Object>) raw;
    List<SinkFamily.SinkPattern> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof String s) {
        out.add(new SinkFamily.SinkPattern(s, -1));
      } else if (item instanceof Map) {
        Map<String, Object> m = (Map<String, Object>) item;
        String p = asString(m.get("pattern"));
        if (p == null) continue;
        int ai = asInt(m.get("arg_index"), -1);
        out.add(new SinkFamily.SinkPattern(p, ai));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static FactKindRegistry parseFactKinds(Map<String, Object> yaml) {
    // start with the 12 built-in kinds
    // (UNTRUSTED_SOURCE, MAY_BE_NULL, etc.) so
    // checker implementations that target the
    // common dataflow categories have a stable
    // registration table to look up by name.
    // The YAML may then override or add to
    // these.
    FactKindRegistry builtins = org.plumb.dfa.fact.BuiltinFactKinds.registryWithBuiltins();
    Map<String, FactKind> out = new LinkedHashMap<>();
    for (FactKind k : builtins.all()) {
      out.put(k.name(), k);
    }
    Object kinds = yaml.get("fact_kinds");
    if (!(kinds instanceof Map)) return new FactKindRegistry(out);
    Map<String, Object> kindsMap = (Map<String, Object>) kinds;
    for (Map.Entry<String, Object> e : kindsMap.entrySet()) {
      String name = e.getKey();
      if (!(e.getValue() instanceof Map)) continue;
      Map<String, Object> spec = (Map<String, Object>) e.getValue();
      String desc = asString(spec.get("description"));
      String owner = asString(spec.get("owner"));
      List<String> traitStrs = asStringList(spec.get("traits"));
      java.util.Set<FactKind.Trait> traits = new java.util.LinkedHashSet<>();
      for (String t : traitStrs) {
        try {
          traits.add(FactKind.Trait.valueOf(t));
        } catch (IllegalArgumentException ex) {
          // Skip unknown trait names so a
          // future trait addition in a
          // newer YAML doesn't break an
          // older engine.
        }
      }
      out.put(name, new FactKind(name, desc, owner, traits));
    }
    return new FactKindRegistry(out);
  }

  @SuppressWarnings("unchecked")
  private static CheckerRegistry parseCheckers(Map<String, Object> yaml) {
    Object chks = yaml.get("checkers");
    if (!(chks instanceof Map)) return CheckerRegistry.empty();
    Map<String, Object> chksMap = (Map<String, Object>) chks;
    Map<String, CheckerConfig> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : chksMap.entrySet()) {
      String name = e.getKey();
      if (!(e.getValue() instanceof Map)) continue;
      Map<String, Object> spec = (Map<String, Object>) e.getValue();
      boolean enabled = !Boolean.FALSE.equals(spec.get("enabled"));
      String sev = asString(spec.get("severity"));
      String cwe = asString(spec.get("cwe"));
      int ctxDepth = asInt(spec.get("context_depth"), 1);
      String path = asString(spec.get("path_sensitivity"));
      if (path == null) path = "full";
      String taintDom = asString(spec.get("taint_domain"));
      String cls = asString(spec.get("class"));
      String desc = asString(spec.get("description"));
      // Aggressiveness knob (low/medium/high).
      // Default "medium" if absent (matches Coverity's
      // baseline). Engine level default can be
      // overridden per-checker here.
      String aggressiveness = asString(spec.get("aggressiveness"));
      if (aggressiveness == null) aggressiveness = "medium";
      out.put(
          name,
          new CheckerConfig(
              name, enabled, sev, cwe, ctxDepth, path, taintDom, cls, desc, aggressiveness));
    }
    return new CheckerRegistry(out);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> parseCweMappings(Map<String, Object> yaml) {
    Object maps = yaml.get("mappings");
    if (!(maps instanceof Map)) return Map.of();
    Map<String, Object> m = (Map<String, Object>) maps;
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : m.entrySet()) {
      out.put(e.getKey(), Objects.toString(e.getValue(), null));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static TuningConfig parseTuning(Map<String, Object> yaml) {
    Object engine = yaml.get("engine");
    Map<String, Object> e = (engine instanceof Map) ? (Map<String, Object>) engine : Map.of();
    int ctxDepth = asInt(e.get("context_depth"), 2);
    int wlMax = asInt(e.get("worklist_max_size"), 10000);
    int z3ms = asInt(e.get("z3_timeout_ms"), 5000);
    int scSize = asInt(e.get("summary_cache_size"), 1000);
    int rwDepth = asInt(e.get("reverse_walk_max_depth"), 8);
    boolean preScan = asBoolean(e.get("pre_scan_enabled"));
    boolean libCls = asBoolean(e.get("library_call_classification"));
    boolean taintSC = asBoolean(e.get("taint_caller_string_concat"));
    // Top-level keys
    String pathSens = asString(yaml.get("path_sensitivity"));
    if (pathSens == null) pathSens = "full";
    Object reporting = yaml.get("reporting");
    String groupBy = "cwe_family";
    String outputFmt = "sarif";
    if (reporting instanceof Map) {
      String g = asString(((Map<String, Object>) reporting).get("group_by"));
      if (g != null) groupBy = g;
      String o = asString(((Map<String, Object>) reporting).get("output_format"));
      if (o != null) outputFmt = o;
    }
    Object performance = yaml.get("performance");
    boolean parallel = false;
    int perFnMs = 30000;
    if (performance instanceof Map) {
      parallel = asBoolean(((Map<String, Object>) performance).get("parallel"));
      perFnMs = asInt(((Map<String, Object>) performance).get("per_function_timeout_ms"), 30000);
    }
    // the points-to context-sensitivity policy. Read as the RAW string and kept unset
    // (null) when absent, so "not configured" stays distinguishable from an explicit choice --
    // an absent key must follow the engine default even if that default later changes, while
    // `context_insensitive` must mean exactly that.
    String ptsPolicy = asString(e.get("points_to_policy"));
    TuningConfig tuning =
        new TuningConfig(
            ctxDepth, wlMax, z3ms, scSize, rwDepth, preScan, libCls, taintSC, pathSens, groupBy,
            outputFmt, parallel, perFnMs);
    return ptsPolicy == null
        ? tuning
        : tuning.withPointsToPolicy(org.plumb.dfa.points.AbstractObject.Policy.fromName(ptsPolicy));
  }

  // ===== YAML helpers =====

  private static String asString(Object o) {
    if (o == null) return null;
    return o.toString();
  }

  private static boolean asBoolean(Object o) {
    return Boolean.TRUE.equals(o);
  }

  private static int asInt(Object o, int defaultValue) {
    if (o == null) return defaultValue;
    if (o instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(o.toString());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> asStringList(Object o) {
    if (o == null) return Collections.emptyList();
    if (o instanceof List) {
      List<String> out = new ArrayList<>();
      for (Object e : (List<Object>) o) {
        out.add(Objects.toString(e, ""));
      }
      return out;
    }
    return Collections.singletonList(o.toString());
  }
}
