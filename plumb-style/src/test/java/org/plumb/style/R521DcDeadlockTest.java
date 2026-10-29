/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
 * the DC.DEADLOCK defect class : covers Thread.suspend / resume, ThreadGroup.suspend / resume,
 * Thread.countStackFrames, and ThreadGroup.allowThreadSuspension, plus the negative cases.
 *
 * <p>Like {@link R520DcDangerousTest}, the checker matches on the receiver's static type so it
 * still flags the methods that have been removed from the JDK (Thread.suspend, ThreadGroup.suspend,
 * etc. were dropped in Java 11/17).
 */
class R521DcDeadlockTest {

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
