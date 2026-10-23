package chain;

/**
 * LAYER 2 / FILE 3 — mutable payload holder.
 *
 * <p>Taint must survive a field store + a field load in a *different* method than the one that
 * wrote it. {@code SourceLayer.wrap} stores into {@code data}; the chain later reads it back out in
 * a different file. This is the first hop that requires field-sensitive taint tracking rather than
 * local-variable tracking.
 */
public class Payload {

  private String data;
  private int length;
  private Payload nested;

  /** The tainted field. */
  public void setData(String data) {
    this.data = data;
    this.length = data == null ? 0 : data.length();
  }

  public String getData() {
    return data;
  }

  /** Taint read back out of the field — this is where field taint has to survive the file boundary. */
  public String readBack() {
    return this.data;
  }

  public int getLength() {
    return length;
  }

  /**
   * Nested holder. Forces the taint to go through a second object graph level, so a one-level-deep
   * field analysis loses it here.
   */
  public void setNested(Payload nested) {
    this.nested = nested;
  }

  public String readNested() {
    return nested == null ? null : nested.getData();
  }

  /** Taint through a two-hop object graph: {@code nested.data}. */
  public String readDeep() {
    Payload n = this.nested;
    if (n == null) {
      return null;
    }
    return n.data;
  }
}