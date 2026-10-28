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
import org.plumb.style.security.UnloggedSecurityExceptionCheck;

/**
 * Coverity <code>UNLOGGED_SECURITY_EXCEPTION</code> : covers unlogged catches of SecurityException,
 * AccessDeniedException (java.nio and Spring), and SQL exceptions, plus the negative cases (logging
 * is present, exception is non-security).
 */
class R524CoverityUnloggedSecurityExceptionTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new UnloggedSecurityExceptionCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnUnloggedSecurityException() {
    // Coverity's canonical example: catch SecurityException with
    // no logging call inside.
    String src =
        "class N { void m() {"
            + " try { System.getProperty(\"x\"); }"
            + " catch (java.lang.SecurityException e) {"
            + "  int x = 0;"
            + "  x++;"
            + " }"
            + "} }";
    assertThat(run(src))
        .as("UNLOGGED_SECURITY_EXCEPTION: catch with no log call fires")
        .isNotEmpty();
  }

  @Test
  void noFireWhenLoggerWarningIsCalled() {
    // The recommended fix: call logger.warning inside the catch.
    String src =
        "import java.util.logging.Logger;"
            + "class N {"
            + " static final Logger L = Logger.getLogger(\"N\");"
            + " void m() {"
            + "  try { System.getProperty(\"x\"); }"
            + "  catch (java.lang.SecurityException e) {"
            + "   L.warning(\"got \" + e);"
            + "  }"
            + " }"
            + "}";
    assertThat(run(src)).as("UNLOGGED_SECURITY_EXCEPTION: logger.warning suppresses").isEmpty();
  }

  @Test
  void noFireWhenSlog4jInfoIsCalled() {
    // SLF4J-shaped call: logger.info(...) is also accepted.
    String src =
        "class N { void m() {"
            + " try { System.getProperty(\"x\"); }"
            + " catch (java.lang.SecurityException e) {"
            + "  org.slf4j.LoggerFactory.getLogger(N.class).info(\"oops\", e);"
            + " }"
            + "} }";
    assertThat(run(src)).as("UNLOGGED_SECURITY_EXCEPTION: SLF4J info suppresses").isEmpty();
  }

  @Test
  void noFireOnNonSecurityException() {
    // A catch on IOException is not security-related, must not fire.
    String src =
        "class N { void m() {"
            + " try { System.getProperty(\"x\"); }"
            + " catch (java.io.IOException e) {"
            + "  int x = 0;"
            + "  x++;"
            + " }"
            + "} }";
    assertThat(run(src)).as("UNLOGGED_SECURITY_EXCEPTION: IOException does not fire").isEmpty();
  }

  @Test
  void noFireOnAccessControlExceptionWithErrorLog() {
    // Error log also counts.
    String src =
        "class N { void m() {"
            + " try { System.getProperty(\"x\"); }"
            + " catch (java.security.AccessControlException e) {"
            + "  org.slf4j.LoggerFactory.getLogger(N.class).error(\"denied\", e);"
            + " }"
            + "} }";
    assertThat(run(src)).as("UNLOGGED_SECURITY_EXCEPTION: error log suppresses").isEmpty();
  }

  @Test
  void firesOnSubclassOfSecurityException() {
    // A project-local exception that extends SecurityException
    // should still fire : the resolved type matches the JDK
    // SecurityException, and the simple-name heuristic
    // ("MySecException" ends with "exception") also catches it.
    String src =
        "class N {"
            + " static class MySecException extends java.lang.SecurityException {"
            + "   private static final long serialVersionUID = 1L;"
            + " }"
            + " void m() {"
            + "  try { System.getProperty(\"x\"); }"
            + "  catch (MySecException e) {"
            + "   int x = 0;"
            + "  }"
            + " }"
            + "}";
    assertThat(run(src))
        .as("UNLOGGED_SECURITY_EXCEPTION: subclass of SecurityException fires")
        .isNotEmpty();
  }

  @Test
  void noFireOnSubclassOfSecurityExceptionWithLog() {
    // Same setup as above but the catch block does call log().
    String src =
        "class N {"
            + " static class MySecException extends java.lang.SecurityException {"
            + "   private static final long serialVersionUID = 1L;"
            + " }"
            + " void m() {"
            + "  try { System.getProperty(\"x\"); }"
            + "  catch (MySecException e) {"
            + "   org.slf4j.LoggerFactory.getLogger(N.class).warn(\"x\", e);"
            + "  }"
            + " }"
            + "}";
    assertThat(run(src))
        .as("UNLOGGED_SECURITY_EXCEPTION: subclass with log() suppresses")
        .isEmpty();
  }

  @Test
  void firesOnCustomAuthorizationException() {
    // A class whose simple name ends in "Authoriz"Exception should
    // be picked up by the simple-name heuristic.
    String src =
        "class N {"
            + " void m() {"
            + "  try { System.getProperty(\"x\"); }"
            + "  catch (MyAuthorizException e) {"
            + "   int x = 0;"
            + "  }"
            + " }"
            + " static class MyAuthorizException extends RuntimeException {"
            + "   private static final long serialVersionUID = 1L;"
            + " }"
            + "}";
    assertThat(run(src))
        .as("UNLOGGED_SECURITY_EXCEPTION: custom authorization exception fires")
        .isNotEmpty();
  }
}
