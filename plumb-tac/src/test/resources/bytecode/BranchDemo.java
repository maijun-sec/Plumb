package bytecode;

/**
 * A demo with one branch, used by the bytecode front end's control-flow test.
 *
 * <p>The two returns are the two paths of the branch: {@code 11} is the THEN path (the source's
 * {@code if (x > 0)}) and {@code 22} is the ELSE path. The test tells them apart by the constant
 * each one returns.
 */
public class BranchDemo {

  public static int pick(int x) {
    if (x > 0) {
      return 11;
    }
    return 22;
  }

  /**
   * A shape where the compiler keeps the source's comparison instead of inverting it: for {@code x
   * > 0 || y > 0} the first branch jumps to the THEN path on {@code x > 0}.
   */
  public static int pickOr(int x, int y) {
    if (x > 0 || y > 0) {
      return 11;
    }
    return 22;
  }
}
