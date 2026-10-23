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
import org.plumb.style.security.DcDeadlockCheck;

/**
 * Coverity <code>DC.DEADLOCK</code> : covers Thread.suspend / resume, ThreadGroup.suspend / resume,
 * Thread.countStackFrames, and ThreadGroup.allowThreadSuspension, plus the negative cases.
 *
 * <p>Like {@link R520CoverityDcDangerousTest}, the checker matches on the receiver's static type so
 * it still flags the methods that have been removed from the JDK (Thread.suspend,
 * ThreadGroup.suspend, etc. were dropped in Java 11/17).
 */
class R521CoverityDcDeadlockTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new DcDeadlockCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnThreadSuspend() {
    String src = "class N { void m(java.lang.Thread t) { t.suspend(); } }";
    assertThat(run(src)).as("DC.DEADLOCK: Thread.suspend() fires (removed from JDK)").isNotEmpty();
  }

  @Test
  void firesOnThreadResume() {
    String src = "class N { void m(java.lang.Thread t) { t.resume(); } }";
    assertThat(run(src)).as("DC.DEADLOCK: Thread.resume() fires (removed from JDK)").isNotEmpty();
  }

  @Test
  void firesOnThreadGroupSuspend() {
    String src = "class N { void m(java.lang.ThreadGroup g) { g.suspend(); } }";
    assertThat(run(src))
        .as("DC.DEADLOCK: ThreadGroup.suspend() fires (removed from JDK)")
        .isNotEmpty();
  }

  @Test
  void firesOnThreadGroupResume() {
    String src = "class N { void m(java.lang.ThreadGroup g) { g.resume(); } }";
    assertThat(run(src))
        .as("DC.DEADLOCK: ThreadGroup.resume() fires (removed from JDK)")
        .isNotEmpty();
  }

  @Test
  void firesOnCountStackFrames() {
    String src = "class N { int m(java.lang.Thread t) { return t.countStackFrames(); } }";
    assertThat(run(src))
        .as("DC.DEADLOCK: Thread.countStackFrames() fires (removed from JDK)")
        .isNotEmpty();
  }

  @Test
  void firesOnAllowThreadSuspension() {
    String src =
        "class N { void m(java.lang.ThreadGroup g) {" + " g.allowThreadSuspension(true);" + "} }";
    assertThat(run(src))
        .as("DC.DEADLOCK: ThreadGroup.allowThreadSuspension(boolean) fires")
        .isNotEmpty();
  }

  @Test
  void noFireOnAllowThreadSuspensionNonBoolean() {
    // A non-boolean argument should suppress the finding.
    String src =
        "class N { void m(java.lang.ThreadGroup g) {"
            + " g.allowThreadSuspension(\"true\");"
            + "} }";
    assertThat(run(src))
        .as("DC.DEADLOCK: non-boolean arg suppresses allowThreadSuspension")
        .isEmpty();
  }

  @Test
  void noFireOnUserClassSuspend() {
    // A user class with a suspend() method should NOT fire :         // the receiver type is not
    // Thread/ThreadGroup.
    String src =
        "class MyWorker { void suspend() {}" + " class N { void m(MyWorker w) { w.suspend(); } } }";
    assertThat(run(src)).as("DC.DEADLOCK: user-defined suspend() does not fire").isEmpty();
  }

  @Test
  void noFireOnSafeApis() {
    String src =
        "class N { void m(java.lang.Thread t) {"
            + " t.start();"
            + " t.join();"
            + " t.interrupt();"
            + "} }";
    assertThat(run(src)).as("DC.DEADLOCK: safe Thread APIs do not fire").isEmpty();
  }
}
