package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * LAYER 6: sink in only one subclass, dispatched from a generic container.
 *
 * <p>A {@code List<Handler>} holds mixed implementations, is iterated, and each element is
 * dispatched through the interface type. This stacks a container on top of polymorphism: the
 * receiver is not a named variable holding a {@code new}, it is whatever an enhanced-for loop
 * produces.
 *
 * <p>Of the implementations in the list, only {@link SqlBackedHandler} reaches a SQL sink. The rest
 * are negative controls placed in the same container so that a report on any of them is a
 * measurable false positive rather than an ambiguous outcome.
 *
 * <p>This layer is where the two weakest points of the analysis interact. First, the element
 * variable of a for-each loop is populated by an iterator, not by a {@code NEW} instruction, so it
 * may carry no useful points-to information at all. Second, when a call result is assigned, the VTA
 * pass falls back to treating the destination as {@code java.lang.Object}. Either situation pushes
 * the points-to filter toward its {@code OBJECT} escape hatch, which admits EVERY override sharing
 * the method name and arity. That is precisely why {@link Handler#handle} is called here and by
 * {@link L1InterfaceDispatch}, {@link L5DiamondInheritance} and the rest: this is the case most
 * likely to produce spurious edges to unrelated implementations, and the false-positive rate here is
 * the headline measurement for the container-plus-polymorphism combination.
 */
public class L6CollectionPolymorphism {

  /** LAYER 6 fixture: the element type of the container. */
  public interface Handler {
    /**
     * LAYER 6: the dispatched method, called on each element of the list.
     *
     * <p>EXPECTED: reachable on the implementations that are added to the list.
     */
    void handle(String input);
  }

  /** LAYER 6 fixture: the only implementation in this file that reaches a SQL sink. */
  public static final class SqlBackedHandler implements Handler {
    private final Connection connection;

    /** LAYER 6 helper: takes the connection the sink needs. */
    public SqlBackedHandler(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 6 TRUE POSITIVE: the single sink body in this file.
     *
     * <p>EXPECTED: report CWE-89 here when reached from the loop.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM queue WHERE label = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** LAYER 6 fixture: negative control that only formats. */
  public static final class FormattingHandler implements Handler {
    /**
     * LAYER 6: builds a display string but never queries the database.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void handle(String input) {
      System.out.println("[" + input + "]");
    }
  }

  /** LAYER 6 fixture: negative control that only counts characters. */
  public static final class CountingHandler implements Handler {
    /**
     * LAYER 6: inspects the input's length without touching SQL.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void handle(String input) {
      System.out.println(input.length());
    }
  }

  /** LAYER 6 fixture: negative control whose handle name is deliberately the same arity. */
  public static final class DroppingHandler implements Handler {
    /**
     * LAYER 6: discards its input entirely.
     *
     * <p>EXPECTED: no report. Present so that a resolver matching name and arity has several
     * same-key candidates to choose wrongly between.
     */
    @Override
    public void handle(String input) {
      input.length();
    }
  }

  /**
   * LAYER 6 TRUE POSITIVE: a list holding exactly one implementation, the sink-bearing one,
   * dispatched from an enhanced-for loop.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle}. The simplest container case:
   * one element, one target.
   */
  public void dispatchSingleElementFromList(Connection connection, RequestLike request) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new SqlBackedHandler(connection));
    String userInput = request.getParameter("label");
    for (Handler handler : handlers) {
      handler.handle(userInput);
    }
  }

  /**
   * LAYER 6 TRUE POSITIVE, PRECISION PROBE: the list mixes the sink-bearing implementation with
   * three negative controls, all sharing the method name and arity.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle} and NO report in the other three.
   * This is the false-positive measurement for container-plus-polymorphism: four same-key
   * candidates, one real target.
   */
  public void dispatchMixedList(Connection connection, RequestLike request) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new FormattingHandler());
    handlers.add(new CountingHandler());
    handlers.add(new SqlBackedHandler(connection));
    handlers.add(new DroppingHandler());
    String userInput = request.getParameter("label");
    for (Handler handler : handlers) {
      handler.handle(userInput);
    }
  }

  /**
   * LAYER 6 TRUE POSITIVE, INDEXED ACCESS: the same mixed list, dispatched by index rather than by
   * iterator.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle} only. The two loop forms are
   * deliberately different so that a failure of one and a success of the other is visible.
   */
  public void dispatchMixedListByIndex(Connection connection, RequestLike request) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new SqlBackedHandler(connection));
    handlers.add(new FormattingHandler());
    String userInput = request.getHeader("X-Label");
    for (int i = 0; i < handlers.size(); i++) {
      handlers.get(i).handle(userInput);
    }
  }

  /**
   * LAYER 6 TRUE POSITIVE, LIST STORED IN A FIELD: the container is an instance field, and the
   * element type is the interface.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle}. Field-typed containers add a
   * field-load step on top of the array-load step.
   */
  private List<Handler> registry = new ArrayList<>();

  /**
   * LAYER 6 TRUE POSITIVE: populate the field-backed container and dispatch from it.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle}.
   */
  public void dispatchFromFieldBackedList(
      Connection connection, RequestLike request) {
    registry.add(new SqlBackedHandler(connection));
    registry.add(new FormattingHandler());
    String userInput = request.getParameter("label");
    for (Handler handler : registry) {
      handler.handle(userInput);
    }
  }

  /**
   * LAYER 6 TRUE POSITIVE, NESTED CONTAINER: the handlers sit inside a wrapper object, which is
   * what the VTA pass types as {@code Object} after a call result, so this also exercises the
   * {@code OBJECT} escape hatch in the points-to filter.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle} and no report in the controls.
   */
  public void dispatchThroughWrapperObject(
      Connection connection, RequestLike request) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new SqlBackedHandler(connection));
    handlers.add(new CountingHandler());
    String userInput = request.getParameter("label");
    dispatchAll(buildWrapper(handlers).members(), userInput);
  }

  /** LAYER 6 fixture: a wrapper that merely holds the container. */
  public static final class HandlerGroup {
    private final List<Handler> members;

    /** LAYER 6 helper: stores the container. */
    public HandlerGroup(List<Handler> members) {
      this.members = members;
    }

    /**
     * LAYER 6: hands the container back to the caller.
     *
     * <p>EXPECTED: no report. The container leaves this method through a call boundary, which is
     * what forces the fallback typing.
     */
    public List<Handler> members() {
      return members;
    }
  }

  /** LAYER 6 helper: builds the wrapper, so the container passes through a call boundary. */
  public static HandlerGroup buildWrapper(List<Handler> members) {
    return new HandlerGroup(members);
  }

  /**
   * LAYER 6 helper: iterates whatever container it is handed.
   *
   * <p>EXPECTED: no report here in isolation. The parameter's declared type is the interface, and
   * no implementation is constructed in this method, so the report must be attributed at the call
   * site that supplies the tainted input.
   */
  public static void dispatchAll(List<Handler> handlers, String input) {
    for (Handler handler : handlers) {
      handler.handle(input);
    }
  }

  /**
   * LAYER 6 EXPECTED NEGATIVE: the container holds only negative controls, so there is no sink at
   * all on this path.
   *
   * <p>EXPECTED: no report anywhere. This is the control that proves any report in the mixed cases
   * comes from the sink-bearing element rather than from the container mechanism itself.
   */
  public void dispatchControlsOnly(RequestLike request) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new FormattingHandler());
    handlers.add(new CountingHandler());
    handlers.add(new DroppingHandler());
    String userInput = request.getParameter("label");
    for (Handler handler : handlers) {
      handler.handle(userInput);
    }
  }

  /**
   * LAYER 6 EXPECTED NEGATIVE: the sink-bearing implementation is in the container, but the input
   * is not tainted.
   *
   * <p>EXPECTED: no report. Isolates dispatch reachability from taint reachability: the edge
   * exists, but nothing flows along it.
   */
  public void dispatchWithCleanInput(Connection connection) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new SqlBackedHandler(connection));
    for (Handler handler : handlers) {
      handler.handle("literal");
    }
  }

  /**
   * LAYER 6 TRUE POSITIVE, DISPATCH THROUGH A CALL RESULT: the element variable is produced by
   * {@code Iterator.next()}, whose result type the analysis falls back to {@code Object} for.
   *
   * <p>EXPECTED: report CWE-89 in {@link SqlBackedHandler#handle}. This is the sharpest case in the
   * whole suite: an explicit iterator loop forces the {@code OBJECT} fallback on the receiver, so
   * every {@code handle/1} in the program becomes a candidate.
   */
  public void dispatchViaExplicitIterator(Connection connection, RequestLike request) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(new SqlBackedHandler(connection));
    handlers.add(new FormattingHandler());
    String userInput = request.getParameter("label");
    java.util.Iterator<Handler> iterator = handlers.iterator();
    while (iterator.hasNext()) {
      Handler handler = iterator.next();
      handler.handle(userInput);
    }
  }
}