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
package org.plumb.dfa.points;

import java.util.List;
import java.util.Objects;

/**
 * The heap abstraction: an identity for "the object a reference might point at".
 *
 * <p>Modelled on Qilin's PAG nodes and heap abstractors (ECOOP'22, He/Lu/Xue), reduced to what
 * Plumb's {@code TacProgram} can express. An abstract object is a {@code (context, allocation site,
 * type)} triple, so two {@code new FileInputStream(..)} calls in different contexts are
 * distinguishable while a single one is not confused with anything else.
 *
 * <h2>Why allocation-site identity rather than type</h2>
 *
 * <p>A type-only points-to set (what {@code VtaCallGraphStrategy.computePointsTo} builds) is enough
 * to resolve a virtual call target but useless for aliasing: every {@code InputStream} points at
 * "InputStream". Every consumer of this analysis asks <em>may these two references be the same
 * object</em>, which needs allocation-site identity.
 *
 * <h2>Field and array abstraction</h2>
 *
 * <p>A field access does not produce a new heap object; it produces an <em>access path</em> into an
 * existing one, modelled by {@link AbstractField} and {@link AbstractArrayIndex}. Aliasing through
 * a field is then a question about paths, not about a second object.
 */
public final class AbstractObject {

  /** An abstract heap object. */
  public static final class Obj {
    /** {@code <context>|<function>#<instructionIndex>} — the allocation site. */
    private final String site;

    /** Declared (or constructed) type, FQN where known. */
    private final String type;

    /** The context the allocation happened in; empty for the trivial analysis. */
    private final Context context;

    private Obj(String site, String type, Context context) {
      this.site = Objects.requireNonNull(site, "site");
      this.type = type == null ? "java.lang.Object" : type;
      this.context = context == null ? Context.EMPTY : context;
    }

    public static Obj at(String functionSignature, int instructionIndex, String type) {
      return new Obj(functionSignature + "#" + instructionIndex, type, Context.EMPTY);
    }

    public static Obj at(
        String functionSignature, int instructionIndex, String type, Context context) {
      return new Obj(functionSignature + "#" + instructionIndex, type, context);
    }

    public String site() {
      return site;
    }

    public String type() {
      return type;
    }

    public Context context() {
      return context;
    }

    /** {@code site.type@context} — the human-readable form used in findings. */
    public String label() {
      return type + "@" + site + (context.isEmpty() ? "" : ":" + context.label());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Obj other)) return false;
      return site.equals(other.site) && type.equals(other.type) && context.equals(other.context);
    }

    @Override
    public int hashCode() {
      return Objects.hash(site, type, context);
    }

    @Override
    public String toString() {
      return label();
    }
  }

  /**
   * A reference to a field of an abstract object: {@code receiver.field}.
   *
   * <p>This is not a heap object of its own — it is a named slot inside one. Two fields of the same
   * object are always distinct; the same field of two different objects is distinct because the
   * receivers are distinct.
   */
  public static final class AbstractField {
    private final Obj receiver;
    private final String fieldName;

    public AbstractField(Obj receiver, String fieldName) {
      this.receiver = Objects.requireNonNull(receiver, "receiver");
      this.fieldName = Objects.requireNonNull(fieldName, "fieldName");
    }

    public Obj receiver() {
      return receiver;
    }

    public String fieldName() {
      return fieldName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AbstractField other)) return false;
      return receiver.equals(other.receiver) && fieldName.equals(other.fieldName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(receiver, fieldName);
    }

    @Override
    public String toString() {
      return receiver.label() + "." + fieldName;
    }
  }

  /**
   * A reference to one array cell: {@code receiver[index]}.
   *
   * <p>A literal index keeps {@code arr[0]} and {@code arr[1]} distinct, which is what makes "two
   * elements of the same list" separable. A computed index degrades to {@link #ANY}, and any two
   * {@code ANY} cells of the same array alias — sound, and the reason a loop-indexed access cannot
   * be proven distinct.
   */
  public static final class AbstractArrayIndex {
    private final Obj array;
    private final Object index;

    private AbstractArrayIndex(Obj array, Object index) {
      this.array = Objects.requireNonNull(array, "array");
      this.index = index;
    }

    public static AbstractArrayIndex of(Obj array, int index) {
      return new AbstractArrayIndex(array, index);
    }

    public static AbstractArrayIndex any(Obj array) {
      return new AbstractArrayIndex(array, ANY);
    }

    public Obj array() {
      return array;
    }

    public boolean isAny() {
      return index == ANY;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AbstractArrayIndex other)) return false;
      if (!array.equals(other.array)) return false;
      // ANY matches everything: an unknown index may be the same cell as a known one, and
      // claiming otherwise would be unsound.
      return isAny() || other.isAny() || index.equals(other.index);
    }

    @Override
    public int hashCode() {
      // Constant so that ANY-hashed and literal-hashed cells land in the same bucket; equals()
      // above decides.
      return isAny() ? 0 : index.hashCode();
    }

    @Override
    public String toString() {
      return array.label() + "[" + (isAny() ? "*" : index) + "]";
    }

    /** Sentinel for an unknown / computed index. */
    public static final Object ANY =
        new Object() {
          @Override
          public String toString() {
            return "*";
          }
        };
  }

  /**
   * The context an abstract object or variable lives in.
   *
   * <p>Qilin's central idea is that context sensitivity is a <em>parameter</em> rather than a fixed
   * choice: a context constructor decides what a method's context is, and a selector decides which
   * elements of it flow into a variable's or object's context. Plumb keeps the shape but ships one
   * policy for now:
   *
   * <ul>
   *   <li>{@link Policy#CONTEXT_INSENSITIVE} — one context per allocation site (call-site
   *       insensitivity)
   *   <li>{@link Policy#CALL_SITE} — context is the caller plus the call index, so the same method
   *       called from two places gets two contexts (1-CFA)
   *   <li>{@link Policy#OBJECT} — context additionally includes the receiver's abstract object
   *       (k-OBJ)
   * </ul>
   *
   * <p>An empty context is the identity for the insensitive policy; the other policies append
   * elements to it.
   */
  public static final class Context {
    private static final Context EMPTY = new Context(List.of());

    private final List<String> elements;

    private Context(List<String> elements) {
      this.elements = List.copyOf(elements);
    }

    public static Context empty() {
      return EMPTY;
    }

    public static Context of(String... parts) {
      if (parts == null || parts.length == 0) return EMPTY;
      return new Context(List.of(parts));
    }

    public List<String> elements() {
      return elements;
    }

    public boolean isEmpty() {
      return elements.isEmpty();
    }

    /** Append one element, producing the context for a callee or a derived object. */
    public Context append(String element) {
      if (element == null || element.isEmpty()) return this;
      java.util.ArrayList<String> next = new java.util.ArrayList<>(elements);
      next.add(element);
      return new Context(next);
    }

    public String label() {
      return String.join("|", elements);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Context c && elements.equals(c.elements);
    }

    @Override
    public int hashCode() {
      return elements.hashCode();
    }

    @Override
    public String toString() {
      return label();
    }
  }

  /** Which context-sensitivity policy the solver applies. */
  public enum Policy {
    /**
     * One context per allocation site.
     *
     * <p>Cheapest, and what the engine's callgraph strategies assume. Confluses values returned to
     * different callers, which is exactly the confusion that leaks taint between unrelated call
     * paths.
     */
    CONTEXT_INSENSITIVE,
    /**
     * 1-CFA: the context is (caller, call index).
     *
     * <p>The coarsest abstraction that still keeps two invocations from merging, and therefore the
     * default for the engine: it is what stops a helper called from two places with two different
     * arguments from returning a merged result that carries both provenances.
     */
    CALL_SITE,
    /** k-OBJ: the context also includes the receiver's abstract object. */
    OBJECT;

    public boolean isContextSensitive() {
      return this != CONTEXT_INSENSITIVE;
    }

    /**
     * The engine's default policy.
     *
     * <p>{@link #CALL_SITE}: chosen over {@link #CONTEXT_INSENSITIVE} because insensitivity merges
     * two invocations of the same method into one formal and one return set, and every consumer of
     * the alias map (null-dereference, taint propagation, sanitiser reach) inherits that merge.
     * Chosen over {@link #OBJECT} because receiver discrimination multiplies contexts on
     * polymorphic call sites, and the marginal precision is not worth the cost on ordinary code.
     */
    public static Policy defaultPolicy() {
      return CALL_SITE;
    }

    /**
     * Parse a policy name from configuration.
     *
     * <p>Accepts the enum spellings plus the short YAML forms. An unrecognised value falls back to
     * {@link #defaultPolicy()} rather than throwing: a typo in a tuning file must not take down a
     * scan, and the fallback is the engine default anyway.
     */
    public static Policy fromName(String raw) {
      if (raw == null || raw.isBlank()) return defaultPolicy();
      String n = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
      return switch (n) {
        case "CONTEXT_INSENSITIVE", "INSENS", "INSENSITIVE", "CI" -> CONTEXT_INSENSITIVE;
        case "CALL_SITE", "CALLSITE", "CFA", "1CFA", "1_CFA", "KCFA" -> CALL_SITE;
        case "OBJECT", "KOBJ", "OBJ", "K_OBJ" -> OBJECT;
        default -> defaultPolicy();
      };
    }
  }
}
