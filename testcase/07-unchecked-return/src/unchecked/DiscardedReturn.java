package app;

import java.io.File;
import java.util.List;

/**
 * A discarded return value: the call says "did this work?" and nobody asks.
 *
 * <p>CWE-252 is about a return value that cannot influence anything. The value can be discarded in
 * three shapes, and all three are real defects:
 *
 * <pre>
 *   target.delete();                    // the statement drops the value
 *   boolean ok = target.delete();       // stored, and 'ok' is never read
 *   log(target.delete());               // the logger drops the value
 * </pre>
 *
 * <p>The first shape is the one a name-based rule finds. The other two are more common in real
 * code, and a rule that only sees the first misses them.
 */
public class DiscardedReturn {

  private final File target = null;
  private final List<String> items = null;

  /** The bare statement: the value is dropped by the statement itself. */
  public void bareStatement() {
    target.delete();
  }

  /** The value is stored, and the variable is never read: nothing observes the result. */
  public void assignedButUnread() {
    boolean ok = target.delete();
  }

  /** The value is stored and READ: the caller acts on the result, so this is correct. */
  public void assignedAndRead() {
    boolean ok = target.delete();
    if (ok) {
      System.out.println("deleted");
    }
  }

  /** The value is passed to a logger, which prints it and acts on neither it nor its own result. */
  public void argumentToLogger() {
    log(target.delete());
  }

  /** A collection's boolean says whether the collection changed. */
  public void collectionMutationIgnored() {
    items.add("another");
  }

  /** The result is compared: the value IS observed. */
  public void compared() {
    if (target.delete()) {
      System.out.println("deleted");
    }
  }

  /** The result is returned: the CALLER observes it. */
  public boolean returned() {
    return target.delete();
  }

  private void log(boolean b) {
    System.out.println(b);
  }
}
