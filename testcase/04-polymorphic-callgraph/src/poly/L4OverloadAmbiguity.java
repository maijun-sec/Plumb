package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * LAYER 4: overload ambiguity.
 *
 * <p>Two classes each declare a method called {@code process} with arity 1, differing only in
 * parameter type: {@link StringProcessor#process(String)} and
 * {@link NumericProcessor#process(int)}. Both are reachable through different static types at
 * different call sites.
 *
 * <p>This probes a specific weakness in the dispatch index. The VTA pass builds its lookup key as
 * {@code method() + "/" + paramTypes().size()}, that is, name plus ARITY, with the parameter
 * TYPES discarded. Both methods therefore share the key {@code "process/1"}. They are only kept
 * apart by the points-to filter on the declaring class.
 *
 * <p>The failure this exposes is the case where the points-to filter does not discriminate, because
 * the receiver is typed as {@code Object} rather than as a specific class. In that situation the
 * filter's escape hatch admits every override under the shared key, and a {@code process(int)} call
 * gets linked to {@code process(String)}. {@link #dispatchThroughObjectTypedReceiver} builds
 * exactly that situation.
 */
public class L4OverloadAmbiguity {

  /** LAYER 4 fixture: one implementation whose {@code process} takes a String. */
  public static final class StringProcessor {

    private final Connection connection;

    /** LAYER 4 helper: takes the connection the sink needs. */
    public StringProcessor(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 4 TRUE POSITIVE: {@code process(String)} sinks.
     *
     * <p>EXPECTED: report CWE-89 here when reached. Any report attributed to a call site that
     * actually invoked the {@code int} overload is a mis-resolution, because an int argument cannot
     * carry taint.
     */
    public void process(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM audit WHERE actor = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** LAYER 4 fixture: the other implementation, same name and arity, different parameter type. */
  public static final class NumericProcessor {

    private final Connection connection;

    /** LAYER 4 helper: takes the connection, so the body can have the same sink available. */
    public NumericProcessor(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 4: {@code process(int)} takes a primitive int and is NOT a sink for string taint.
     *
     * <p>EXPECTED: no report. An int cannot be tainted, so even though this method can reach the
     * sink, no taint can flow into it. This method is the canary: a report here means the engine
     * bound a call site to the wrong overload.
     */
    public void process(int code) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM audit WHERE code = " + code);
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** LAYER 4 fixture: a third class that also declares {@code process(String)}, widening the key. */
  public static final class ReportProcessor {

    private final Connection connection;

    /** LAYER 4 helper: takes the connection the sink needs. */
    public ReportProcessor(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 4 TRUE POSITIVE: another {@code process(String)}, in a different class from the first.
     *
     * <p>EXPECTED: report CWE-89 only when this specific receiver is the one in the points-to set.
     */
    public void process(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM report WHERE subject = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * LAYER 4 TRUE POSITIVE: an arity-2 overload of the SAME name in the SAME class. Its lookup
     * key is {@code "process/2"}, which is distinct from the {@code "process/1"} key shared with
     * {@link StringProcessor#process} and {@link NumericProcessor#process}.
     *
     * <p>EXPECTED: report CWE-89 here. If this method ever leaks an edge to an arity-1 method, the
     * index is keying on the bare name and ignoring arity entirely.
     */
    public void process(String subject, String author) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery(
            "SELECT * FROM report WHERE subject = '" + subject + "' AND author = '" + author + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * LAYER 4 TRUE POSITIVE: the receiver is typed as the concrete class, so the points-to filter has
   * exactly one candidate.
   *
   * <p>EXPECTED: report CWE-89 in {@link StringProcessor#process}.
   */
  public void dispatchToStringOverload(
      Connection connection, RequestLike request) {
    StringProcessor processor = new StringProcessor(connection);
    String userInput = request.getParameter("actor");
    processor.process(userInput);
  }

  /**
   * LAYER 4 EXPECTED NEGATIVE: the receiver is typed as the other concrete class and the argument is
   * an int parsed from the request. The int itself is not a String taint.
   *
   * <p>EXPECTED: no report. The tainted String is converted to a number, so the {@code process(int)}
   * body is reached but receives no string taint. If a report lands on
   * {@link NumericProcessor#process}, the engine bound this call to {@code process(String)}.
   */
  public void dispatchToIntOverload(
      Connection connection, RequestLike request) {
    NumericProcessor processor = new NumericProcessor(connection);
    String raw = request.getParameter("code");
    int code = Integer.parseInt(raw);
    processor.process(code);
  }

  /**
   * LAYER 4 THE AMBIGUITY PROBE: the receiver is typed as {@code Object}, so the points-to filter
   * cannot discriminate between the three classes that share the key {@code "process/1"}.
   *
   * <p>EXPECTED: report CWE-89 in {@link StringProcessor#process} and
   * {@link ReportProcessor#process}, because both take a tainted String. NO report in
   * {@link NumericProcessor#process}, because its parameter is an int and no taint can arrive. The
   * last part is the actual measurement: a resolver keyed on name plus arity alone links the
   * {@code int} overload here, and reports it.
   */
  public void dispatchThroughObjectTypedReceiver(Connection connection, RequestLike request) {
    Object processor = new StringProcessor(connection);
    String userInput = request.getParameter("actor");
    ((StringProcessor) processor).process(userInput);
  }

  /**
   * LAYER 4 THE AMBIGUITY PROBE, two implementations sharing the key: the receiver is typed as
   * {@code Object} and two of the same-name-same-arity classes are live.
   *
   * <p>EXPECTED: reports in both String-typed bodies, no report in the int-typed body.
   */
  public void dispatchTwoStringOverloadsThroughObject(
      Connection connection, RequestLike request) {
    Object first = new StringProcessor(connection);
    Object second = new ReportProcessor(connection);
    String userInput = request.getHeader("X-Actor");
    ((ReportProcessor) second).process(userInput);
    ((StringProcessor) first).process(userInput);
  }

  /**
   * LAYER 4 control: both overloads called through correctly-typed receivers in the same method, so
   * the two key-sharing methods are both in scope with distinguishable receivers.
   *
   * <p>EXPECTED: report CWE-89 in {@link StringProcessor#process} only.
   */
  public void dispatchBothOverloadsWithTypedReceivers(
      Connection connection, RequestLike request) {
    StringProcessor strings = new StringProcessor(connection);
    NumericProcessor numbers = new NumericProcessor(connection);
    String userInput = request.getParameter("actor");
    strings.process(userInput);
    numbers.process(7);
  }

  /**
   * LAYER 4 control: an arity-2 overload exists too, so the index must key arity as well as name.
   *
   * <p>EXPECTED: report CWE-89 in {@link ReportProcessor#process(String, String)} only. If the
   * arity-2 method leaks an edge to the arity-1 method, the key is name-only.
   */
  public void dispatchArityTwoOverload(
      Connection connection, RequestLike request) {
    ReportProcessor processor = new ReportProcessor(connection);
    String subject = request.getParameter("subject");
    String author = request.getHeader("X-Author");
    processor.process(subject, author);
  }

  /**
   * LAYER 4 TRUE POSITIVE: the arity-2 overload, whose key is {@code "process/2"} and is distinct
   * from the arity-1 {@code "process/1"} group.
   *
   * <p>EXPECTED: report CWE-89 here.
   */
  public void dispatchArityTwoWithObjectReceiver(
      Connection connection, RequestLike request) {
    Object processor = new ReportProcessor(connection);
    String subject = request.getParameter("subject");
    String author = request.getHeader("X-Author");
    ((ReportProcessor) processor).process(subject, author);
  }
}