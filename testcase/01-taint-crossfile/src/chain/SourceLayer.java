package chain;

import java.io.IOException;
import java.io.InputStream;

/**
 * LAYER 1 / FILE 1 of the cross-file taint chain.
 *
 * <p>This is the ONLY place in the whole chain where the value is attacker-controlled. Everything
 * downstream (files 2..10) receives the value as a parameter or as a field read, so the engine can
 * only report the SQL-injection sink at the end of the chain if it successfully propagates taint
 * through every hop.
 *
 * <p>The source deliberately goes through a non-obvious shape (a reader interface call returning
 * an {@code InputStream}, then a buffer read, then a string construction) so that a purely
 * syntactic "parameter named {@code input}" heuristic does not trivially solve it.
 */
public class SourceLayer {

  /** Real taint origin. {@code getParameter} is a standard servlet entry point. */
  public String readRequestParam(String name) {
    return HttpRequestSupport.getParameter(name);
  }

  /** Stage 1: pull the raw request stream rather than a parsed parameter. */
  public String readRawStream(java.io.InputStream in) throws IOException {
    byte[] buf = new byte[512];
    int n = in.read(buf);
    return new String(buf, 0, Math.max(n, 0));
  }

  /**
   * Stage 1b: source via the request-stream helper. Kept separate from {@link #readRawStream} so the
   * chain has two independent entry points that both converge on the same sink.
   */
  public String readViaSupportStream(InputStream in) {
    return HttpRequestSupport.readAll(in);
  }

  /** Stage 2: wrap the source value in a mutable holder that crosses a file boundary. */
  public Payload wrap(String tainted) {
    Payload p = new Payload();
    p.setData(tainted);
    return p;
  }

  /** Stage 3: a trivial one-hop identity that a call-graph summary must survive. */
  public String passThrough(String v) {
    return TextOps.passthrough(v);
  }
}