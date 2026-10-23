package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * LAYER 2: abstract class hierarchy, three levels deep.
 *
 * <p>Probes whether an abstract method declared in a GRANDPARENT is resolved when the receiver's
 * static type is the grandparent. {@link BaseHandler} declares abstract {@code handle(String)},
 * {@link MiddleHandler} extends {@code BaseHandler} without implementing it, and two leaves
 * ({@link AuditLeaf}, {@link QueryLeaf}) supply the implementations.
 *
 * <p>The interesting sub-case is {@link #dispatchAtGrandparentLevel}: the receiver is typed
 * {@code BaseHandler} but every {@code new} in scope is a leaf. A resolver with transitive
 * class-hierarchy closure walks {@code BaseHandler -> MiddleHandler -> QueryLeaf}. A resolver that is
 * only signature lookup plus points-to filtering looks for a method declared by a class actually in
 * the points-to set. The leaves declare {@code handle}, so the leaf bodies are reachable; but
 * {@link MiddleHandler} itself declares no {@code handle}, so any attempt to treat the abstract
 * declaration as a real target is a distinct behaviour worth observing.
 */
public class L2AbstractHierarchy {

  /** LAYER 2 fixture: top of the hierarchy, declares the abstract method. */
  public abstract static class BaseHandler {
    /**
     * LAYER 2: the abstract method, declared two levels above the concrete leaves.
     *
     * <p>EXPECTED: dispatch through a {@code BaseHandler} receiver must reach both leaf
     * implementations, because both are instantiated and both declare {@code handle/1}.
     */
    public abstract void handle(String input);

    /**
     * LAYER 2 helper: a concrete method on the grandparent, so the hierarchy has a member that is
     * not abstract and not overridden anywhere.
     *
     * <p>EXPECTED: no report. Nothing tainted flows here.
     */
    public void describe() {
      System.out.println("handler of type " + getClass().getSimpleName());
    }
  }

  /** LAYER 2 fixture: middle level, abstract, does NOT implement handle. */
  public abstract static class MiddleHandler extends BaseHandler {
    private final String tag;

    /** LAYER 2 helper: constructor takes a tag the leaves can log. */
    protected MiddleHandler(String tag) {
      this.tag = tag;
    }

    /**
     * LAYER 2 helper: a concrete method only the middle level declares.
     *
     * <p>EXPECTED: no report. Confirms a mid-hierarchy method is still callable when the receiver is
     * typed as the middle class.
     */
    public void logTag() {
      System.out.println(tag);
    }
  }

  /** LAYER 2 fixture: leaf that logs, never reaches a sink. */
  public static final class AuditLeaf extends MiddleHandler {
    /** LAYER 2 helper: inherits the tag from the middle level. */
    public AuditLeaf(String tag) {
      super(tag);
    }

    /**
     * LAYER 2 NEGATIVE CONTROL: the leaf implements the grandparent's abstract method but does not
     * sink.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void handle(String input) {
      logTag();
      System.out.println(input);
    }
  }

  /** LAYER 2 fixture: leaf that reaches the SQL sink, the true positive. */
  public static final class QueryLeaf extends MiddleHandler {
    private final Connection connection;

    /** LAYER 2 helper: takes the connection the sink needs. */
    public QueryLeaf(String tag, Connection connection) {
      super(tag);
      this.connection = connection;
    }

    /**
     * LAYER 2 TRUE POSITIVE: implements the method declared in the grandparent, and sinks.
     *
     * <p>EXPECTED: report CWE-89 on {@code executeQuery} when this leaf is dispatched through
     * {@code BaseHandler} or {@code MiddleHandler}.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM ledger WHERE ref = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * LAYER 2 TRUE POSITIVE: the receiver is typed at the GRANDPARENT, the concrete objects are two
   * levels below.
   *
   * <p>EXPECTED: report CWE-89 in {@link QueryLeaf#handle}.
   */
  public void dispatchAtGrandparentLevel(
      Connection connection, RequestLike request) {
    String userInput = request.getParameter("ref");
    BaseHandler handler = new QueryLeaf("q:", connection);
    handler.handle(userInput);
  }

  /**
   * LAYER 2 TRUE POSITIVE: the receiver is typed at the MIDDLE level, one level above the leaf.
   *
   * <p>EXPECTED: report CWE-89 in {@link QueryLeaf#handle}.
   */
  public void dispatchAtMiddleLevel(Connection connection, RequestLike request) {
    String userInput = request.getParameter("ref");
    MiddleHandler handler = new QueryLeaf("q:", connection);
    handler.handle(userInput);
  }

  /**
   * LAYER 2 control: both leaves in the points-to set at one call site, dispatched at the
   * grandparent level.
   *
   * <p>EXPECTED: report CWE-89 in {@link QueryLeaf#handle} only, no report in
   * {@link AuditLeaf#handle}.
   */
  public void dispatchBothLeavesAtGrandparent(
      Connection connection, RequestLike request) {
    String userInput = request.getHeader("X-Ref");
    BaseHandler first = new QueryLeaf("q:", connection);
    BaseHandler second = new AuditLeaf("a:");
    first.handle(userInput);
    second.handle(userInput);
  }

  /**
   * LAYER 2 control: a concrete method that exists only on the middle level, called on a
   * grandparent-typed receiver.
   *
   * <p>EXPECTED: no report. The method is reached statically, but the argument is not tainted and
   * the method does not sink.
   */
  public void callMiddleOnlyMethod(Connection connection) {
    MiddleHandler handler = new QueryLeaf("q:", connection);
    handler.logTag();
    handler.describe();
  }

  /**
   * LAYER 2 TRUE POSITIVE: the leaf is stored in a field before dispatch, so the receiver is a
   * field load rather than a local from a {@code new}.
   *
   * <p>EXPECTED: report CWE-89 in {@link QueryLeaf#handle}. Probes that field-typed receivers
   * contribute to the points-to set at the grandparent type.
   */
  private BaseHandler registered;

  /**
   * LAYER 2 TRUE POSITIVE: register the leaf in a field, then dispatch through the field.
   *
   * <p>EXPECTED: report CWE-89 in {@link QueryLeaf#handle}.
   */
  public void dispatchThroughGrandparentField(
      Connection connection, RequestLike request) {
    String userInput = request.getParameter("ref");
    registered = new QueryLeaf("q:", connection);
    registered.handle(userInput);
  }
}