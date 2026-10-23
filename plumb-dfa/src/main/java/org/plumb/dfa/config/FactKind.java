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
 * <p>Each fact kind has a set of {@link Trait}s that declaratively describe how the engine
 * propagates it. The engine consults the trait bits when handling an instruction; checkers don't
 * need to write per-kind propagation code.
 *
 * <p>Fact kinds are loaded from {@code fact-kinds.yaml} and registered via {@link
 * FactKindRegistry#register}.
 */
public final class FactKind {

  /** How a fact kind propagates through the IR. */
  public enum Trait {
    /** Flows from SSA def to all uses. */
    PROPAGATED_BY_DECL,
    /** May propagate across function call boundaries. */
    PROPAGATED_THROUGH_CALL,
    /** Marks the value as untrusted (e.g. user input). */
    UNTRUSTED_SOURCE,
    /** Marks a use site that consumes sensitive values. */
    UNTRUSTED_SINK,
    /** When set, the fact is dropped on loop back-edges. */
    NOT_PROPAGATED_THROUGH_LOOP,
    /** Facts of this kind are joined with AND, not OR. */
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
