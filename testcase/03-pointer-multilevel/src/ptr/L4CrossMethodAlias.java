package ptr;

/**
 * LAYER 4: aliasing across a method boundary.
 *
 * <p>Points-to has to survive a return, or every fact about an object evaporates at the call site.
 * The negative control is the important half: a callee that allocates two objects and returns one
 * of them must not hand the caller both. The previous approximation -- the union of every local in
 * the callee -- got the positive right and the negative wrong, which is worse than getting neither.
 */
public class L4CrossMethodAlias {

  /** Allocates one object and returns it: the case a union-of-locals approximation gets right. */
  public StringBuilder makeOne(String seed) {
    StringBuilder made = new StringBuilder(seed);
    return made;
  }

  /**
   * SAFE, and actually safe: the returned object is aliased in the caller and guarded on one name.
   *
   * <p>R1113: an unguarded {@code same.append(..)} on a freshly returned reference made this a real
   * null dereference while the layer treated it as a cross-method alias measurement. Guarding
   * {@code primary} and mutating {@code same} is precisely the cross-method alias transfer this case
   * is for.
   */
  public int returnedObjectAliasedInCaller(String seed) {
    StringBuilder primary = makeOne(seed);
    StringBuilder same = primary;
    if (primary == null) {
      return 0;
    }
    same.append("-one");
    return primary.length();
  }

  /**
   * Allocates two objects and returns only the first. The caller must receive exactly one, so
   * mutating the second must not be visible through the returned reference.
   */
  public StringBuilder makeTwoReturnFirst() {
    StringBuilder kept = new StringBuilder("kept");
    StringBuilder dropped = new StringBuilder("dropped");
    dropped.append("-internal");
    return kept;
  }

  /** SAFE, and actually safe: the unreturned allocation must not leak into the caller. */
  public int unreturnedAllocationDoesNotLeak() {
    StringBuilder got = makeTwoReturnFirst();
    if (got == null) {
      return 0;
    }
    got.append("-caller");
    // `got` is the kept object only; nothing the callee did to `dropped` is observable here.
    return got.length();
  }

  /** UNSAFE: a value passed in, stored in a field, and mutated through the field. */
  public int parameterStoredInFieldThenMutated(StringBuilder incoming) {
    this.held = incoming;
    if (incoming == null) {
      return 0;
    }
    this.held.append("-field");
    return incoming.length();
  }

  /** SAFE: a parameter stored in a field and then replaced, leaving the caller's object alone. */
  public int parameterStoredThenReplaced(StringBuilder incoming) {
    this.held = incoming;
    this.held = new StringBuilder("replacement");
    this.held.append("!");
    return incoming.length();
  }

  private StringBuilder held;
}