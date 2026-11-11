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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.plumb.dfa.taint.FieldOpClassifier;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Program-level field definition / use tracker. Lives on the {@code AnalysisContext} (one per
 * engine run).
 *
 * <p>Each checker that observes a field store (write) or field load (read) calls {@link #recordDef}
 * or {@link #recordUse} with the field's key. After the engine has processed all functions, the
 * cross-function layer answers "was this field ever read?" via {@link #isRead(String)} and "was it
 * ever written?" via {@link #isWritten(String)}.
 *
 * <p>Used by the {@code UnusedFieldChecker} to suppress findings on private fields that are read
 * elsewhere in the program -- without the tracker the check would fire on every setter that no
 * other local function reads, which is the classic over-fire on POJO style code.
 *
 * <h2>What the key names</h2>
 *
 * <p>A field belongs to a class. Keying it by its NAME alone -- {@code "field:" + name} -- makes
 * two classes that declare a field of the same name share one key, and the rule can then report at
 * most one of them: the second class's field looks read (or written) because the first class's
 * field was.
 *
 * <p>The class is taken from the RECEIVER of the access. The receiver is the thing the field hangs
 * on: {@code this.counter} is a field of this class, {@code other.counter} is a field of {@code
 * other's} class, and {@code Counter.total} is a field of {@code Counter}. The receiver's type is
 * in the IR -- it is the type of the instruction's first operand -- so the class a field belongs to
 * is known without asking the front end for anything new.
 *
 * <p>It is the receiver's class and NOT the class the access is written in. A field of a base class
 * is written in the subclass and read in the base class; the class the access is written in says
 * nothing about which field is meant, and keying the two sides differently would report a field
 * that IS read as never read.
 */
public final class FieldDefUseTracker {

  /**
   * Field key → set of function signatures that write it.
   *
   * <p>Concurrent: the parallel worklist has every worker recording into this tracker, and a plain
   * hash map mutated from several threads loses records and can corrupt itself.
   */
  private final Map<String, Set<String>> defs = new ConcurrentHashMap<>();

  /** Field key → set of function signatures that read it. */
  private final Map<String, Set<String>> uses = new ConcurrentHashMap<>();

  /**
   * Field name → how many use keys carry that name.
   *
   * <p>A read whose receiver had no class is recorded under the bare name. When the WRITE's
   * receiver had no class either, any read of that name might be the same field, and the tracker
   * has to answer that without walking the whole map.
   */
  private final Map<String, Integer> usesByName = new ConcurrentHashMap<>();

  public void recordDef(String fieldKey, String fnSignature) {
    if (fieldKey == null || fieldKey.isBlank()) return;
    defs.computeIfAbsent(fieldKey, k -> ConcurrentHashMap.newKeySet())
        .add(fnSignature == null ? "<unknown>" : fnSignature);
  }

  public void recordUse(String fieldKey, String fnSignature) {
    if (fieldKey == null || fieldKey.isBlank()) return;
    uses.computeIfAbsent(fieldKey, k -> ConcurrentHashMap.newKeySet())
        .add(fnSignature == null ? "<unknown>" : fnSignature);
    usesByName.merge(nameOf(fieldKey), 1, Integer::sum);
  }

  /**
   * Whether the field under {@code fieldKey} was ever read.
   *
   * <p>A read of the SAME field is a read under the same key. Two more reads count as well, and
   * both of them are about the same field that the program could not name a class for:
   *
   * <ul>
   *   <li>a read recorded under the bare name -- the receiver of that read had no class, so it may
   *       be this field;
   *   <li>when the key itself is the bare name -- the receiver of the WRITE had no class, so any
   *       read of that name may be this field.
   * </ul>
   *
   * <p>A read recorded under ANOTHER class's key is a different field, and it does not count: the
   * two classes' fields of the same name are not the same field.
   */
  public boolean isRead(String fieldKey) {
    if (fieldKey == null) return false;
    Set<String> rs = uses.get(fieldKey);
    if (rs != null && !rs.isEmpty()) return true;
    String bare = "field:" + nameOf(fieldKey);
    Set<String> brs = uses.get(bare);
    if (brs != null && !brs.isEmpty()) return true;
    if (bare.equals(fieldKey)) {
      Integer n = usesByName.get(nameOf(fieldKey));
      return n != null && n > 0;
    }
    return false;
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

  /**
   * The key a field store or load is recorded under.
   *
   * <p>Public because the checker walks the program's instructions to find the function that owns a
   * field, and it has to build the same key the tracker recorded.
   *
   * @param ins the instruction to take the key from
   * @return the key, or null when the instruction names no field
   */
  public static String fieldKey(TacInstruction ins) {
    if (ins == null) return null;
    String name = ins.fieldName;
    if (name == null || name.isEmpty()) return null;
    String owner = fieldOwner(ins);
    if (owner == null || owner.isEmpty()) return "field:" + name;
    return "field:" + owner + "." + name;
  }

  /**
   * The class a field access is made on: the type of the instruction's receiver.
   *
   * <p>The receiver of a field access is the thing the field hangs on, so its type names the class
   * the field belongs to. The class is resolved by {@link FieldOpClassifier#classNameOfType} and
   * {@link FieldOpClassifier#isLikelyClassName} -- the same two the cross-function field tracker
   * uses -- so this key and that one name the same class the same way.
   *
   * <p>A receiver that names no class -- a local the front end could not type, a static field
   * reached through a name that is not a class -- names nothing, and the caller falls back to the
   * field's name alone.
   */
  public static String fieldOwner(TacInstruction ins) {
    if (ins == null) return null;
    TacOperand receiver = ins.lhs1;
    if (!(receiver instanceof TacOperand.TacVar v)) return null;
    String owner = FieldOpClassifier.classNameOfType(v.type());
    if (owner != null && !owner.isEmpty()) return owner;
    // A receiver that names a class -- the IR's "t = this.Helper; t.config = u" shape -- has no
    // type, but its NAME is the class.
    if (FieldOpClassifier.isLikelyClassName(v.name())) return v.name();
    return null;
  }

  /** The field name a key carries, without the class it was qualified with. */
  public static String nameOf(String fieldKey) {
    String s = fieldKey.startsWith("field:") ? fieldKey.substring("field:".length()) : fieldKey;
    int dot = s.lastIndexOf('.');
    return dot < 0 ? s : s.substring(dot + 1);
  }

  public int size() {
    return defs.size() + uses.size();
  }

  /** All field keys that have at least one def. Sorted, so the order does not depend on the map. */
  public Set<String> writtenKeys() {
    return new TreeSet<>(defs.keySet());
  }

  /** All field keys that have at least one use. Sorted, so the order does not depend on the map. */
  public Set<String> readKeys() {
    return new TreeSet<>(uses.keySet());
  }

  public void clear() {
    defs.clear();
    uses.clear();
    usesByName.clear();
  }

  /** For tests / logs: dump a short summary. */
  public String summary() {
    return "FieldDefUseTracker{defs=" + defs.size() + ", uses=" + uses.size() + "}";
  }
}
