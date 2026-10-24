import java.io.IOException;
import java.io.InputStream;

/**
 * R140 — small no-package demo class used as a known-shape smoke target for the Jimple→TAC
 * converter. The class is intentionally minimal (no package) so SootUp's classpath resolution
 * (which expects a {@code com.foo.Bar.class} at {@code <root>/com/foo/Bar.class}) lines up with a
 * simple {@code R140Demo.class} file at the scan root, and the simple class name {@code R140Demo}
 * is what the test asserts.
 *
 * <p>The class demonstrates:
 *
 * <ul>
 *   <li>Field load/store (R127 — the test asserts FIELDSTORE is emitted for the {@code secret}
 *       field assignment).
 *   <li>Method calls with a receiver (R115 — {@code sb.append("...")}).
 *   <li>Object construction ({@code new StringBuilder()}).
 *   <li>Return value with an int binop (R99 identity protection for {@code +} on locals).
 *   <li>Identity setup for {@code this} (instance method on a non-static class).
 *   <li>Static method call ({@code String.valueOf}).
 * </ul>
 *
 * <p>NB: this file is not compiled by the project — it is shipped in {@code src/test/resources} so
 * {@code BytecodeAnalyzerTest} can compile it to a .class on the fly and scan the result.
 */
public class R140Demo {

  private String secret = "default";

  public String getSecret() {
    return secret;
  }

  public void setSecret(String value) {
    // direct field assignment — R127's translator fix
    this.secret = value;
  }

  public int doubleIt(int n) {
    return n * 2;
  }

  public String buildMessage(String name) {
    StringBuilder sb = new StringBuilder();
    sb.append("Hello, ");
    sb.append(name);
    return sb.toString();
  }

  public static int add(int a, int b) {
    return a + b;
  }

  public static String asString(int n) {
    return String.valueOf(n);
  }

  public static void main(String[] args) throws IOException {
    InputStream in = System.in;
    in.read();
  }
}
