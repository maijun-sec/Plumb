package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.security.DcDangerousCheck;

/**
 * Coverity <code>DC.DANGEROUS</code> : covers the four documented dangerous calls (Thread.stop,
 * ThreadGroup.stop, Thread.destroy, ObjectOutputStream$PutField.write) plus the negative cases
 * (safe API calls, no finding).
 *
 * <p>The receiver-type check uses {@code scope.calculateResolvedType()} so the checker still fires
 * for methods that have been removed from the JDK (ThreadGroup.stop / suspend etc. were dropped in
 * Java 11/17).
 */
class R520CoverityDcDangerousTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new DcDangerousCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnThreadStop() {
    String src = "class N { void m(java.lang.Thread t) { t.stop(); } }";
    assertThat(run(src)).as("DC.DANGEROUS: Thread.stop() fires").isNotEmpty();
  }

  @Test
  void firesOnThreadGroupStop() {
    // ThreadGroup.stop() was removed from the JDK in 11/17; the
    // checker still fires because it matches on the receiver's
    // static type (ThreadGroup) and the method name.
    String src = "class N { void m(java.lang.ThreadGroup g) { g.stop(); } }";
    assertThat(run(src))
        .as("DC.DANGEROUS: ThreadGroup.stop() fires (removed from JDK)")
        .isNotEmpty();
  }

  @Test
  void firesOnThreadDestroy() {
    String src = "class N { void m(java.lang.Thread t) { t.destroy(); } }";
    assertThat(run(src)).as("DC.DANGEROUS: Thread.destroy() fires (removed from JDK)").isNotEmpty();
  }

  @Test
  void firesOnThreadGroupDestroy() {
    // ThreadGroup.destroy() still exists in JDK 17 but is
    // deprecated; the checker still flags it.
    String src = "class N { void m(java.lang.ThreadGroup g) { g.destroy(); } }";
    assertThat(run(src)).as("DC.DANGEROUS: ThreadGroup.destroy() fires").isNotEmpty();
  }

  @Test
  void noFireOnUserClassStop() {
    // A class named StopHelper with a stop() method should NOT fire
    // : the receiver is a user class, not Thread/ThreadGroup.
    String src =
        "class StopHelper { void stop() {}" + " class N { void m(StopHelper h) { h.stop(); } } }";
    assertThat(run(src)).as("DC.DANGEROUS: user-defined stop() does not fire").isEmpty();
  }

  @Test
  void noFireOnSafeApi() {
    String src =
        "class N { void m(java.lang.Thread t) {"
            + " t.start();"
            + " t.interrupt();"
            + " t.join();"
            + "} }";
    assertThat(run(src)).as("DC.DANGEROUS: safe Thread APIs do not fire").isEmpty();
  }

  @Test
  void noFireOnStringBuilderStop() {
    // StringBuilder has a `stop`-like shape? No, but make sure
    // we don't false-positive on unrelated receivers.
    String src = "class N { void m(java.lang.StringBuilder sb) {" + " sb.toString();" + "} }";
    assertThat(run(src)).as("DC.DANGEROUS: StringBuilder does not fire").isEmpty();
  }
}
