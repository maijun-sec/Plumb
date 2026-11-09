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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.tac.ir.SourceLanguage;
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

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConfigLoader.class);

  private static final String[] FILES = {
    "sink-families.yaml", "fact-kinds.yaml", "checkers.yaml", "cwe-mappings.yaml", "tuning.yaml",
  };

  /**
   * The name that means "every language" in a checker's {@code languages:} list.
   *
   * <p>A checker with no language-specific mechanism carries this, so a language added tomorrow
   * picks the checker up without the catalogue being edited.
   */
  static final String ALL_LANGUAGES = "all";

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
    Map<String, Object> argYaml = loadYamlFromClasspath("argument-passthrough.yaml", overrides);
    return build(sinkYaml, kindYaml, chkYaml, cweYaml, tunYaml, rcvYaml, argYaml);
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
    Map<String, Object> argYaml = loadYamlFromFile(yamlDir.resolve("argument-passthrough.yaml"));
    return build(sinkYaml, kindYaml, chkYaml, cweYaml, tunYaml, rcvYaml, argYaml);
  }

  @SuppressWarnings("unchecked")
  private static EngineConfig build(
      Map<String, Object> sinkYaml,
      Map<String, Object> kindYaml,
      Map<String, Object> chkYaml,
      Map<String, Object> cweYaml,
      Map<String, Object> tunYaml,
      Map<String, Object> rcvYaml,
      Map<String, Object> argYaml) {
    SinkFamilyRegistry families = parseSinkFamilies(sinkYaml);
    FactKindRegistry kinds = parseFactKinds(kindYaml);
    CheckerRegistry checkers = parseCheckers(chkYaml);
    Map<String, String> cweMappings = parseCweMappings(cweYaml);
    TuningConfig tuning = parseTuning(tunYaml);
    List<Pattern> receiverPassthroughPatterns = parseReceiverPassthrough(rcvYaml);
    ArgumentPassthrough argumentPassthrough = parseArgumentPassthrough(argYaml);
    return new EngineConfig(
        families,
        kinds,
        checkers,
        cweMappings,
        tuning,
        receiverPassthroughPatterns,
        org.plumb.dfa.semantics.LanguageSemanticsRegistry.withDefaults(),
        argumentPassthrough);
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

  /**
   * parse {@code argument-passthrough.yaml}. Returns the rules that say an ARGUMENT's taint reaches
   * the callee's RESULT.
   *
   * <p>YAML shape mirrors {@code receiver-passthrough.yaml} (top-level {@code families} map; each
   * entry has {@code enabled} + {@code patterns} list), except that each pattern is a MAP carrying
   * the pattern text and the argument position it names.
   */
  @SuppressWarnings("unchecked")
  private static ArgumentPassthrough parseArgumentPassthrough(Map<String, Object> yaml) {
    if (yaml == null || yaml.isEmpty()) return ArgumentPassthrough.empty();
    Object fams = yaml.get("families");
    if (!(fams instanceof Map)) return ArgumentPassthrough.empty();
    Map<String, Object> famsMap = (Map<String, Object>) fams;
    List<ArgumentPassthrough.Rule> out = new ArrayList<>();
    for (Map.Entry<String, Object> e : famsMap.entrySet()) {
      if (!(e.getValue() instanceof Map)) continue;
      Map<String, Object> spec = (Map<String, Object>) e.getValue();
      // enabled defaults to true; explicit
      // `enabled: false` skips the family.
      if (Boolean.FALSE.equals(spec.get("enabled"))) continue;
      Object rawPatterns = spec.get("patterns");
      if (!(rawPatterns instanceof List)) continue;
      for (Object item : (List<Object>) rawPatterns) {
        if (!(item instanceof Map)) continue;
        Map<String, Object> entry = (Map<String, Object>) item;
        Object rawPattern = entry.get("pattern");
        if (!(rawPattern instanceof String)) continue;
        List<Integer> indexes = parsePassthroughArgIndexes(entry.get("arg_index"));
        if (indexes.isEmpty()) continue;
        try {
          out.add(new ArgumentPassthrough.Rule(Pattern.compile((String) rawPattern), indexes));
        } catch (java.util.regex.PatternSyntaxException ignored) {
          // A pattern the engine cannot compile is a pattern it cannot use. The entry is
          // dropped rather than failing the load: one bad rule must not take the whole
          // configuration down.
        }
      }
    }
    return new ArgumentPassthrough(out);
  }

  /**
   * The argument positions a rule names.
   *
   * <p>Three shapes are accepted: a single number, a list of numbers, and the word {@code any} for
   * a rule that does not name one position.
   */
  @SuppressWarnings("unchecked")
  private static List<Integer> parsePassthroughArgIndexes(Object raw) {
    List<Integer> out = new ArrayList<>();
    if (raw == null) return out;
    if (raw instanceof Number n) {
      out.add(n.intValue());
      return out;
    }
    if (raw instanceof String s) {
      if ("any".equalsIgnoreCase(s.trim())) {
        out.add(ArgumentPassthrough.ANY_ARGUMENT);
      }
      return out;
    }
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Number n) {
          out.add(n.intValue());
        } else if (item instanceof String s && "any".equalsIgnoreCase(s.trim())) {
          out.add(ArgumentPassthrough.ANY_ARGUMENT);
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

  /**
   * Parse a sink-family YAML mapping. Exposed for tests.
   *
   * <p>The production path is {@link #loadFromClasspath()}. This accessor exists so a test can feed
   * the parser a mapping it could not write to disk -- which is how the "an unknown language name
   * is rejected" behaviour is pinned: the rejection happens in the parser, and the only way to
   * observe it is to call the parser.
   *
   * @param yaml the parsed YAML mapping
   * @return the parsed families
   */
  public static SinkFamilyRegistry parseSinkFamiliesForTest(Map<String, Object> yaml) {
    return parseSinkFamilies(yaml);
  }

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
      // Optional language restriction. Absent = every language, which is the common case: a
      // shared sink family (java.sql.Statement#execute) is reachable from every language.
      // Present = this family only applies to the listed languages.
      Set<SourceLanguage> languages = parseLanguages(spec.get("languages"));
      // The per-language pattern table: the symbols only one language can mention, kept on the
      // SAME family so the defect class stays one name with one CWE.
      LanguageRuleSet languageRules = parseLanguageRules(spec.get("by_language"), parsedPatterns);
      out.put(
          name,
          new SinkFamily(
              name, cwe, sev, enabled, defaultArgIdx, parsedPatterns, languages, languageRules));
    }
    return new SinkFamilyRegistry(out);
  }

  /**
   * Parse a {@code languages} list into a set of {@link SourceLanguage}.
   *
   * <p>Accepts the enum names in any case ({@code java}, {@code KOTLIN}, {@code Scala}). An
   * unrecognised name is skipped with a warning rather than aborting the load: a typo in one family
   * must not take the whole rule set down.
   *
   * <p>An absent or empty list yields an empty set, which means "every language".
   */
  @SuppressWarnings("unchecked")
  private static Set<SourceLanguage> parseLanguages(Object raw) {
    if (!(raw instanceof List)) return Set.of();
    Set<SourceLanguage> out = new LinkedHashSet<>();
    for (Object item : (List<Object>) raw) {
      if (!(item instanceof String s)) continue;
      String key = s.trim();
      SourceLanguage lang = SourceLanguage.parse(key);
      if (lang == SourceLanguage.UNKNOWN) {
        LOG.warn("Unknown language '{}' in sink family language restriction; ignored", key);
        continue;
      }
      out.add(lang);
    }
    return out;
  }

  /**
   * Parse the languages a checker declares.
   *
   * <p>Unlike a sink family's restriction, a checker's declaration is kept as the NAMES the YAML
   * wrote, because {@link CheckerConfig#languages()} is a name set that the filter matches against.
   *
   * <p><b>The {@code all} keyword.</b> A checker with no language-specific mechanism declares
   * {@code languages: [all]}, and that means EVERY language -- including one added after this
   * catalogue was written. Spelling the languages out one by one would make a new language load
   * ZERO checkers the day it is added, which is the silent disappearance this whole change exists
   * to remove. The languages a checker is <em>not</em> for are named explicitly.
   *
   * <p><b>Why an unknown name is dropped rather than kept.</b> A name that is not a language Plumb
   * knows matches nothing, so a checker that declared only {@code [jav]} would be dropped from
   * EVERY run -- including a Java run -- and the rule would simply stop firing. That is a silent
   * disappearance, so the name is reported instead.
   *
   * @param checker the checker's name, for the message
   * @param raw the YAML value
   * @return the language names, lower-cased; empty means "every language"
   */
  private static Set<String> parseCheckerLanguages(String checker, Object raw) {
    Set<String> out = new LinkedHashSet<>();
    for (String name : asStringList(raw)) {
      String key = name.trim().toLowerCase(java.util.Locale.ROOT);
      if (key.isEmpty()) continue;
      if ("all".equals(key) || "*".equals(key)) {
        // Every language, now and later. The set is cleared so an explicit name
        // cannot contradict the wildcard.
        out.clear();
        out.add(ALL_LANGUAGES);
        continue;
      }
      if (ALL_LANGUAGES.equals(key)) {
        out.clear();
        out.add(ALL_LANGUAGES);
        continue;
      }
      if (SourceLanguage.parse(key) == SourceLanguage.UNKNOWN) {
        LOG.warn(
            "Unknown language '{}' in the language restriction of checker '{}'; ignored",
            name,
            checker);
        continue;
      }
      out.add(key);
    }
    return out;
  }

  /**
   * parse a patterns list whose entries are either bare strings or maps with {@code pattern} and
   * optional {@code arg_index}.
   *
   * <p>{@code arg_index} may be a SCALAR ({@code arg_index: 0}) or a LIST ({@code arg_index: [0,
   * 1]}). The list form names SEVERAL argument positions that can carry the taint for this pattern
   * -- see {@link SinkFamily.SinkPattern} for why a symbol can have more than one.
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
        int[] indexes = parseArgIndexes(m.get("arg_index"));
        int primary = indexes.length > 0 ? indexes[0] : -1;
        out.add(new SinkFamily.SinkPattern(p, primary, indexes));
      }
    }
    return out;
  }

  /**
   * Parse an {@code arg_index} value into the list of candidate positions.
   *
   * <p>Accepts a scalar (the common case) and a list. An absent or unparseable value yields an
   * empty array, which means "unset" -- the pattern then falls back to the family default.
   */
  private static int[] parseArgIndexes(Object raw) {
    if (raw == null) return new int[0];
    if (raw instanceof Number n) return new int[] {n.intValue()};
    if (raw instanceof String s) {
      try {
        return new int[] {Integer.parseInt(s.trim())};
      } catch (NumberFormatException e) {
        return new int[0];
      }
    }
    if (raw instanceof List<?> list) {
      java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
      for (Object item : list) {
        if (item instanceof Number n) {
          if (!out.contains(n.intValue())) out.add(n.intValue());
        } else if (item instanceof String s) {
          try {
            int v = Integer.parseInt(s.trim());
            if (!out.contains(v)) out.add(v);
          } catch (NumberFormatException e) {
            // skip an unparseable entry rather than failing the whole catalog
          }
        }
      }
      int[] arr = new int[out.size()];
      for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
      return arr;
    }
    return new int[0];
  }

  /**
   * Parses the per-language pattern table of a sink family.
   *
   * <p>The YAML shape is:
   *
   * <pre>
   *   families:
   *     regex.sinks:
   *       cwe: CWE-1333
   *       patterns:                     # shared: reachable from every language
   *         - '^java\.util\.regex\.Pattern\.compile(/\d+)?'
   *       by_language:                  # per language: only that language can mention it
   *         kotlin:
   *           - pattern: '^kotlin\.text\.Regex\.&lt;init&gt;'
   *             arg_index: 0
   *         scala:
   *           - '^scala\.util\.matching\.Regex\.&lt;init&gt;'
   * </pre>
   *
   * <p>See {@link LanguageRuleSet} for why the per-language symbols belong to the SAME family
   * rather than to separate families.
   *
   * @param raw the raw YAML value
   * @return the per-language pattern table; empty when the family declares none
   */
  private static LanguageRuleSet parseLanguageRules(
      Object raw, List<SinkFamily.SinkPattern> shared) {
    if (!(raw instanceof Map)) {
      return new LanguageRuleSet(shared, java.util.Map.of());
    }
    Map<String, Object> byLang = (Map<String, Object>) raw;
    Map<org.plumb.tac.ir.SourceLanguage, List<SinkFamily.SinkPattern>> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : byLang.entrySet()) {
      org.plumb.tac.ir.SourceLanguage lang = org.plumb.tac.ir.SourceLanguage.parse(e.getKey());
      if (lang == org.plumb.tac.ir.SourceLanguage.UNKNOWN) {
        // An unknown language name is a TYPO, not a language to ignore. Dropping it silently
        // would make the rule look like it was configured, while never being able to fire.
        throw new IllegalArgumentException(
            "unknown language '" + e.getKey() + "' in by_language of a sink family");
      }
      List<SinkFamily.SinkPattern> pats = parsePatterns(e.getValue());
      if (!pats.isEmpty()) {
        out.put(lang, pats);
      }
    }
    return new LanguageRuleSet(shared, out);
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
      // Default "medium" if absent (the engine's
      // baseline). The engine level default can be
      // overridden per-checker here.
      String aggressiveness = asString(spec.get("aggressiveness"));
      if (aggressiveness == null) aggressiveness = "medium";
      // The languages this checker applies to. Absent means "every language",
      // which is the conservative default: a checker with no language-specific
      // mechanism should not be excluded from a run that named one language.
      java.util.Set<String> languages = parseCheckerLanguages(name, spec.get("languages"));
      out.put(
          name,
          new CheckerConfig(
              name,
              enabled,
              sev,
              cwe,
              ctxDepth,
              path,
              taintDom,
              cls,
              desc,
              aggressiveness,
              languages));
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
