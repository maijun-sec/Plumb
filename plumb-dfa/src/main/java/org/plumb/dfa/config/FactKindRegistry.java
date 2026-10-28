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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of {@link FactKind}s, indexed by kind name. Thread-safe; supports both YAML-loaded
 * defaults and runtime registration / deregistration.
 */
public final class FactKindRegistry {

  private final Map<String, FactKind> byName;

  public FactKindRegistry(Map<String, FactKind> byName) {
    this.byName = new LinkedHashMap<>(byName);
  }

  public static FactKindRegistry empty() {
    return new FactKindRegistry(Map.of());
  }

  public Optional<FactKind> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public synchronized FactKind register(FactKind k) {
    Objects.requireNonNull(k, "k");
    return byName.put(k.name(), k);
  }

  public synchronized FactKind deregister(String name) {
    return byName.remove(name);
  }

  public java.util.Collection<FactKind> all() {
    return Collections.unmodifiableCollection(byName.values());
  }
}
