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
package org.plumb.rules;

import java.util.Objects;

/**
 * Stable, unique identifier for a rule. The {@code id} is the contract surface -- a rule's UUID
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
