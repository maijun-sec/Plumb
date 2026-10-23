package org.plumb.rules;

import java.util.Objects;

/**
 * Stable, unique identifier for a rule. The {@code id} is the contract surface ? a rule's UUID
 * never changes even if the rule is renamed, split, or merged. The {@code name} is the
 * human-readable slug used in CLI flags, suppression files, and dashboards.
 *
 * <p>Built-in rule IDs use a {@code bu-XXXX} slug pattern. Third-party rules should pick a slug
 * that does not collide and a UUID that is uniquely owned by the rule (one rule, one UUID,
 * forever).
 */
public final class RuleId {

  private final String id; // UUID, e.g. "a3f9c2b8-..."
  private final String name; // e.g. "TAINT_SQL_INJECTION"

  public RuleId(String id, String name) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = Objects.requireNonNull(name, "name");
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return name + "(" + id + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof RuleId r)) return false;
    return id.equals(r.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
