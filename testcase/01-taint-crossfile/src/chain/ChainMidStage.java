package chain;

/**
 * LAYER 7 / FILE 10 — mid-stage assembler.
 *
 * <p>Deliberately trivial in logic but placed as an extra file boundary. The value is returned from
 * a method here, which means the engine has to consult a function summary rather than the
 * inlined body. Several taint engines treat a call as taint-opaque; this is the file that catches
 * that.
 */
public class ChainMidStage {

  /** Return the tainted value unchanged after touching a local. */
  public String assemble(String tainted, String key) {
    String local = tainted;
    String decorated = HttpRequestSupport.decorate(local);
    String joined = key + "=" + decorated;
    return joined;
  }

  /** Return tainted through a ternary merge: one branch taint, one branch constant. */
  public String mergeOrDefault(String tainted, boolean useDefault) {
    if (useDefault) {
      return "default-value";
    }
    return tainted;
  }

  /** Return tainted through a loop that appends a constant each iteration. */
  public String loopAppend(String tainted, int rounds) {
    String acc = tainted;
    for (int i = 0; i < rounds; i++) {
      acc = TextOps.suffix(acc);
    }
    return acc;
  }

  /** Return tainted through an exception path: the tainted value is on the stack when thrown. */
  public String viaException(String tainted) {
    try {
      if (tainted.isEmpty()) {
        throw new IllegalStateException("empty");
      }
      return tainted;
    } catch (IllegalStateException e) {
      return tainted;
    }
  }
}