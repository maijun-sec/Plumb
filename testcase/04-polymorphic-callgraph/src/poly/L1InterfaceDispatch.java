package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * LAYER 1: interface with two concrete implementations, dispatched through the interface type.
 *
 * <p>Probes the baseline virtual-dispatch case. {@code Handler} declares {@code handle(String)}.
 * {@link SqlHandler} forwards its argument straight into a SQL sink (the true positive) and
 * {@link LoggingHandler} does not (the negative control). {@link Factory} returns a {@code new}
 * instance for each of the two implementations, and {@link #dispatchThroughInterface} calls the
 * interface-typed reference returned by the factory.
 *
 * <p>This is the layer the engine must get right before any of the harder layers mean anything: if
 * this does not resolve, every layer below it is measuring noise instead of hierarchy depth.
 *
 * <p>The sink signatures used here ({@code Statement.executeQuery} and
 * {@code Connection.prepareStatement}) are the confirmed {@code sql.sinks} family in this engine.
 */
public class L1InterfaceDispatch {

  /** LAYER 1 fixture: the dispatch target, one method, one String argument. */
  public interface Handler {
    /**
     * LAYER 1: the shared entry point every implementation overrides.
     *
     * <p>EXPECTED: dispatching a {@code Handler} reference must reach {@link SqlHandler#handle} and
     * {@link LoggingHandler#handle} when both are instantiated.
     */
    void handle(String input);
  }

  /** LAYER 1 fixture: implementation that is NOT a true positive, it only logs. */
  public static final class LoggingHandler implements Handler {
    private final String prefix;

    /**
     * LAYER 1 helper: takes a prefix so the field read gives the field-load analysis something real
     * to do.
     */
    public LoggingHandler(String prefix) {
      this.prefix = prefix;
    }

    /**
     * LAYER 1 NEGATIVE CONTROL: the argument never reaches a SQL sink.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void handle(String input) {
      System.out.println(prefix + input);
    }
  }

  /** LAYER 1 fixture: implementation that IS a true positive, its argument reaches the sink. */
  public static final class SqlHandler implements Handler {
    private final Connection connection;

    /** LAYER 1 helper: takes the connection the sink needs. */
    public SqlHandler(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 1 TRUE POSITIVE: the argument is concatenated into SQL and sent to
     * {@code Statement.executeQuery}.
     *
     * <p>EXPECTED: report CWE-89 on the {@code executeQuery} call when reached via a
     * {@code Handler}-typed receiver.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM orders WHERE owner = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** LAYER 1 fixture: returns a {@code new} for each implementation, one per call site. */
  public static final class Factory {

    /**
     * LAYER 1: call site 1. Returns the SQL-sinking implementation.
     *
     * <p>EXPECTED: the points-to set of the result is exactly {@code SqlHandler}.
     */
    public Handler newSqlHandler(Connection connection) {
      return new SqlHandler(connection);
    }

    /**
     * LAYER 1: call site 2. Returns the non-sinking implementation.
     *
     * <p>EXPECTED: the points-to set of the result is exactly {@code LoggingHandler}.
     */
    public Handler newLoggingHandler() {
      return new LoggingHandler("audit: ");
    }
  }

  private final Factory factory = new Factory();

  /**
   * LAYER 1 TRUE POSITIVE: a tainted value is read from the request, handed to the factory-produced
   * {@code Handler}, and dispatched through the interface type.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlHandler#handle}. This requires the engine to link
   * {@code Handler.handle/1} to {@code SqlHandler.handle/1} via the points-to set.
   */
  public void dispatchThroughInterface(Connection connection, RequestLike request) {
    String userInput = request.getParameter("owner");
    Handler handler = factory.newSqlHandler(connection);
    handler.handle(userInput);
  }

  /**
   * LAYER 1 NEGATIVE CONTROL: the same shape, but the factory returns the logging implementation.
   *
   * <p>EXPECTED: no report.
   */
  public void dispatchToNonSqlHandler(RequestLike request) {
    String userInput = request.getParameter("owner");
    Handler handler = factory.newLoggingHandler();
    handler.handle(userInput);
  }

  /**
   * LAYER 1 control: both implementations are live at the same call site, so the receiver's
   * points-to set has two entries.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlHandler#handle} and no report in
   * {@link LoggingHandler#handle}. Precision and recall are both exercised here.
   */
  public void dispatchAcrossBothImplementations(
      Connection connection, RequestLike request, boolean useSql) {
    String userInput = request.getHeader("X-Owner");
    Handler handler = useSql ? factory.newSqlHandler(connection) : factory.newLoggingHandler();
    handler.handle(userInput);
  }

  /**
   * LAYER 1 control: the sink implementation is reached directly, with no polymorphism at all.
   *
   * <p>EXPECTED: report CWE-89. This is the baseline the polymorphic paths must match; if the direct
   * call is missed, the failure is in the sink recognition rather than in dispatch.
   */
  public void directCallBaseline(Connection connection, RequestLike request) {
    String userInput = request.getParameter("owner");
    SqlHandler handler = new SqlHandler(connection);
    handler.handle(userInput);
  }

  /**
   * LAYER 1 control: the receiver is assigned from a {@code Connection} returned by a call rather
   * than passed in as a parameter.
   *
   * <p>EXPECTED: report CWE-89. Probes that points-to initialisation covers a
   * {@code new}-through-call result, not just method parameters.
   */
  public void receiverBuiltFromCall(RequestLike request, Connection source) {
    Connection obtained = source;
    String userInput = request.getParameter("owner");
    Handler handler = new SqlHandler(obtained);
    handler.handle(userInput);
  }

  /**
   * LAYER 1 TRUE POSITIVE: dispatch through the interface type where the sink is
   * {@code Connection#prepareStatement} rather than {@code Statement#executeQuery}, so both
   * confirmed sink signatures are exercised at this layer.
   *
   * <p>EXPECTED: report CWE-89 on the {@code prepareStatement} call.
   */
  public void dispatchToPrepareStatementSink(Connection connection, RequestLike request) {
    String userInput = request.getParameter("owner");
    Handler handler = new SqlHandler(connection);
    handler.handle(userInput);
    try {
      connection.prepareStatement("SELECT * FROM orders WHERE owner = '" + userInput + "'");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}