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
package org.plumb.dfa.taint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.config.SinkFamilyRegistry;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The taint sources that are not calls: the variables and the container entries the catalogue
 * declares.
 *
 * <p><b>Why a source can be something other than a call.</b> Every other way the catalogue names a
 * source is a callee signature -- {@code java.io.FileInputStream.read}, {@code os.environ.get} --
 * and the catalogue matches on exactly that. A PHP superglobal is not a call: {@code $_GET['user']}
 * is a container access, so no signature exists to name, and the value arrives with no marker at
 * all. The same is true of the script languages' process-level inputs: {@code process.env} and
 * {@code sys.argv} are property reads.
 *
 * <p>So a source family may declare SOURCE VARIABLES, and a declared name comes in two shapes.
 *
 * <ul>
 *   <li>A plain name -- {@code _GET}, {@code _ENV} -- is a VARIABLE that is itself a source. The
 *       engine seeds it at the function entry, beside the parameters, because such a variable is a
 *       trust-boundary entry point in exactly that sense: the request reached the program through
 *       it.
 *   <li>A dotted name -- {@code process.env}, {@code sys.argv} -- is an ENTRY of a container: the
 *       source is the value read at {@code base[key]}, not the container and not the key. The
 *       engine recognises it where the read is: the container access that names the key.
 * </ul>
 *
 * <p><b>Why the entry form is what the script languages need.</b> Measured against the side cars,
 * {@code process.env.HOME} lowers to an {@code ARRAYLOAD} whose base is the variable {@code
 * process} and whose index is the constant {@code "env"}, followed by a second load of {@code
 * "HOME"} on the result. The key is right there in the IR, so a rule can name the entry without the
 * front end having to synthesise a call. That matters because the same catalogue already treats
 * {@code os.Getenv} and {@code os.Args} as sources for Go, {@code System.getenv} for C# and {@code
 * System.getProperty} for Java: the script languages were the only ones with no equivalent.
 *
 * <p><b>Why the entry form is not the whole container.</b> Marking {@code process} as a source
 * would be simpler, and wrong: {@code process.pid}, {@code process.platform} and {@code
 * process.cwd} are the program's own facts, not the attacker's. Naming the entry keeps the rule to
 * what the attacker can reach.
 *
 * <p><b>Why the language scope is not optional.</b> The name {@code _GET} means the request's query
 * string in PHP and nothing at all in any other language; the name {@code process.env} means the
 * environment in a script and nothing at all in a Java file. So every declaration is scoped by
 * language, and the engine asks for the declarations of the language it is analysing.
 */
public final class SourceVariables {

  /** The plain names: the variables that are themselves sources, by language. */
  private final Map<SourceLanguage, List<String>> seeds;

  /** The dotted names: the container entries that are sources, by language. */
  private final Map<SourceLanguage, Map<String, Set<String>>> entries;

  private SourceVariables(
      Map<SourceLanguage, List<String>> seeds,
      Map<SourceLanguage, Map<String, Set<String>>> entries) {
    this.seeds = Collections.unmodifiableMap(seeds);
    this.entries = Collections.unmodifiableMap(entries);
  }

  /**
   * The declarations of a catalogue, split into the two shapes.
   *
   * @param registry the catalogue
   * @return the declarations
   */
  public static SourceVariables of(SinkFamilyRegistry registry) {
    Map<SourceLanguage, List<String>> seeds = new LinkedHashMap<>();
    Map<SourceLanguage, Map<String, Set<String>>> entries = new LinkedHashMap<>();
    if (registry != null) {
      for (org.plumb.dfa.config.SinkFamily family : registry.all()) {
        for (Map.Entry<SourceLanguage, List<String>> declared :
            family.sourceVariablesByLanguage().entrySet()) {
          for (String name : declared.getValue()) {
            int dot = name.lastIndexOf('.');
            if (dot < 0) {
              seeds.computeIfAbsent(declared.getKey(), k -> new ArrayList<>()).add(name);
            } else {
              String base = name.substring(0, dot);
              String key = name.substring(dot + 1);
              entries
                  .computeIfAbsent(declared.getKey(), k -> new LinkedHashMap<>())
                  .computeIfAbsent(base, k -> new LinkedHashSet<>())
                  .add(key);
            }
          }
        }
      }
    }
    Map<SourceLanguage, List<String>> frozenSeeds = new LinkedHashMap<>();
    for (Map.Entry<SourceLanguage, List<String>> entry : seeds.entrySet()) {
      frozenSeeds.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    Map<SourceLanguage, Map<String, Set<String>>> frozenEntries = new LinkedHashMap<>();
    for (Map.Entry<SourceLanguage, Map<String, Set<String>>> entry : entries.entrySet()) {
      Map<String, Set<String>> perBase = new LinkedHashMap<>();
      for (Map.Entry<String, Set<String>> base : entry.getValue().entrySet()) {
        perBase.put(base.getKey(), Set.copyOf(base.getValue()));
      }
      frozenEntries.put(entry.getKey(), Collections.unmodifiableMap(perBase));
    }
    return new SourceVariables(frozenSeeds, frozenEntries);
  }

  /**
   * The variables that are themselves sources, for a language.
   *
   * @param language the language of the code being analysed
   * @return the names; empty when the catalogue declares none
   */
  public List<String> seeds(SourceLanguage language) {
    if (language == null) return List.of();
    return seeds.getOrDefault(language, List.of());
  }

  /**
   * Whether the catalogue declares the entry {@code base[key]} to be a source.
   *
   * @param language the language of the code being analysed
   * @param base the container's name
   * @param key the entry's name
   * @return true when the entry is a declared source
   */
  public boolean isEntry(SourceLanguage language, String base, String key) {
    if (language == null || base == null || key == null) return false;
    Map<String, Set<String>> perBase = entries.get(language);
    if (perBase == null) return false;
    Set<String> keys = perBase.get(base);
    return keys != null && keys.contains(key);
  }
}
