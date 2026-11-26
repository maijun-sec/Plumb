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

import java.util.List;
import java.util.regex.Pattern;

/**
 * The project's own sanitizers.
 *
 * <p>A "sanitizer" is a method whose return value is provably clean whatever the taint of its input
 * is. The engine has a built-in list of such methods -- the JDK's numeric parsers, the
 * cryptographic boundary -- but a project has trust boundaries of its own: its own escaping helper,
 * its own validator, its own canonicaliser. The engine cannot know them, so the project declares
 * them here.
 *
 * <p>Each entry matches the callee signature (the FQN.method/arity form {@code
 * TacTranslator.methodSig} produces) with a regular expression. The expression is matched against
 * the WHOLE signature, so it is anchored on both ends by default.
 *
 * <p>This table ADDS to the built-in list; it does not replace it. The built-in list is the
 * engine's knowledge about the standard library, and it is not the project's to turn off.
 */
public final class SanitizerConfig {

  /** One declared sanitizer. */
  public record Entry(String id, Pattern match, boolean enabled) {

    /** Whether this entry claims the callee signature. */
    public boolean matches(String calleeSig) {
      if (!enabled || match == null || calleeSig == null) {
        return false;
      }
      return match.matcher(calleeSig).matches();
    }
  }

  private static final SanitizerConfig EMPTY = new SanitizerConfig(List.of());

  private final List<Entry> entries;

  private SanitizerConfig(List<Entry> entries) {
    this.entries = entries;
  }

  /** The configuration with no declared sanitizer. */
  public static SanitizerConfig empty() {
    return EMPTY;
  }

  /** The configuration with the given entries. */
  public static SanitizerConfig of(List<Entry> entries) {
    if (entries == null || entries.isEmpty()) {
      return EMPTY;
    }
    return new SanitizerConfig(List.copyOf(entries));
  }

  /** The declared entries, in declaration order. */
  public List<Entry> entries() {
    return entries;
  }

  /** Whether the callee signature is one of the declared sanitizers. */
  public boolean isSanitizer(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) {
      return false;
    }
    for (Entry entry : entries) {
      if (entry.matches(calleeSig)) {
        return true;
      }
    }
    return false;
  }
}
