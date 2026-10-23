package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * LAYER 5: diamond inheritance.
 *
 * <p>{@link Top} is the root interface. {@link Left} and {@link Right} both extend it.
 * {@link DiamondImpl} implements both. The dispatch site is typed {@code Top}, so the concrete type
 * is reachable by two distinct inheritance paths from the static type of the receiver.
 *
 * <p>Probes whether the engine handles multiple paths to the same concrete type without producing
 * duplicate or spurious edges. This engine has NO transitive class-hierarchy closure, so the
 * hierarchy itself does no work here: resolution succeeds only because {@link DiamondImpl} is
 * instantiated and its simple name matches the dispatched method. The diamond therefore cannot
 * rescue an engine that ignores points-to, but it also must not cause the engine to emit the edge
 * twice or to lose it because the type is reachable twice.
 *
 * <p>The secondary question is whether a type reachable only via the interfaces, with no
 * instantiation, leaks in. {@link NeverInstantiatedImpl} implements {@code Top} through both
 * branches and is never constructed.
 */
public class L5DiamondInheritance {

  /** LAYER 5 fixture: the root of the diamond. */
  public interface Top {
    /**
     * LAYER 5: the method dispatched through the diamond root.
     *
     * <p>EXPECTED: reachable in {@link DiamondImpl}.
     */
    void handle(String input);

    /**
     * LAYER 5: a second method, so the diamond is probed with more than one dispatched name.
     *
     * <p>EXPECTED: reachable in {@link DiamondImpl}.
     */
    void audit(String input);
  }

  /** LAYER 5 fixture: the left branch of the diamond. */
  public interface Left extends Top {
    /**
     * LAYER 5: declared on the left branch, inherited by the concrete type.
     *
     * <p>EXPECTED: no report; this is a declaration only, it contains no code.
     */
    void leftMarker();
  }

  /** LAYER 5 fixture: the right branch of the diamond. */
  public interface Right extends Top {
    /**
     * LAYER 5: declared on the right branch, inherited by the concrete type.
     *
     * <p>EXPECTED: no report; this is a declaration only, it contains no code.
     */
    void rightMarker();
  }

  /** LAYER 5 fixture: the bottom of the diamond, reached by two paths from {@link Top}. */
  public static final class DiamondImpl implements Left, Right {

    private final Connection connection;

    /** LAYER 5 helper: takes the connection the sink needs. */
    public DiamondImpl(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 5 TRUE POSITIVE: implements the root's method and sinks.
     *
     * <p>EXPECTED: report CWE-89 here, exactly once, when dispatched through a {@code Top}
     * receiver.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM diamond WHERE name = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * LAYER 5: the second method on the root, which does NOT sink.
     *
     * <p>EXPECTED: no report. Confirms the diamond is resolved per-method rather than wholesale
     * per-type.
     */
    @Override
    public void audit(String input) {
      System.out.println("audit: " + input);
    }

    /**
     * LAYER 5: the left branch's method, an arity-0 method unique to this branch.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void leftMarker() {
      System.out.println("left");
    }

    /**
     * LAYER 5: the right branch's method, an arity-0 method unique to this branch.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void rightMarker() {
      System.out.println("right");
    }
  }

  /**
   * LAYER 5 fixture: a second type in the same diamond shape that is never instantiated.
   *
   * <p>EXPECTED: no report. Its sink must stay unreachable, because nothing constructs it.
   */
  public static final class NeverInstantiatedImpl implements Left, Right {

    private final Connection connection;

    /** LAYER 5 helper: never called from anywhere. */
    public NeverInstantiatedImpl(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 5: an unreachable sink body. If this reports, the diamond's interface paths are being
     * walked as if they were concrete types.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM ghost WHERE name = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * LAYER 5: the second unreachable method.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void audit(String input) {
      System.out.println(input);
    }

    /**
     * LAYER 5: the unreachable left branch method.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void leftMarker() {}

    /**
     * LAYER 5: the unreachable right branch method.
     *
     * <p>EXPECTED: no report.
     */
    @Override
    public void rightMarker() {}
  }

  /**
   * LAYER 5 TRUE POSITIVE: dispatch through the ROOT of the diamond, where the concrete type sits
   * two inheritance hops away.
   *
   * <p>EXPECTED: report CWE-89 in {@link DiamondImpl#handle}, once.
   */
  public void dispatchThroughRoot(Connection connection, RequestLike request) {
    Top service = new DiamondImpl(connection);
    String userInput = request.getParameter("name");
    service.handle(userInput);
  }

  /**
   * LAYER 5 TRUE POSITIVE: dispatch through one BRANCH of the diamond.
   *
   * <p>EXPECTED: report CWE-89 in {@link DiamondImpl#handle}. The points-to set is unchanged from
   * the root case; only the declared type differs.
   */
  public void dispatchThroughLeftBranch(Connection connection, RequestLike request) {
    Left service = new DiamondImpl(connection);
    String userInput = request.getParameter("name");
    service.handle(userInput);
  }

  /**
   * LAYER 5 TRUE POSITIVE: dispatch through the OTHER branch, the second path to the same type.
   *
   * <p>EXPECTED: report CWE-89 in {@link DiamondImpl#handle}. If this and
   * {@link #dispatchThroughLeftBranch} behave differently, path-sensitivity is leaking into the
   * points-to set.
   */
  public void dispatchThroughRightBranch(Connection connection, RequestLike request) {
    Right service = new DiamondImpl(connection);
    String userInput = request.getParameter("name");
    service.handle(userInput);
  }

  /**
   * LAYER 5 TRUE POSITIVE: both branches dispatch at the same call site, so the concrete type is
   * live under two distinct static types.
   *
   * <p>EXPECTED: two reports, one per call site, both in {@link DiamondImpl#handle}. Not four, not
   * one. Duplicate edges along the two diamond paths are the specific defect this probes.
   */
  public void dispatchThroughBothBranches(Connection connection, RequestLike request) {
    String userInput = request.getParameter("name");
    Left viaLeft = new DiamondImpl(connection);
    Right viaRight = new DiamondImpl(connection);
    viaLeft.handle(userInput);
    viaRight.handle(userInput);
  }

  /**
   * LAYER 5 EXPECTED NEGATIVE: dispatch through a branch-typed receiver that holds an
   * externally supplied implementation.
   *
   * <p>EXPECTED: no report. The only implementation declared in this file is never constructed, so
   * nothing in the points-to set matches.
   */
  public void dispatchBranchWithExternalImplementation(
      RequestLike request, Left external) {
    String userInput = request.getParameter("name");
    external.handle(userInput);
  }

  /**
   * LAYER 5 control: call the branch-specific arity-0 method on the concrete type directly.
   *
   * <p>EXPECTED: no report. These exist so the diamond has members other than the shared
   * {@code handle}, which would otherwise make it impossible to tell whether resolution is
   * happening per-method or per-type.
   */
  public void callBranchSpecificMethods(Connection connection) {
    DiamondImpl impl = new DiamondImpl(connection);
    impl.leftMarker();
    impl.rightMarker();
    impl.audit("constant");
  }
}