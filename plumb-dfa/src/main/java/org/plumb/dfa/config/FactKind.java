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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Typed dataflow fact.
 *
 * <p>A fact kind is a semantic property the analysis cares about. For example, {@code
 * UntrustedSource} marks a value as coming from outside the trust boundary; {@code MayBeZero} marks
 * a variable that may hold 0.
 *
 * <p>Each fact kind carries a set of {@link Trait}s that describe <em>how a fact of this kind is
 * meant to propagate</em>. <b>The traits are documentation, not a policy the engine executes.</b>
 * No production code reads them: the propagation is implemented per checker, and a checker
 * propagates what it needs to propagate.
 *
 * <p>That is worth stating plainly, because the alternative reading is a plausible one: a reader
 * could reasonably believe that adding {@code PROPAGATED_BY_DECL} to a kind makes the engine copy
 * the fact from a def to its uses. It does not. {@link #has(Trait)} exists and is exercised by the
 * tests, but the engine never calls it.
 *
 * <p>The traits are still worth keeping: they are the catalogue's own statement of intent, they are
 * what {@code fact-kinds.yaml} configures, and they are what a future trait-driven propagation step
 * would consume. What they are not is a mechanism that exists today.
 *
 * <p>Fact kinds are loaded from {@code fact-kinds.yaml} and registered via {@link
 * FactKindRegistry#register}.
 */
public final class FactKind {

  /**
   * How a fact kind is meant to propagate through the IR.
   *
   * <p><b>These are declarations of intent, not behaviour the engine executes.</b> No production
   * code reads a trait; the propagation is implemented by the checker that owns the kind. The enum
   * values describe what a fact of that kind SHOULD do, so the catalogue stays readable and a
   * future trait-driven step has something to consume.
   */
  public enum Trait {
    /** Should flow from an SSA def to all its uses. */
    PROPAGATED_BY_DECL,
    /** May cross a function call boundary. */
    PROPAGATED_THROUGH_CALL,
    /** Marks the value as untrusted (e.g. user input). */
    UNTRUSTED_SOURCE,
    /** Marks a use site that consumes sensitive values. */
    UNTRUSTED_SINK,
    /** Should be dropped on loop back-edges. */
    NOT_PROPAGATED_THROUGH_LOOP,
    /** Facts of this kind should be joined with AND, not OR. */
    JOINED_BY_AND,
    /** Marks the instruction as a candidate path sink. */
    IS_PATH_SINK
  }

  private final String name;
  private final String description;
  private final String owner; // owning checker, may be null
  private final Set<Trait> traits;

  public FactKind(String name, String description, String owner, Set<Trait> traits) {
    this.name = Objects.requireNonNull(name, "name");
    this.description = description == null ? "" : description;
    this.owner = owner;
    // EnumSet.copyOf(emptySet) throws
    // IllegalArgumentException because
    // EnumSet needs at least one element to
    // determine the enum class. The test
    // suite often builds a FactKind with an
    // empty trait set (a placeholder for a
    // kind the engine has not specialised
    // yet), so handle that case explicitly.
    if (traits == null || traits.isEmpty()) {
      this.traits = Collections.emptySet();
    } else if (traits instanceof EnumSet && ((EnumSet<?>) traits).isEmpty()) {
      this.traits = Collections.emptySet();
    } else {
      this.traits = EnumSet.copyOf(traits);
    }
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public String owner() {
    return owner;
  }

  public Set<Trait> traits() {
    return Collections.unmodifiableSet(traits);
  }

  public boolean has(Trait t) {
    return traits.contains(t);
  }
}
