package poly;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * LAYER 3a: THE INSTANTIATION-GATED PROBE, NEGATIVE VARIANT.
 *
 * <p>This file declares an interface {@link LateImpl} and a class of the SAME NAME that implements
 * it. The class contains a real, unconditional SQL sink. Crucially, {@code LateImpl} is NEVER
 * instantiated with {@code new} anywhere in this file: it appears only as a declared field type and
 * as a reflective {@code Class.forName} target. There is no points-to evidence for it.
 *
 * <p>A resolver that indexes by simple name plus arity and then filters by "is the declaring class
 * in the receiver's points-to set" must NOT reach {@link LateImpl#handle}, because the receiver's
 * points-to set never contains {@code LateImpl}. That is the documented limitation this fixture
 * measures precisely.
 *
 * <p>Compare against {@link L3bLateImplInstantiated}, which is byte-for-byte the same shape but
 * adds a single {@code new LateImpl(...)}. If a report appears HERE but not THERE, the engine is
 * matching on name alone and ignoring the instantiation gate. If a report appears in neither, the
 * gate holds. If a report appears in both, the gate does not hold and the LIMITATION FINDING itself
 * is the false negative.
 *
 * <p>This file must be scannable on its own: it shares no type with the 3b variant on purpose, so
 * neither file's classes pollute the other's points-to sets.
 */
public class L3aLateImplNotInstantiated {

  /**
   * LAYER 3a fixture: the interface named {@code LateImpl}. It exists only so the dispatch site has
   * a static type to go through.
   *
   * <p>EXPECTED: no reachability anywhere in this file.
   */
  public interface LateImpl {
    /**
     * LAYER 3a: the dispatch target shape.
     *
     * <p>EXPECTED: not called from anywhere in this file.
     */
    void handle(String input);
  }

  /**
   * LAYER 3a fixture: a class whose simple name collides with the interface above, which contains a
   * real SQL sink.
   *
   * <p>EXPECTED: NO report from this class in this file. Nothing ever constructs it, so no
   * receiver's points-to set can contain it.
   */
  public static final class LateImplSink implements LateImpl {

    private final Connection connection;

    /** LAYER 3a helper: constructor exists but is never invoked in this file. */
    public LateImplSink(Connection connection) {
      this.connection = connection;
    }

    /**
     * LAYER 3a: the sink body, unreachable because the class is never instantiated.
     *
     * <p>EXPECTED: no report. This method exists only so a name-matching resolver WOULD have
     * something to wrongly attach to.
     */
    @Override
    public void handle(String input) {
      try {
        Statement statement = connection.createStatement();
        statement.executeQuery("SELECT * FROM late_impl WHERE name = '" + input + "'");
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** LAYER 3a: the declared field type is the interface. No concrete type is ever assigned. */
  private LateImpl plugin;

  /**
   * LAYER 3a helper: registers the plugin. The argument is not constructed here, it is only
   * assigned, so no {@code new} for the sink-bearing class appears in this file.
   *
   * <p>EXPECTED: the assignment adds the concrete type to the points-to set ONLY IF the engine
   * tracks the flow-sensitive fact that the value arrived as a parameter. Nothing is instantiated
   * here either way.
   */
  public void register(LateImpl plugin) {
    this.plugin = plugin;
  }

  /**
   * LAYER 3a TRUE POSITIVE SHAPE, EXPECTED NEGATIVE: dispatch through the interface-typed field
   * after registering an externally supplied implementation.
   *
   * <p>EXPECTED: no report. The only implementation in this file is never constructed, so a
   * points-to-filtered resolver cannot reach {@link LateImplSink#handle}. A resolver that ignores
   * the gate and matches on simple name plus arity alone WILL report here, and that report is the
   * bug this file exists to detect.
   */
  public void dispatchRegisteredPlugin(RequestLike request) {
    String userInput = request.getParameter("name");
    plugin.handle(userInput);
  }

  /**
   * LAYER 3a: the concrete class is loaded reflectively by name. Reflection defeats static
   * instantiation analysis entirely.
   *
   * <p>EXPECTED: no report. A static analyser cannot see that this {@code forName} returns a
   * {@link LateImplSink}, and must not pretend it can.
   */
  public void dispatchReflectivelyLoaded(
      ClassLoader loader, RequestLike request) throws Exception {
    Class<?> loaded = loader.loadClass("poly.L3aLateImplNotInstantiated$LateImplSink");
    String userInput = request.getParameter("name");
    Object instance = loaded.getDeclaredConstructor(Connection.class).newInstance((Connection) null);
    ((LateImpl) instance).handle(userInput);
  }

  /**
   * LAYER 3a: the interface-typed field is dispatched with NO prior registration at all.
   *
   * <p>EXPECTED: no report. The field's points-to set is empty, so there is nothing to filter
   * against.
   */
  public void dispatchWithoutRegistration(RequestLike request) {
    String userInput = request.getParameter("name");
    plugin.handle(userInput);
  }

  /**
   * LAYER 3a: a fresh interface-typed local that is never assigned.
   *
   * <p>EXPECTED: no report. Confirms the engine does not treat an unassigned interface-typed local
   * as "might be anything".
   */
  public void dispatchUnassignedLocal(RequestLike request) {
    String userInput = request.getParameter("name");
    LateImpl unregistered = plugin;
    if (unregistered != null) {
      unregistered.handle(userInput);
    }
  }
}