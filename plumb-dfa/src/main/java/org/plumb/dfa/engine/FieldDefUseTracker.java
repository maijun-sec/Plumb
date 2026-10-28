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
package org.plumb.dfa.engine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacInstruction;

/**
 * Program-level field definition / use tracker. Lives on the {@code AnalysisContext} (one per
 * engine run).
 *
 * <p>Each checker that observes a field store (write) or field load (read) calls {@link #recordDef}
 * or {@link #recordUse} with the field's {@code receiver.simpleName + "." + fieldName} key. After
 * the engine has processed all functions, the cross-function layer answers "was this field ever
 * read?" via {@link #isRead(String)} and "was it ever written?" via {@link #isWritten(String)}.
 *
 * <p>Used by the {@code UnusedFieldChecker} to suppress findings on private fields that are read
 * elsewhere in the program -- without the tracker the check would fire on every setter that no
 * other local function reads, which is the classic over-fire on POJO style code.
 */
public final class FieldDefUseTracker {

  /** Field key → set of function signatures that write it. */
  private final Map<String, Set<String>> defs = new LinkedHashMap<>();

  /** Field key → set of function signatures that read it. */
  private final Map<String, Set<String>> uses = new LinkedHashMap<>();

  public void recordDef(String fieldKey, String fnSignature) {
    if (fieldKey == null || fieldKey.isBlank()) return;
    defs.computeIfAbsent(fieldKey, k -> new LinkedHashSet<>())
        .add(fnSignature == null ? "<unknown>" : fnSignature);
  }

  public void recordUse(String fieldKey, String fnSignature) {
    if (fieldKey == null || fieldKey.isBlank()) return;
    uses.computeIfAbsent(fieldKey, k -> new LinkedHashSet<>())
        .add(fnSignature == null ? "<unknown>" : fnSignature);
  }

  public boolean isRead(String fieldKey) {
    if (fieldKey == null) return false;
    Set<String> rs = uses.get(fieldKey);
    return rs != null && !rs.isEmpty();
  }

  public boolean isWritten(String fieldKey) {
    if (fieldKey == null) return false;
    Set<String> ds = defs.get(fieldKey);
    return ds != null && !ds.isEmpty();
  }

  public Set<String> readBy(String fieldKey) {
    return uses.getOrDefault(fieldKey, Set.of());
  }

  public Set<String> writtenBy(String fieldKey) {
    return defs.getOrDefault(fieldKey, Set.of());
  }

  /** Convenience for a {@link TacInstruction} of kind FIELDSTORE. */
  public void recordFieldStore(TacInstruction ins, String fnSignature) {
    if (ins == null) return;
    String key = fieldKey(ins);
    if (key != null) recordDef(key, fnSignature);
  }

  /** Convenience for a {@link TacInstruction} of kind FIELDLOAD. */
  public void recordFieldLoad(TacInstruction ins, String fnSignature) {
    if (ins == null) return;
    String key = fieldKey(ins);
    if (key != null) recordUse(key, fnSignature);
  }

  static String fieldKey(TacInstruction ins) {
    if (ins == null) return null;
    String name = ins.fieldName;
    if (name == null || name.isEmpty()) return null;
    return "field:" + name;
  }

  public int size() {
    return defs.size() + uses.size();
  }

  /** All field keys that have at least one def. */
  public Set<String> writtenKeys() {
    return new LinkedHashSet<>(defs.keySet());
  }

  /** All field keys that have at least one use. */
  public Set<String> readKeys() {
    return new LinkedHashSet<>(uses.keySet());
  }

  public void clear() {
    defs.clear();
    uses.clear();
  }

  /** For tests / logs: dump a short summary. */
  public String summary() {
    return "FieldDefUseTracker{defs=" + defs.size() + ", uses=" + uses.size() + "}";
  }
}
