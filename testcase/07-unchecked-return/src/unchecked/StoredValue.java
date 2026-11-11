package app;

import java.io.File;

/**
 * Whether a STORED value is observed is decided by the local that HOLDS it.
 *
 * <p>The holder is the variable the value is stored in, and only that variable decides: a
 * declaration can hold several variables at once, and the one that holds the value is the one that
 * matters. The way the value is spelled on the way in -- negated, parenthesised, assigned rather
 * than declared -- does not change what it is.
 */
public class StoredValue {

  private final File target = null;

  /** The value is negated on the way into the local, and the local is never read. */
  public void negatedIntoAnUnreadLocal() {
    boolean ok = !target.delete();
  }

  /** The same shape, but the local IS read: the value is observed. */
  public void negatedIntoAReadLocal() {
    boolean ok = !target.delete();
    if (ok) {
      System.out.println("deleted");
    }
  }

  /** Two declarators: the value is in the FIRST one, and the first one IS read. */
  public void theValueIsInTheReadDeclarator() {
    boolean first = target.delete(), second = true;
    if (first) {
      System.out.println("deleted");
    }
  }

  /** Two declarators: the value is in the SECOND one, and the second one is never read. */
  public void theValueIsInTheUnreadDeclarator() {
    boolean first = true, second = target.delete();
  }

  /** A plain assignment to a local that is never read. */
  public void assignedToAnUnreadLocal() {
    boolean ok;
    ok = target.delete();
  }

  /** A plain assignment to a local that IS read. */
  public void assignedToAReadLocal() {
    boolean ok;
    ok = target.delete();
    if (ok) {
      System.out.println("deleted");
    }
  }
}
