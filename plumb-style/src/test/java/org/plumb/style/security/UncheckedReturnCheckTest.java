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
/*
 * CWE-252: unchecked return value -- the discarded-value shapes.
 */
package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * A return value that cannot influence anything is a discarded return value.
 *
 * <p>CWE-252 is about a method whose return value says "did this work?" being thrown away. The
 * value can be discarded in three shapes, and all three are real defects:
 *
 * <pre>
 *   f.delete();                          // the statement drops the value
 *   boolean ok = f.delete();             // stored, and 'ok' is never read
 *   log(f.delete());                     // the outer call drops the value
 * </pre>
 *
 * <p>Before this rule was rebuilt, only the FIRST shape was detected, and it was detected on the
 * METHOD NAME alone. Both halves were wrong:
 *
 * <ul>
 *   <li>It fired on a {@code void} method. {@code InputStream.close()}, {@code
 *       Connection.commit()}, {@code Writer.flush()}, {@code Files.delete(Path)} and {@code
 *       Socket.getInputStream().close()} are all void: they report failure by THROWING, not by
 *       returning a flag. There is no return value to check, so every one of them was a false
 *       positive.
 *   <li>It missed the two shapes above that are MORE common in real code than the bare statement.
 * </ul>
 *
 * <p>The tests use the PRODUCTION parser, because the rule needs a symbol resolver to tell a void
 * call from one that returns a flag. A parser without a resolver cannot answer that question at
 * all, and the rule then has to assume the call returns something.
 */
class UncheckedReturnCheckTest {

  private final AstChecker check = new UncheckedReturnCheck();

  @TempDir Path tmp;

  private int count(String src) throws Exception {
    Path file = tmp.resolve("Test.java");
    Files.writeString(file, src);
    ParsedSource ps = new JavaSourceParser().parse(file);
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    new org.plumb.style.AstDispatcher().register(check).run(ctx);
    int n = 0;
    for (Finding f : ctx.findings()) {
      if ("ast-security-unchecked-return".equals(f.ruleId())) n++;
    }
    return n;
  }

  // ---------- shape 1: the bare statement ----------

  @Test
  void fileDeleteStatement_fires() throws Exception {
    assertThat(count("class T { void m(java.io.File f) { f.delete(); } }")).isEqualTo(1);
  }

  @Test
  void theFindingNamesTheCwe() throws Exception {
    Path file = tmp.resolve("Cwe.java");
    Files.writeString(file, "class T { void m(java.io.File f) { f.delete(); } }");
    ParsedSource ps = new JavaSourceParser().parse(file);
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    new org.plumb.style.AstDispatcher().register(check).run(ctx);
    Finding finding = null;
    for (Finding f : ctx.findings()) {
      if ("ast-security-unchecked-return".equals(f.ruleId())) finding = f;
    }
    assertThat(finding).isNotNull();
    assertThat(finding.cwe()).isEqualTo("CWE-252");
  }

  // ---------- shape 2: assigned, and never read ----------

  @Test
  void anAssignedButUnreadReturnIsDiscarded() throws Exception {
    // 'ok' is never read, so nothing observes the deletion's result.
    assertThat(count("class T { void m(java.io.File f) { boolean ok = f.delete(); } }"))
        .isEqualTo(1);
  }

  @Test
  void anAssignedAndReadReturnIsNotDiscarded() throws Exception {
    // 'ok' IS read, so the value is observed.
    assertThat(count("class T { void m(java.io.File f) { boolean ok = f.delete(); if (ok) { } } }"))
        .isZero();
  }

  @Test
  void anAssignedReturnReadLaterIsNotDiscarded() throws Exception {
    assertThat(
            count(
                "class T { void m(java.io.File f) { boolean ok = f.delete(); use(ok); } "
                    + "void use(boolean b) { } }"))
        .isZero();
  }

  @Test
  void anAssignedReturnThatIsReturnedIsNotDiscarded() throws Exception {
    assertThat(
            count("class T { boolean m(java.io.File f) { boolean ok = f.delete(); return ok; } }"))
        .isZero();
  }

  // ---------- shape 3: passed to a call that ignores it ----------

  @Test
  void aReturnPassedToALoggingCallIsDiscarded() throws Exception {
    // The logger prints the value and acts on neither it nor its own result.
    assertThat(
            count(
                "class T { void m(java.io.File f) { log(f.delete()); } void log(boolean b) { } }"))
        .isEqualTo(1);
  }

  @Test
  void aReturnPassedToPrintlnIsDiscarded() throws Exception {
    assertThat(count("class T { void m(java.io.File f) { System.out.println(f.delete()); } }"))
        .isEqualTo(1);
  }

  @Test
  void aReturnPassedToACallThatUsesItIsNotDiscarded() throws Exception {
    // String.valueOf's own result depends on the argument, so the value is observed.
    assertThat(count("class T { void m(java.io.File f) { String.valueOf(f.delete()); } }"))
        .isZero();
  }

  // ---------- the value IS used ----------

  @Test
  void comparedReturn_doesNotFire() throws Exception {
    assertThat(count("class T { void m(java.io.File f) { if (f.delete()) { } } }")).isZero();
  }

  @Test
  void aReturnedValue_doesNotFire() throws Exception {
    assertThat(count("class T { boolean m(java.io.File f) { return f.delete(); } }")).isZero();
  }

  @Test
  void aValueUsedInAWhileCondition_doesNotFire() throws Exception {
    assertThat(count("class T { void m(java.util.Iterator<String> rs) { while (rs.next()) { } } }"))
        .isZero();
  }

  @Test
  void unrelatedMethodStatement_doesNotFire() throws Exception {
    assertThat(count("class T { void m() { System.out.println(\"hi\"); } }")).isZero();
  }

  // ---------- a void call has nothing to check ----------

  /**
   * A void method has no return value, so there is nothing to check.
   *
   * <p>This is the false positive the name-only rule produced. The JDK signatures are the
   * authority: {@code InputStream.close()} is {@code public void close() throws IOException}.
   */
  @Test
  void aVoidCloseIsNotAnUncheckedReturn() throws Exception {
    assertThat(count("class T { void m(java.io.InputStream s) throws Exception { s.close(); } }"))
        .isZero();
  }

  /** {@code Connection.commit()} is void: it reports failure by THROWING, not by returning. */
  @Test
  void aVoidCommitIsNotAnUncheckedReturn() throws Exception {
    assertThat(count("class T { void m(java.sql.Connection c) throws Exception { c.commit(); } }"))
        .isZero();
  }

  /** {@code Writer.flush()} is void. */
  @Test
  void aVoidFlushIsNotAnUncheckedReturn() throws Exception {
    assertThat(count("class T { void m(java.io.Writer w) throws Exception { w.flush(); } }"))
        .isZero();
  }

  /** {@code Files.delete(Path)} is void; {@code Files.deleteIfExists(Path)} returns a boolean. */
  @Test
  void aVoidDeleteIsNotAnUncheckedReturnButTheBooleanOneIs() throws Exception {
    assertThat(
            count(
                "class T { void m(java.nio.file.Path p) throws Exception { "
                    + "java.nio.file.Files.delete(p); } }"))
        .isZero();
    assertThat(
            count(
                "class T { void m(java.nio.file.Path p) throws Exception { "
                    + "java.nio.file.Files.deleteIfExists(p); } }"))
        .isEqualTo(1);
  }

  /** A user's own void method named like a must-check method is not a finding. */
  @Test
  void aUserVoidMethodOnTheWhitelistIsNotAnUncheckedReturn() throws Exception {
    assertThat(count("class T { void close() { } void m() { close(); } }")).isZero();
    assertThat(count("class T { static class B { void get() { } } void m(B b) { b.get(); } }"))
        .isZero();
  }

  /** A void method reached through a chain is not a finding either. */
  @Test
  void aVoidCallInAChainIsNotAnUncheckedReturn() throws Exception {
    assertThat(
            count(
                "class T { void m(java.net.Socket s) throws Exception { "
                    + "s.getInputStream().close(); } }"))
        .isZero();
  }

  // ---------- the name list ----------

  /** A collection's boolean result says whether the collection changed. */
  @Test
  void aCollectionMutationWithAnIgnoredResultIsDiscarded() throws Exception {
    assertThat(count("class T { void m(java.util.List<String> l) { l.add(\"a\"); } }"))
        .isEqualTo(1);
    assertThat(count("class T { void m(java.util.Set<String> s) { s.remove(\"a\"); } }"))
        .isEqualTo(1);
    assertThat(count("class T { void m(java.util.Map<String,String> m) { m.put(\"a\", \"b\"); } }"))
        .isEqualTo(1);
  }

  /** A statement's result says whether the command succeeded. */
  @Test
  void aStatementExecutionWithAnIgnoredResultIsDiscarded() throws Exception {
    assertThat(
            count(
                "class T { void m(java.sql.Statement s) throws Exception { s.execute(\"x\"); } }"))
        .isEqualTo(1);
    assertThat(
            count(
                "class T { void m(java.sql.PreparedStatement p) throws Exception { "
                    + "p.executeUpdate(\"x\"); } }"))
        .isEqualTo(1);
  }

  /**
   * An UNRESOLVABLE call is still reported.
   *
   * <p>A source-only scan has no third-party jar on the analysis classpath, so a call to a library
   * method usually cannot be resolved at all. The rule must still report it: an unresolved type is
   * a MISSING FACT, never a proof of safety. Assuming the call returns void would suppress a real
   * finding on the strength of a fact the scanner simply does not have.
   */
  @Test
  void anUnresolvableCallIsStillReported() throws Exception {
    assertThat(count("class T { void m() { SomeLib.delete(); } }")).isEqualTo(1);
    assertThat(count("class T { void m() { org.acme.Lib.execute(); } }")).isEqualTo(1);
    assertThat(count("class T { void m(SomeUnknownType t) { t.delete(); } }")).isEqualTo(1);
  }

  /**
   * The name is matched EXACTLY, so a longer name does not match.
   *
   * <p>{@code deleteIfExists} returns a boolean and IS on the list, so it fires either way and
   * cannot tell an exact match from a prefix match. {@code setReadOnly} returns a boolean and is
   * NOT on the list: it must not fire, and a prefix match against {@code set} would make it fire.
   * That is the case that distinguishes the two.
   */
  @Test
  void aLongerNameDoesNotMatchTheWhitelist() throws Exception {
    // 'deleteOnExit' is void, so it is not a finding for a different reason.
    assertThat(count("class T { void m(java.io.File f) { f.deleteOnExit(); } }")).isZero();
    // 'setReadOnly' returns a boolean, is NOT on the list, and must not fire.
    assertThat(count("class T { void m(java.io.File f) { f.setReadOnly(); } }")).isZero();
    assertThat(count("class T { void m(java.io.File f) { f.setWritable(true); } }")).isZero();
  }
}
