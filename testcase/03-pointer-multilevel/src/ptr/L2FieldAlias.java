package ptr;

/**
 * LAYER 2: field sensitivity.
 *
 * <p>The direct precision probe for alias analysis. Two roots of the same class holding two
 * different children must stay apart; a field-insensitive analysis that keys fields by
 * {@code (class, name)} without the receiver conflates them and reports a defect where there is
 * none. This is the control that decides whether the analysis is usable on real object graphs.
 */
public class L2FieldAlias {

  /** A two-field holder, so field NAME sensitivity is testable as well as receiver sensitivity. */
  public static class Node {
    public StringBuilder name;
    public StringBuilder payload;
  }

  /** UNSAFE: the same child object reachable through two roots. */
  public int sharedChildSeenThroughBothRoots() {
    Node left = new Node();
    Node right = new Node();
    StringBuilder shared = new StringBuilder("x");
    left.payload = shared;
    right.payload = shared;
    right.payload.append("!");
    return left.payload.length();          // `shared` grew via the other root
  }

  /** SAFE: the same field on two different roots holds different objects. */
  public int distinctChildrenDoNotAlias() {
    Node left = new Node();
    Node right = new Node();
    left.payload = new StringBuilder("l");
    right.payload = new StringBuilder("r");
    right.payload.append("!");
    return left.payload.length();          // untouched
  }

  /** SAFE: two DIFFERENT fields of the SAME object are distinct slots. */
  public int differentFieldsOfOneObjectDoNotAlias() {
    Node single = new Node();
    single.name = new StringBuilder("n");
    single.payload = new StringBuilder("p");
    single.payload.append("!");
    return single.name.length();            // `name` is a different slot
  }

  /** UNSAFE: a deeper path, one.child.payload. */
  public int twoLevelFieldPath() {
    Node outer = new Node();
    Node inner = new Node();
    StringBuilder leaf = new StringBuilder("leaf");
    inner.payload = leaf;
    outer.name = leaf;
    outer.name.append("+");
    return inner.payload.length();
  }

  /** SAFE: two independent chains. */
  public int independentChainsDoNotAlias() {
    Node outerA = new Node();
    Node innerA = new Node();
    Node outerB = new Node();
    Node innerB = new Node();
    innerA.payload = new StringBuilder("a");
    innerB.payload = new StringBuilder("b");
    innerB.payload.append("!");
    return innerA.payload.length();
  }
}