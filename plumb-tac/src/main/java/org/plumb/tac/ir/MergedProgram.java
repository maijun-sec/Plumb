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
package org.plumb.tac.ir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * A cross-TU {@link TacProgram} that combines multiple compilation units (one per .java file) into
 * a single analysis unit.
 *
 * <p>For the cross-TU taint analysis, the {@code ScanRunner} collects all parsed sources,
 * translates each to a {@link TacProgram}, and merges them. The merged program is functionally
 * equivalent to running each program separately for analysis purposes, but the engine sees a single
 * {@link org.plumb.dfa.engine.DfaEngine} view, so calls from one file's function to another file's
 * function are resolved and recursively analyzed just like intra-TU calls.
 *
 * <p>Function signature collisions across TUs are handled by prefixing the type name: a function in
 * {@code com.foo.A} named {@code m} becomes {@code com.foo.A.m(...)} in the merged program,
 * distinct from {@code com.bar.B.m(...)}.
 */
public final class MergedProgram implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  private final List<TacProgram> sources;
  private final Map<String, TacFunction> functions = new LinkedHashMap<>();
  private final Map<String, String> lambdaBindings = new java.util.HashMap<>();
  private final Map<String, Path> functionSourcePath = new java.util.HashMap<>();
  // Reverse index from a function's bare signature
  // (e.g. "action(String):void") to its merged key (e.g.
  // "CWE78_81_bad :: action(String):void"). The engine
  // asks for a source path by signature in
  // AnalysisContext.sourcePathForFunction; without this
  // index the lookup would miss the merged key and fall
  // back to the synthetic "merged.java" path, losing the
  // real per-class file. The index also records the FIRST
  // merged key observed for each signature (insertion
  // order from rebuild), so the lookup is deterministic
  // when two same-signature methods on different classes
  // exist in the same program.
  private final Map<String, String> signatureToMergedKey = new java.util.LinkedHashMap<>();

  public MergedProgram(List<TacProgram> sources) {
    this.sources = List.copyOf(Objects.requireNonNull(sources));
    rebuild();
  }

  private void rebuild() {
    functions.clear();
    lambdaBindings.clear();
    functionSourcePath.clear();
    signatureToMergedKey.clear();
    for (TacProgram src : sources) {
      // Lambda bindings: translate var names through the
      // merged prefix so they don't collide with another
      // file's same-named var.
      String prefix = src.typeName() == null ? "" : src.typeName() + "$";
      for (var e : src.lambdaBindings().entrySet()) {
        String newKey = prefix + e.getKey();
        // The lambda function name is already unique
        // (per-enclosing-scope counter); keep it as-is.
        lambdaBindings.put(newKey, e.getValue());
      }
      // Use the same merged key (signature +
      // enclosing class) as {@link #asProgram()} so that
      // functions with identical signatures but different
      // declaring classes (e.g. the Juliet "flow 81+"
      // `bad.action(String)` and `goodG2B.action(String)`
      // siblings) all survive the merge. The
      // prior signature-based key was last-writer-wins,
      // which silently dropped every implementor after the
      // first: a single "good" implementor would then be the
      // only candidate left for the CHA name+arity match, so
      // the "bad" sink vanished from the analysis and the
      // report could fire against the wrong file.
      for (TacFunction fn : src.functions()) {
        String key = mergedKey(fn);
        functions.put(key, fn);
        functionSourcePath.put(key, src.sourcePath());
        // First-write-wins for the signature → merged
        // key reverse index. Insertion order is
        // deterministic (rebuild iterates sources in
        // declaration order), so the same signature
        // always maps to the same merged key for the
        // duration of this analysis pass.
        signatureToMergedKey.putIfAbsent(fn.signature(), key);
      }
    }
  }

  public List<TacProgram> sources() {
    return Collections.unmodifiableList(sources);
  }

  public List<TacFunction> functions() {
    return Collections.unmodifiableList(new ArrayList<>(functions.values()));
  }

  public TacFunction function(String signature) {
    return functions.get(signature);
  }

  /**
   * Look up a function by its merged key (enclosing class + signature). The earlier {@link
   * #function(String)} collapses same-signature methods on different classes to a single entry;
   * merged callers (e.g. the engine's call resolver looking up a class implementor) use the merged
   * key directly so the right variant is returned.
   */
  public TacFunction functionByMergedKey(String mergedKey) {
    return functions.get(mergedKey);
  }

  public String lambdaFor(String varName) {
    return lambdaBindings.get(varName);
  }

  public Map<String, String> lambdaBindings() {
    return Collections.unmodifiableMap(lambdaBindings);
  }

  public Path sourcePathFor(String functionSig) {
    // The functionSourcePath map is now keyed by
    // merged key (enclosingClass + signature). Look up the
    // signature first, then fall back to the reverse
    // signature → merged key index that rebuild() built.
    Path p = functionSourcePath.get(functionSig);
    if (p != null) return p;
    String mergedKey = signatureToMergedKey.get(functionSig);
    if (mergedKey != null) {
      return functionSourcePath.get(mergedKey);
    }
    return null;
  }

  /** Convenience: number of source files merged. */
  public int sourceCount() {
    return sources.size();
  }

  /**
   * A view that implements the {@link TacProgram}-shaped API the engine expects. Returns a
   * synthetic {@link TacProgram} that delegates {@code functions()}, {@code lambdaFor()}, etc. to
   * the merged state. The synthetic program has no real {@code sourcePath} (use {@link
   * #sourcePathFor(String)} per function).
   */
  public TacProgram asProgram() {
    TacProgram stub = new TacProgram(null, "<merged>");
    // Use a signature+enclosingClass key so that
    // same-named methods on different classes in different
    // .java files (e.g. the Juliet CWE-78/89/36 "flow 81+"
    // family: a `bad` subclass and a `goodG2B` subclass of
    // the same abstract base both implement the abstract
    // `action(String)` method with the same signature) don't
    // collide in the stub's last-writer-wins map. With a
    // signature-only key the merge kept only the second-added
    // implementation, and the engine's taint report would then
    // fire against the wrong file (false positive) or miss the
    // "bad" sink entirely (false negative). The fix keys the stub
    // map on signature + enclosing class so both variants survive
    // the merge and a resolver can tell them apart by their
    // declaring type.
    int stubFnCount = 0;
    for (TacFunction fn : functions.values()) {
      String key = mergedKey(fn);
      stub.addFunctionByKey(key, fn);
      stubFnCount++;
    }
    for (var e : lambdaBindings.entrySet()) {
      stub.bindLambda(e.getKey(), e.getValue());
    }
    // Aggregate the per-file class hierarchy so the tables are complete across
    // compilation units.
    //
    // NOTE: no analysis code reads these tables today. The CHA strategy
    // (org.plumb.dfa.cg.ChaCallGraphStrategy) resolves a virtual call by matching the
    // callee's SIMPLE NAME and arity against the functions in the program -- it does not
    // walk the extends/abstract-method edges. The cross-TU abstract-base shape (one file
    // declares an abstract base, another declares a concrete subclass) therefore resolves
    // only because the subclass's method name matches, not because the hierarchy says so.
    // The tables are still aggregated here so that a hierarchy-aware resolver can be added
    // without having to revisit the merge, and so the per-file and merged views agree.
    //
    // The {@code registerClassParent} call is idempotent (putIfAbsent), so re-aggregating on
    // every {@code asProgram} call is safe.
    for (TacProgram src : sources) {
      for (var e : src.classToParent().entrySet()) {
        stub.registerClassParent(e.getKey(), e.getValue());
      }
    }
    // Aggregate the per-file FIELD annotation tables the same way, so a
    // request bean declared in one .java file is still visible to an
    // endpoint declared in another. Without this, the container-binding
    // shape (@RestBeanParam Parameters params, with @RestQuery on
    // Parameters.customer) only resolves when the bean and the endpoint
    // happen to share a compilation unit.
    for (TacProgram src : sources) {
      for (var classEntry : src.classFieldAnnotations().entrySet()) {
        for (var fieldEntry : classEntry.getValue().entrySet()) {
          stub.addClassFieldAnnotations(
              classEntry.getKey(), fieldEntry.getKey(), fieldEntry.getValue());
        }
      }
    }
    return stub;
  }

  /**
   * Produce a unique key for a {@link TacFunction} in the merged stub. For a function whose
   * enclosing class is known, the key is {@code "<enclosingClass> :: <signature>"}; for a top-level
   * function (e.g. {@code bad()} in 81a) the key is just the signature. Two functions with the same
   * signature but different enclosing classes (e.g. {@code CWE78_81_bad.action(String):void} and
   * {@code CWE78_81_goodG2B.action(String):void}) therefore keep separate entries.
   */
  private static String mergedKey(final TacFunction fn) {
    if (fn.enclosingClass() == null || fn.enclosingClass().isEmpty()) {
      return fn.signature();
    }
    return fn.enclosingClass() + " :: " + fn.signature();
  }
}
