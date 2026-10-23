package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * LAYER 3b: THE INSTANTIATION-GATED PROBE, POSITIVE VARIANT.
 *
 * <p>Structurally identical to {@link L3aLateImplNotInstantiated}, with exactly one difference: the
 * sink-bearing class IS constructed with {@code new} here. The dispatch site is otherwise the
 * same shape, through an interface-typed receiver.
 *
 * <p>Pairing 3a with 3b isolates the single variable that matters. The engine's VTA pass assigns the
 * destination of a {@code NEW} its concrete ctor type (see the {@code NEW} case in
 * {@code computePointsTo}), so in this file the receiver's points-to set DOES contain the concrete
 * type, and a name-plus-arity resolver filtered by points-to membership can reach the sink.
 *
 * <p>EXPECTED: report CWE-89 in {@link PluginImpl#handle}. The delta between this file and 3a is the
 * measurement. Anything that reports in 3a as well has lost the instantiation gate; anything that
 * fails to report here has failed the gate in the conservative direction and loses real findings.
 *
 * <p>Type names are suffixed differently from 3a on purpose, so that scanning these two files
 * independently cannot leak points-to evidence across them.
 */
public class L3bLateImplInstantiated {

  /** LAYER 3b fixture: the interface the receiver is typed against. */
  public interface Plugin {
    /**
     * LAYER 3b: the dispatch target shape.
     *
     * <p>EXPECTED: called from {@link #dispatchConstructedPlugin}.
     */
    void handle(String input);
  }

  /**
   * LAYER 3b fixture: the concrete implementation, holding a real SQL sink, and constructed with
   * {@code new} in this file.
   *
   * <p>EXPECTED: report CWE-89 in {@link #handle}.
   */
  public static final class PluginImpl implements Plugin {

    private final Connection connection;

    /** LAYER 3b helper: this constructor IS called, unlike its counterpart in 3a. */
    public PluginImpl(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 3b TRUE POSITIVE: the sink body. Reachable now precisely because the class is
     * instantiated somewhere in the file.
     *
     * <p>EXPECTED: report CWE-89 on the {@code executeQuery} call.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM plugin WHERE name = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** LAYER 3b: the interface-typed field, mirroring the 3a layout. */
  private Plugin plugin;

  /**
   * LAYER 3b TRUE POSITIVE: the implementation is constructed here, then dispatched through the
   * interface-typed receiver.
   *
   * <p>EXPECTED: report CWE-89 in {@link PluginImpl#handle}. This is the direct counterpart of
   * {@code L3aLateImplNotInstantiated#dispatchRegisteredPlugin}.
   */
  public void dispatchConstructedPlugin(Connection connection, RequestLike request) {
    plugin = new PluginImpl(connection);
    String userInput = request.getParameter("name");
    plugin.handle(userInput);
  }

  /**
   * LAYER 3b TRUE POSITIVE: constructed into a local and dispatched immediately, no field
   * involved.
   *
   * <p>EXPECTED: report CWE-89 in {@link PluginImpl#handle}.
   */
  public void dispatchFromLocal(Connection connection, RequestLike request) {
    Plugin local = new PluginImpl(connection);
    String userInput = request.getParameter("name");
    local.handle(userInput);
  }

  /**
   * LAYER 3b TRUE POSITIVE: the sink is reached through {@code Connection#prepareStatement} rather
   * than {@code Statement#executeQuery}, confirming both confirmed sink signatures are exercised.
   *
   * <p>EXPECTED: report CWE-89 on the {@code prepareStatement} call.
   */
  public void dispatchThroughPrepareStatement(
      Connection connection, RequestLike request) {
    Plugin local = new PluginImpl(connection);
    String userInput = request.getParameter("name");
    local.handle(userInput);
    try {
      connection.prepareStatement("SELECT * FROM plugin WHERE name = '" + userInput + "'");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * LAYER 3b: register an externally supplied implementation over the constructed one, so the
   * receiver ends up with both a constructed and an unconstructed candidate.
   *
   * <p>EXPECTED: report CWE-89 in {@link PluginImpl#handle}. The constructed instance is enough to
   * justify the edge; the parameter-supplied one adds nothing and must not create a false
   * negative.
   */
  public void dispatchOverRegisteredParameter(
      Connection connection, Plugin external, RequestLike request) {
    this.plugin = new PluginImpl(connection);
    String userInput = request.getParameter("name");
    this.plugin = external;
    this.plugin.handle(userInput);
  }

  /**
   * LAYER 3b: the constructed implementation is dispatched but the input is NOT tainted.
   *
   * <p>EXPECTED: no report. Confirms the layer measures dispatch reachability and not just sink
   * presence.
   */
  public void dispatchCleanInput(Connection connection) {
    Plugin local = new PluginImpl(connection);
    local.handle("constant-value");
  }
}