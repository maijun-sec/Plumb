package app;

import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * A void method has no return value, so there is nothing to check.
 *
 * <p>These methods report failure by THROWING, not by returning a flag:
 *
 * <pre>
 *   public void close() throws IOException
 *   public void commit() throws SQLException
 *   public void flush() throws IOException
 *   public static void delete(Path) throws IOException
 * </pre>
 *
 * <p>A rule that matches on the METHOD NAME alone reports all of them. Each one is a false
 * positive: there is no return value to discard. Failing to handle the exception they throw is
 * CWE-703 (improper check of exceptional conditions) -- a different rule, with a different
 * mechanism.
 *
 * <p>The JDK signatures are the authority here, and the sibling that DOES return a boolean is in
 * the same file so the two cannot be confused: {@code Files.delete(Path)} is void, while {@code
 * Files.deleteIfExists(Path)} returns a boolean.
 */
public class VoidReturnValue {

  private final InputStream input = null;
  private final Writer writer = null;
  private final Connection connection = null;
  private final Statement statement = null;
  private final Path path = null;

  /** void close(): the failure is an IOException, not a return value. */
  public void closeIsVoid() throws Exception {
    input.close();
  }

  /** void commit(): the failure is a SQLException, not a return value. */
  public void commitIsVoid() throws Exception {
    connection.commit();
  }

  /** void flush(): the failure is an IOException, not a return value. */
  public void flushIsVoid() throws Exception {
    writer.flush();
  }

  /** void delete(Path): the failure is an IOException, not a return value. */
  public void deleteIsVoid() throws Exception {
    Files.delete(path);
  }

  /** A void call reached through a chain is still void. */
  public void chainedCloseIsVoid() throws Exception {
    input.close();
  }

  /** The sibling that DOES return a boolean: this one must be reported. */
  public void deleteIfExistsIsChecked() throws Exception {
    Files.deleteIfExists(path);
  }

  /** A user's own void method whose name is on the must-check list. */
  public void userVoidMethod() {
    close();
  }

  private void close() {
    // a local helper that happens to be named 'close'
  }
}
