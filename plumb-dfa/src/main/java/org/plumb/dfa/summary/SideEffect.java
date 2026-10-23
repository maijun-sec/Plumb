package org.plumb.dfa.summary;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Side-effect description of a method, computed once from the IR and consulted by the engine at
 * every call site.
 *
 * <p>Five buckets are tracked independently:
 *
 * <ul>
 *   <li>{@link #writesThisFields} ? names of {@code this.field} assignments the method performs.
 *       The engine uses this to invalidate caller-side read state.
 *   <li>{@link #writesParamIndices} ? indices of parameters the method may mutate (e.g. by calling
 *       {@code arg.add(...)} on a collection parameter).
 *   <li>{@link #writesStaticFields} ? {@code FQCN.field} keys for static field writes.
 *   <li>{@link #callsMethods} ? callee signatures of every method call in the body (used for
 *       inter-procedural summary chaining).
 *   <li>{@link #mayThrow} ? true if the body contains a {@code THROW} instruction or calls a method
 *       known to throw. The engine uses this to decide whether the call site has a reachable
 *       exception path.
 * </ul>
 *
 * <p>{@link #isPure()} is a fast O(1) check that returns true iff the method performs no writes, no
 * calls, and never throws ? i.e. the engine can freely re-evaluate it without observing state
 * changes.
 */
public final class SideEffect {

  private final Set<String> writesThisFields;
  private final Set<Integer> writesParamIndices;
  private final Set<String> writesStaticFields;
  private final Set<String> callsMethods;
  private final boolean mayThrow;

  public SideEffect(
      Set<String> writesThisFields,
      Set<Integer> writesParamIndices,
      Set<String> writesStaticFields,
      Set<String> callsMethods,
      boolean mayThrow) {
    this.writesThisFields =
        writesThisFields == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(writesThisFields));
    this.writesParamIndices =
        writesParamIndices == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(writesParamIndices));
    this.writesStaticFields =
        writesStaticFields == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(writesStaticFields));
    this.callsMethods =
        callsMethods == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(callsMethods));
    this.mayThrow = mayThrow;
  }

  /** Empty side-effect (pure function). */
  public static SideEffect pure() {
    return new SideEffect(Set.of(), Set.of(), Set.of(), Set.of(), false);
  }

  public Set<String> writesThisFields() {
    return writesThisFields;
  }

  public Set<Integer> writesParamIndices() {
    return writesParamIndices;
  }

  public Set<String> writesStaticFields() {
    return writesStaticFields;
  }

  public Set<String> callsMethods() {
    return callsMethods;
  }

  public boolean mayThrow() {
    return mayThrow;
  }

  public boolean isPure() {
    return writesThisFields.isEmpty()
        && writesParamIndices.isEmpty()
        && writesStaticFields.isEmpty()
        && callsMethods.isEmpty()
        && !mayThrow;
  }

  /**
   * A mutable builder. The {@link MethodSummaryBuilder} constructs one of these as it walks the IR,
   * then closes it into an immutable {@link SideEffect}.
   */
  public static final class Builder {
    private final Set<String> writesThisFields = new LinkedHashSet<>();
    private final Set<Integer> writesParamIndices = new LinkedHashSet<>();
    private final Set<String> writesStaticFields = new LinkedHashSet<>();
    private final Set<String> callsMethods = new LinkedHashSet<>();
    private boolean mayThrow = false;

    public void addWriteThisField(String name) {
      if (name != null) writesThisFields.add(name);
    }

    public void addWriteParamIndex(int idx) {
      writesParamIndices.add(idx);
    }

    public void addWriteStaticField(String key) {
      if (key != null) writesStaticFields.add(key);
    }

    public void addCall(String calleeSig) {
      if (calleeSig != null) callsMethods.add(calleeSig);
    }

    public void markMayThrow() {
      mayThrow = true;
    }

    public boolean isEmpty() {
      return writesThisFields.isEmpty()
          && writesParamIndices.isEmpty()
          && writesStaticFields.isEmpty()
          && callsMethods.isEmpty()
          && !mayThrow;
    }

    public SideEffect build() {
      return new SideEffect(
          writesThisFields, writesParamIndices, writesStaticFields, callsMethods, mayThrow);
    }
  }
}
