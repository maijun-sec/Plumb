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
package org.plumb.dfa.types;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.CalleeKeyNormalizer;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The type each callee in the program RETURNS.
 *
 * <p><b>Why the analysis needs this.</b> A call produces a value, and the value's type is a
 * property of the CALLEE's declaration: a function that says it returns an int returns an int. The
 * body of the callee cannot say it -- the body only says what was returned, and the engine follows
 * a value, not a declaration. So without this index the result of every call in the program is an
 * untyped value, and a sink that needs a string cannot tell an int from a string.
 *
 * <p><b>Why it is an index and not a lookup per call.</b> A scan asks the same question at every
 * call site of a function, and the answer does not change while the program does. The index is
 * built once per program and read many times.
 *
 * <p><b>Why a key is only answered when every candidate agrees.</b> The IR names a callee by {@code
 * owner.method/arity} -- the arity is in the key and the PARAMETER TYPES are not. Two overloads of
 * the same method therefore share one key, and two classes may declare a method of the same name. A
 * key whose candidates disagree about what they return is not evidence of anything: the call site
 * cannot tell which one it called, so the analysis knows nothing about the value it produced. Such
 * a key is dropped, and the call's result stays untyped.
 *
 * <p><b>Why there are two keys.</b> The canonical key keeps the owner, and it is the precise one.
 * The bare key drops the owner, and it is coarser: it can join a call whose owner the front end
 * spelled one way with a declaration whose owner it spelled another way. It is also the key that
 * two unrelated methods collide on, which is why it is only consulted when the canonical key says
 * nothing, and why the same "every candidate agrees" rule applies to it.
 *
 * <p><b>What is NOT here.</b> The parameter types of the callee. Those are in the IR ({@code
 * TacInstruction.calleeParamTypes}) and they are deliberately not used to type a caller's argument:
 * in the languages that fill that field the compiler already guarantees the argument's type, so it
 * says nothing the analysis did not know, and in a language where an annotation is not enforced it
 * would be a claim, not a fact.
 */
public final class CalleeReturnTypes {

  /** The type of every key whose candidates agree, by the canonical key. */
  private final Map<String, TacType> byCanonicalKey;

  /** The type of every key whose candidates agree, by the bare key. */
  private final Map<String, TacType> byBareKey;

  private CalleeReturnTypes(Map<String, TacType> byCanonicalKey, Map<String, TacType> byBareKey) {
    this.byCanonicalKey = byCanonicalKey;
    this.byBareKey = byBareKey;
  }

  /**
   * Build the index for a program.
   *
   * @param program the program, or null
   * @return the index; empty when the program is null
   */
  public static CalleeReturnTypes of(TacProgram program) {
    Map<String, TacType> canonical = new HashMap<>();
    Map<String, TacType> bare = new HashMap<>();
    Set<String> ambiguousCanonical = new HashSet<>();
    Set<String> ambiguousBare = new HashSet<>();
    if (program != null) {
      for (TacFunction fn : program.functions()) {
        if (fn == null) continue;
        TacType type = fn.returnType();
        if (type == null || type.kind() == TacType.Kind.UNKNOWN) continue;
        String signature = fn.signature();
        String canonicalKey = CalleeKeyNormalizer.canonicalCalleeKey(signature);
        if (canonicalKey != null) {
          record(canonical, ambiguousCanonical, canonicalKey, type);
        }
        String bareKey = CalleeKeyNormalizer.bareCalleeKey(signature);
        if (bareKey != null) {
          record(bare, ambiguousBare, bareKey, type);
        }
      }
    }
    return new CalleeReturnTypes(Map.copyOf(canonical), Map.copyOf(bare));
  }

  /**
   * Record a type under a key, unless the key already carries a different one.
   *
   * <p>A key that has already been found ambiguous stays ambiguous: the second function that
   * disagrees is not evidence that the first one was wrong.
   *
   * @param agreed the keys whose candidates agree
   * @param ambiguous the keys whose candidates do not
   * @param key the key
   * @param type the type the function returns
   */
  private static void record(
      Map<String, TacType> agreed, Set<String> ambiguous, String key, TacType type) {
    if (ambiguous.contains(key)) return;
    TacType existing = agreed.get(key);
    if (existing == null) {
      agreed.put(key, type);
      return;
    }
    if (!existing.equals(type)) {
      agreed.remove(key);
      ambiguous.add(key);
    }
  }

  /**
   * The type the callee of a call returns.
   *
   * @param calleeSig the call's callee signature
   * @return the type, or null when the analysis does not know it
   */
  public TacType returnTypeOf(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return null;
    String canonicalKey = CalleeKeyNormalizer.canonicalCalleeKey(calleeSig);
    TacType type = canonicalKey == null ? null : byCanonicalKey.get(canonicalKey);
    if (type == null) {
      String bareKey = CalleeKeyNormalizer.bareCalleeKey(calleeSig);
      type = bareKey == null ? null : byBareKey.get(bareKey);
    }
    if (type == null) return null;
    // A function that returns nothing says nothing about the value its call
    // produced, and a front end that did not fill in a return type says
    // VOID for every function -- which is no evidence at all.
    if (type.kind() == TacType.Kind.VOID) return null;
    return type;
  }

  /**
   * How many keys the index answers.
   *
   * @return the number of keys, canonical and bare together
   */
  public int size() {
    return byCanonicalKey.size() + byBareKey.size();
  }
}
