package ptr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LAYER 6: the combined pipeline.
 *
 * <p>Everything the previous layers isolate, stacked into one method: a value returned from a callee,
 * routed through a container, stored on a field of two roots, and then null-checked on one name
 * while dereferenced through another.
 *
 * <p>This is the case that separates a real alias-aware analysis from one that merely tracks
 * assignment inside a single function, because the guard and the dereference are joined through a
 * chain that includes a callee boundary and a container lookup.
 */
public class L6CombinedPipeline {

  /** A two-field holder so field NAME and receiver sensitivity are both exercised. */
  public static class Node {
    public String name;
    public StringBuilder payload;
  }

  /** A root that owns a node, reached through a field. */
  public static class Root {
    public Node node;
  }

  /** Allocates and returns; the caller must alias the result through two names. */
  public StringBuilder makePayload(String seed) {
    return new StringBuilder(seed);
  }

  /**
   * SAFE: the value is null-checked under one name and dereferenced through an alias of that name.
   *
   * <p>The guard constrains the object, not the name, so an alias-aware analysis must stay silent.
   */
  public int guardedAliasAcrossCalleeAndContainer(String input) {
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder viaCallee = makePayload(input);   // crosses a method boundary
    store.put("k", viaCallee);
    StringBuilder viaContainer = store.get("k");    // crosses a container
    if (viaCallee == null) {
      return 0;
    }
    return viaContainer.length();                   // same object: safe here
  }

  /**
   * SAFE: two entries hold genuinely different objects, so the guard on one says nothing about the
   * other — and here the other is guarded on its own terms.
   */
  public int distinctContainerEntriesAreIndependent(String input) {
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder first = makePayload(input);
    StringBuilder second = new StringBuilder("other");
    store.put("a", first);
    store.put("b", second);
    StringBuilder fetched = store.get("b");
    if (fetched == null) {
      return 0;
    }
    return fetched.length();                       // guarded in its own right
  }

  /**
   * UNSAFE: the guard protects one root's node, and the other root's node is dereferenced without
   * any check. Field sensitivity is what keeps these two apart.
   */
  public int unguardedSiblingFieldDereference(String input) {
    Root left = new Root();
    Root right = new Root();
    left.node = new Node();
    right.node = null;                             // only the RIGHT root is null
    if (left.node != null) {
      return left.node.payload.length();            // guarded
    }
    return right.node.payload.length();             // NOT guarded -- real defect
  }

  /**
   * SAFE: one shared node reached through two roots, null-checked through either name.
   */
  public int sharedNodeGuardedThroughOneRoot(String input) {
    Root left = new Root();
    Root right = new Root();
    Node shared = new Node();
    left.node = shared;
    right.node = shared;
    if (left.node == null) {
      return 0;
    }
    return right.node.payload.length();             // same object: safe
  }

  /** SAFE: a list element guarded through the list, then read back through a second alias. */
  public int listElementGuardedThroughOneReference(String input) {
    List<StringBuilder> items = new ArrayList<>();
    StringBuilder element = makePayload(input);
    items.add(element);
    StringBuilder back = items.get(0);
    if (element == null) {
      return 0;
    }
    return back.length();                          // same object: safe
  }

  /**
   * UNSAFE: the guard is on a different list position, so it protects a different element.
   */
  public int listSiblingPositionNotGuarded(String input) {
    List<StringBuilder> items = new ArrayList<>();
    StringBuilder first = makePayload(input);
    StringBuilder second = new StringBuilder("second");
    items.add(first);
    items.add(second);
    StringBuilder other = items.get(1);
    if (first == null) {
      return 0;
    }
    return other.length();                         // NOT guarded -- real defect
  }

  /**
   * SAFE: a value reassigned after the guard is a different object, so the guard does not apply to
   * it — and here the replacement is itself non-null, so nothing dereferences null.
   */
  public int reassignmentAfterGuard(String input) {
    StringBuilder original = makePayload(input);
    StringBuilder same = original;
    if (same == null) {
      return 0;
    }
    StringBuilder replacement = new StringBuilder("replacement");
    same = replacement;                            // `same` now names another object
    return original.length();                       // `original` is still the guarded one
  }
}