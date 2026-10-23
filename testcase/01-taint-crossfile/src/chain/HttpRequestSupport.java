package chain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * LAYER 1 / FILE 2 — support code for the source layer.
 *
 * <p>These wrappers exist so the taint has to survive an extra indirection before it even leaves
 * the first file. {@code readAll} in particular converts a stream into a String via a
 * {@code ByteArrayOutputStream}, which is a classic place for taint tracking to drop a value.
 */
public final class HttpRequestSupport {

  private HttpRequestSupport() {}

  /** Stand-in for {@code HttpServletRequest#getParameter}. */
  public static String getParameter(String name) {
    return "req:" + name;
  }

  /**
   * Read a whole stream into a String. The value passes through a byte array and then
   * {@code new String(byte[])} — two conversion steps that a naive propagation model loses.
   */
  public static String readAll(InputStream in) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[256];
      int read;
      while ((read = in.read(chunk)) != -1) {
        out.write(chunk, 0, read);
      }
      return new String(out.toByteArray());
    } catch (IOException e) {
      return "";
    }
  }

  /** A second source shape: parameter concatenated into a larger string. */
  public static String decorate(String raw) {
    return "[" + raw + "]";
  }
}