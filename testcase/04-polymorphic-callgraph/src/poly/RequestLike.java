package poly;

/**
 * Minimal stand-in for {@code javax.servlet.http.HttpServletRequest}.
 *
 * <h2>Why this exists</h2>
 *
 * The fixtures in this category need a realistic untrusted-input boundary. The obvious choice is the
 * servlet API, but {@code javax.servlet.*} is not part of the JDK, so any fixture referencing it
 * cannot be compiled standalone by {@code javac} without adding a servlet-api jar to the classpath.
 * A testcase that only runs when an extra dependency is wired up is a testcase that silently rots.
 *
 * <p>This interface declares only the two methods the fixtures actually call,
 * {@link #getParameter(String)} and {@link #getHeader(String)}, which is the standard
 * taint-source shape in the Plumb rule set. It lives in the {@code poly} package, so the fixtures
 * use {@code RequestLike} instead of {@code javax.servlet.http.HttpServletRequest}.
 *
 * <p>If you are porting a fixture from real servlet code, replacing this with the genuine
 * {@code HttpServletRequest} should not change any result: only the two method names matter to the
 * engine, not the declaring type.
 */
public interface RequestLike {

  /**
   * A request parameter. This is the canonical untrusted source and the entry point of every
   * taint chain in this category.
   *
   * @param name parameter name
   * @return the raw, attacker-controlled value
   */
  String getParameter(String name);

  /**
   * A request header. The second most common untrusted source; {@code User-Agent} and {@code Referer}
   * are the classic Log4Shell vectors and {@code X-Owner} is used here for the same reason.
   *
   * @param name header name
   * @return the raw, attacker-controlled value
   */
  String getHeader(String name);
}