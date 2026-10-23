package chain;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * LAYER 6 / FILE 9 — the orchestrator.
 *
 * <p>This file contains NO sink and NO source. Its only job is to wire the other eight files
 * together so that a single call site produces a long, realistic chain:
 *
 * <pre>
 *   request param
 *     -> SourceLayer.wrap          (file 1, field store)
 *     -> Payload.readBack          (file 3, field load)
 *     -> TextOps.prefix            (file 5, concat)
 *     -> ChainMidStage.assemble    (file 10, return)
 *     -> CollectionCarrier.addToList (file 4, list store)
 *     -> CollectionCarrier.firstFromList (file 4, list load)
 *     -> Validator.weakValidate    (file 7, fake sanitizer)
 *     -> TextOps.build             (file 5, StringBuilder)
 *     -> SqlSink.queryByName       (file 8, SINK)
 * </pre>
 *
 * <p>A naive checker only sees the last hop and misses everything above it. This file is what makes
 * the chain "cross twenty methods across ten files" measurable.
 */
public class ChainOrchestrator {

  private final CollectionCarrier carrier = new CollectionCarrier();
  private final Validator validator = new Validator();

  /**
   * FULL CHAIN — expected to report CWE-89 at {@link SqlSink#queryByName}.
   *
   * <p>Hop count: 9 methods across 6 files before the sink.
   */
  public void handleFullChain(Connection conn, String paramName) throws SQLException {
    SourceLayer source = new SourceLayer();

    // Hop 1+2: source -> field store -> field load
    String raw = source.readRequestParam(paramName);
    Payload payload = source.wrap(raw);
    String fromField = payload.readBack();

    // Hop 3: string transform
    String prefixed = TextOps.prefix(fromField);

    // Hop 4: cross-file return through a mid-stage object
    ChainMidStage mid = new ChainMidStage();
    String assembled = mid.assemble(prefixed, paramName);

    // Hop 5+6: collection store then load
    carrier.addToList(assembled);
    String fromList = carrier.firstFromList(0);

    // Hop 7: fake sanitizer (must NOT suppress taint)
    String validated = validator.weakValidate(fromList);

    // Hop 8: StringBuilder accumulation
    String finalQuery = TextOps.build(validated);

    // Hop 9: sink
    new SqlSink().queryByName(conn, finalQuery);
  }

  /**
   * SANITIZED CHAIN — expected NOT to report. The value passes through {@link
   * Validator#hardAllowlist} which returns a literal, so taint must be killed before the sink.
   */
  public void handleSanitizedChain(Connection conn, String paramName) throws SQLException {
    SourceLayer source = new SourceLayer();
    String raw = source.readRequestParam(paramName);
    String allowed = new Validator().hardAllowlist(raw);
    new SqlSink().queryByName(conn, allowed);
  }

  /**
   * CHAIN THROUGH A MAP — a different container path than {@link #handleFullChain}, so the two
   * chains fail independently if only one container shape is modelled.
   */
  public void handleMapChain(Connection conn, String paramName) throws SQLException {
    SourceLayer source = new SourceLayer();
    Payload p = source.wrap(source.readRequestParam(paramName));
    carrier.addToMap("k", p.readNested());
    String looked = carrier.lookupMap("k");
    String built = TextOps.buildChained(looked);
    new SqlSink().queryByName(conn, built);
  }

  /**
   * CHAIN THROUGH AN ARRAY — third container shape.
   */
  public void handleArrayChain(Connection conn, String paramName) throws SQLException {
    SourceLayer source = new SourceLayer();
    String[] arr = carrier.buildArray(source.readRequestParam(paramName));
    String el = carrier.readArray(arr, 0);
    new SqlSink().queryByName(conn, TextOps.splitJoin(el));
  }

  /**
   * CHAIN THROUGH AN OBJECT GRAPH — fourth shape, two levels deep.
   */
  public void handleDeepFieldChain(Connection conn, String paramName) throws SQLException {
    SourceLayer source = new SourceLayer();
    Payload inner = source.wrap(source.readRequestParam(paramName));
    Payload outer = new Payload();
    outer.setNested(inner);
    String deep = outer.readDeep();
    new SqlSink().queryByName(conn, deep);
  }

  /**
   * CHAIN THROUGH A RETURN-VALUE SUMMARY — the taint is returned from a getter inside a conditional,
   * so the engine needs a proper CFG merge at the join point.
   */
  public void handleConditionalChain(Connection conn, String paramName, boolean flag)
      throws SQLException {
    SourceLayer source = new SourceLayer();
    RequestContext ctx = new RequestContext(source.readRequestParam(paramName), "t1", null);
    String picked = ctx.getConditional(flag);
    new SqlSink().queryByName(conn, picked);
  }
}