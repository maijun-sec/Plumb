package app;

import java.io.File;

/**
 * Whether an ARGUMENT is discarded is decided by the CALLEE'S BODY.
 *
 * <p>The callee's name cannot answer it: two methods called {@code log} may behave completely
 * differently, and only one of them drops the value it is handed. So the body decides, and the
 * name is only the fallback for a callee the resolver cannot reach.
 */
public class ArgumentToCallee {

  private final File target = null;

  /** The callee never touches its parameter: the value is dropped. */
  public void calleeNeverReadsItsParameter() {
    report(target.delete());
  }

  /** The callee throws when the flag is false: it ACTS on the value, so nothing is dropped. */
  public void calleeActsOnItsParameter() {
    check(target.delete());
  }

  /** The callee is named like a logger but acts on the value: reporting it would be wrong. */
  public void calleeNamedLikeALoggerButActsOnTheValue() {
    log(target.delete());
  }

  /** The callee forwards the value to a logger and does nothing else: the value is dropped. */
  public void calleeForwardsTheValueToALogger() {
    forward(target.delete());
  }

  private void report(boolean deleted) {
    // the parameter is never read
  }

  private void check(boolean deleted) {
    if (!deleted) {
      throw new IllegalStateException("the delete did not happen");
    }
  }

  private void log(boolean deleted) {
    if (!deleted) {
      throw new IllegalStateException("the delete did not happen");
    }
  }

  private void forward(boolean deleted) {
    System.out.println(deleted);
  }
}
