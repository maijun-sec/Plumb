package chain;

/**
 * LAYER 3 / FILE 6 — wrapper objects that return tainted values.
 *
 * <p>The taint now has to survive a *returned* object from a constructor, then a getter on that
 * object, then a field read inside the getter. Return-value summaries are where many engines drop
 * taint, so this file exists specifically to probe that.
 */
public class RequestContext {

  private final String user;
  private final String tenant;
  private final Payload payload;

  public RequestContext(String user, String tenant, Payload payload) {
    this.user = user;
    this.tenant = tenant;
    this.payload = payload;
  }

  /** Taint returned through a plain getter. */
  public String getUser() {
    return user;
  }

  /** Taint returned through a getter that reads another object's field. */
  public String getTenant() {
    return this.tenant;
  }

  /** Taint returned from a nested object. */
  public String getPayloadData() {
    return payload == null ? null : payload.getData();
  }

  /** Taint returned from a two-level-deep object graph. */
  public String getDeepData() {
    return payload == null ? null : payload.readDeep();
  }

  /**
   * Taint returned from inside a conditional. This is the case that requires a proper merge at the
   * CFG join: one branch returns the tainted field, the other returns a constant.
   */
  public String getConditional(boolean flag) {
    if (flag) {
      return this.user;
    }
    return "anonymous";
  }

  /** Taint returned through a loop accumulator. */
  public String accumulate(int times) {
    String acc = "";
    for (int i = 0; i < times; i++) {
      acc = TextOps.prefix(acc);
    }
    return TextOps.prefix(acc);
  }
}