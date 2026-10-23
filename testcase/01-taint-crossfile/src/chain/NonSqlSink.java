package chain;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Logger;

/**
 * LAYER 5 / FILE 8b — non-SQL sinks.
 *
 * <p>The same chain depth as {@link SqlSink}, but against four different sink families. A taint
 * engine can easily support SQL concatenation while having no support for path traversal or
 * command injection, so these belong in the same category but are reported separately.
 *
 * <ul>
 *   <li>CWE-22 path traversal — {@link #writeByName}
 *   <li>CWE-78 OS command injection — {@link #execByName}
 *   <li>CWE-532 log injection — {@link #logUserInput}
 *   <li>CWE-79 reflected XSS — {@link #renderHtml}
 * </ul>
 */
public class NonSqlSink {

  private static final Logger LOG = Logger.getLogger(NonSqlSink.class.getName());

  /** CWE-22: tainted filename joined onto a base directory. */
  public void writeByName(String baseDir, String fileName) throws IOException {
    File out = new File(baseDir, fileName);
    try (OutputStream os = new FileOutputStream(out)) {
      os.write("data".getBytes());
    }
  }

  /** CWE-22: tainted path passed straight to a File constructor chain. */
  public void copyByPath(String src, String dst) throws IOException {
    File s = new File(src);
    File d = new File(dst);
    if (!d.getCanonicalPath().startsWith("/safe")) {
      try (OutputStream os = new FileOutputStream(d)) {
        os.write(s.getCanonicalPath().getBytes());
      }
    }
  }

  /** CWE-78: tainted argument to a process exec. */
  public void execByName(String program, String arg) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(program, arg).start();
    p.waitFor();
  }

  /** CWE-78: tainted value embedded in a shell command string. */
  public void execViaShell(String cmd) throws IOException, InterruptedException {
    Process p = Runtime.getRuntime().exec("sh -c " + cmd);
    p.waitFor();
  }

  /** CWE-532: tainted value written to a log. */
  public void logUserInput(String userInput) {
    LOG.info("user input: " + userInput);
  }

  /** CWE-79: tainted value reflected into an HTML response. */
  public void renderHtml(String comment, StringBuilder out) {
    out.append("<div>").append(comment).append("</div>");
  }
}