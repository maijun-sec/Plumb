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

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.concurrency.LockPairingCheck;
import org.plumb.style.security.HardCodedCryptoKeyCheck;
import org.plumb.style.security.MutableStaticFieldCheck;
import org.plumb.style.security.PlaintextCookieCheck;
import org.plumb.style.security.UnclosedResourceCheck;

/**
 * The rules that close the corpus gaps must fire on the corpus's own shapes and stay silent on the
 * fixed shape.
 *
 * <p>Each case is the Juliet shape: the same defect in the {@code bad} method, and the repaired
 * form in the {@code good} method. A rule that cannot tell the two apart is not a rule for this
 * defect.
 */
class R1200CorpusGapRulesTest {

  private List<Finding> run(AstChecker checker, String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(checker).run(ctx);
    return ctx.findings();
  }

  // ---- CWE-772 / CWE-775: a resource that is never closed ----

  @Test
  void unclosedResourceFiresOnTheUnclosedStream() {
    String src =
        "import java.io.*;\n"
            + "class Foo {\n"
            + "  void bad() throws Exception {\n"
            + "    BufferedReader readerBuffered = null;\n"
            + "    InputStreamReader readerInputStream = null;\n"
            + "    try {\n"
            + "      readerInputStream = new InputStreamReader(System.in, \"UTF-8\");\n"
            + "      readerBuffered = new BufferedReader(readerInputStream);\n"
            + "    } catch (IOException e) { }\n"
            + "  }\n"
            + "}";
    assertThat(run(new UnclosedResourceCheck(), src))
        .as("CWE-772: a stream that is never closed fires")
        .isNotEmpty();
  }

  @Test
  void unclosedResourceIsSilentWhenTheStreamIsClosed() {
    String src =
        "import java.io.*;\n"
            + "class Foo {\n"
            + "  void good() throws Exception {\n"
            + "    BufferedReader readerBuffered = null;\n"
            + "    InputStreamReader readerInputStream = null;\n"
            + "    try {\n"
            + "      readerInputStream = new InputStreamReader(System.in, \"UTF-8\");\n"
            + "      readerBuffered = new BufferedReader(readerInputStream);\n"
            + "    } catch (IOException e) { }\n"
            + "    finally {\n"
            + "      try { if (readerBuffered != null) { readerBuffered.close(); } }\n"
            + "      catch (IOException e) { }\n"
            + "      try { if (readerInputStream != null) { readerInputStream.close(); } }\n"
            + "      catch (IOException e) { }\n"
            + "    }\n"
            + "  }\n"
            + "}";
    assertThat(run(new UnclosedResourceCheck(), src))
        .as("CWE-772: a stream closed in a finally block does not fire")
        .isEmpty();
  }

  @Test
  void unclosedResourceIsSilentForTryWithResources() {
    String src =
        "import java.io.*;\n"
            + "class Foo {\n"
            + "  void good() throws Exception {\n"
            + "    try (BufferedReader r = new BufferedReader(new FileReader(\"f.txt\"))) {\n"
            + "      r.readLine();\n"
            + "    }\n"
            + "  }\n"
            + "}";
    assertThat(run(new UnclosedResourceCheck(), src))
        .as("CWE-772: try-with-resources releases automatically")
        .isEmpty();
  }

  @Test
  void unclosedResourceIsSilentForANonResource() {
    String src =
        "class Foo {\n"
            + "  void good() {\n"
            + "    StringBuilder sb = new StringBuilder();\n"
            + "    sb.append(\"x\");\n"
            + "  }\n"
            + "}";
    assertThat(run(new UnclosedResourceCheck(), src))
        .as("CWE-772: a StringBuilder is not a closeable resource")
        .isEmpty();
  }

  // ---- CWE-582 / CWE-607: a public static final field that is mutable ----

  @Test
  void mutableStaticFieldFiresOnAPublicStaticFinalArray() {
    String src = "class Foo { public static final int[] INT_ARRAY = new int[10]; }";
    assertThat(run(new MutableStaticFieldCheck(), src))
        .as("CWE-582: a public static final array fires")
        .isNotEmpty();
  }

  @Test
  void mutableStaticFieldFiresOnAPublicStaticFinalCollection() {
    String src =
        "import java.util.*;\n"
            + "class Foo { public static final List<String> NAMES = new ArrayList<>(); }";
    assertThat(run(new MutableStaticFieldCheck(), src))
        .as("CWE-607: a public static final List fires")
        .isNotEmpty();
  }

  @Test
  void mutableStaticFieldIsSilentForAnImmutableConstant() {
    String src = "class Foo { public static final String NAME = \"x\"; }";
    assertThat(run(new MutableStaticFieldCheck(), src))
        .as("CWE-582: a String constant is genuinely fixed")
        .isEmpty();
  }

  @Test
  void mutableStaticFieldIsSilentWhenTheFieldIsNotPublic() {
    String src = "class Foo { static final int[] INT_ARRAY = new int[10]; }";
    assertThat(run(new MutableStaticFieldCheck(), src))
        .as("CWE-582: a package-private array is not reachable by every caller")
        .isEmpty();
  }

  // ---- CWE-765 / CWE-832 / CWE-667: lock pairing ----

  @Test
  void lockPairingFiresOnMoreUnlocksThanLocks() {
    String src =
        "import java.util.concurrent.locks.*;\n"
            + "class Foo {\n"
            + "  static private final ReentrantLock L = new ReentrantLock();\n"
            + "  void bad() {\n"
            + "    L.lock();\n"
            + "    try { work(); }\n"
            + "    finally { L.unlock(); L.unlock(); }\n"
            + "  }\n"
            + "  void work() { }\n"
            + "}";
    assertThat(run(new LockPairingCheck(), src)).as("CWE-765: unlocking twice fires").isNotEmpty();
  }

  @Test
  void lockPairingFiresOnUnlockWithoutLock() {
    String src =
        "import java.util.concurrent.locks.*;\n"
            + "class Foo {\n"
            + "  static private final ReentrantLock L = new ReentrantLock();\n"
            + "  void bad() {\n"
            + "    try { work(); }\n"
            + "    finally { L.unlock(); }\n"
            + "  }\n"
            + "  void work() { }\n"
            + "}";
    assertThat(run(new LockPairingCheck(), src))
        .as("CWE-832: unlocking a lock that was never taken fires")
        .isNotEmpty();
  }

  @Test
  void lockPairingFiresOnALockThatIsNeverReleased() {
    String src =
        "import java.util.concurrent.locks.*;\n"
            + "class Foo {\n"
            + "  static private final ReentrantLock L = new ReentrantLock();\n"
            + "  void bad() { L.lock(); work(); }\n"
            + "  void work() { }\n"
            + "}";
    assertThat(run(new LockPairingCheck(), src))
        .as("CWE-667: a lock that is never released fires")
        .isNotEmpty();
  }

  @Test
  void lockPairingIsSilentWhenTheLockIsBalanced() {
    String src =
        "import java.util.concurrent.locks.*;\n"
            + "class Foo {\n"
            + "  static private final ReentrantLock L = new ReentrantLock();\n"
            + "  void good() {\n"
            + "    L.lock();\n"
            + "    try { work(); }\n"
            + "    finally { L.unlock(); }\n"
            + "  }\n"
            + "  void work() { }\n"
            + "}";
    assertThat(run(new LockPairingCheck(), src))
        .as("CWE-765: a balanced lock does not fire")
        .isEmpty();
  }

  // ---- CWE-321: a hard-coded cryptographic key ----

  @Test
  void hardCodedCryptoKeyFiresOnALiteralKey() {
    String src =
        "import javax.crypto.spec.*;\n"
            + "class Foo {\n"
            + "  void bad() throws Exception {\n"
            + "    SecretKeySpec spec = new SecretKeySpec(\"Super secret"
            + " Squirrel\".getBytes(\"UTF-8\"), \"AES\");\n"
            + "  }\n"
            + "}";
    assertThat(run(new HardCodedCryptoKeyCheck(), src))
        .as("CWE-321: a literal used as a key fires")
        .isNotEmpty();
  }

  @Test
  void hardCodedCryptoKeyFiresOnALiteralHeldInAVariable() {
    String src =
        "import javax.crypto.spec.*;\n"
            + "class Foo {\n"
            + "  void bad() throws Exception {\n"
            + "    String secret = \"Super secret Squirrel\";\n"
            + "    SecretKeySpec spec = new SecretKeySpec(secret.getBytes(\"UTF-8\"), \"AES\");\n"
            + "  }\n"
            + "}";
    assertThat(run(new HardCodedCryptoKeyCheck(), src))
        .as("CWE-321: a literal assigned to a variable and used as a key fires")
        .isNotEmpty();
  }

  @Test
  void hardCodedCryptoKeyIsSilentWhenTheKeyComesFromOutside() {
    String src =
        "import javax.crypto.spec.*;\n"
            + "class Foo {\n"
            + "  void good(String data) throws Exception {\n"
            + "    SecretKeySpec spec = new SecretKeySpec(data.getBytes(\"UTF-8\"), \"AES\");\n"
            + "  }\n"
            + "}";
    assertThat(run(new HardCodedCryptoKeyCheck(), src))
        .as("CWE-321: a key read from outside the source does not fire")
        .isEmpty();
  }

  @Test
  void hardCodedCryptoKeyFiresOnASeededRandom() {
    String src =
        "import java.security.*;\n"
            + "class Foo {\n"
            + "  void bad() throws Exception {\n"
            + "    SecureRandom random = SecureRandom.getInstance(\"SHA1PRNG\");\n"
            + "    random.setSeed(12345L);\n"
            + "  }\n"
            + "}";
    assertThat(run(new HardCodedCryptoKeyCheck(), src))
        .as("CWE-321: a seeded random is not random")
        .isNotEmpty();
  }

  // ---- CWE-315: a cookie that stores a value in plaintext ----

  @Test
  void plaintextCookieFiresOnAnUnhashedValue() {
    String src =
        "import javax.servlet.http.*;\n"
            + "class Foo {\n"
            + "  void bad(String data, HttpServletResponse response) {\n"
            + "    response.addCookie(new Cookie(\"auth\", data));\n"
            + "  }\n"
            + "}";
    assertThat(run(new PlaintextCookieCheck(), src))
        .as("CWE-315: a cookie that stores a plain value fires")
        .isNotEmpty();
  }

  @Test
  void plaintextCookieIsSilentWhenTheValueIsHashed() {
    String src =
        "import javax.servlet.http.*;\n"
            + "import java.security.MessageDigest;\n"
            + "class Foo {\n"
            + "  void good(String data, HttpServletResponse response) throws Exception {\n"
            + "    String salt = \"ThisIsMySalt\";\n"
            + "    MessageDigest digest = MessageDigest.getInstance(\"SHA-512\");\n"
            + "    digest.reset();\n"
            + "    byte[] hashed = digest.digest((salt + data).getBytes(\"UTF-8\"));\n"
            + "    data = IO.toHex(hashed);\n"
            + "    response.addCookie(new Cookie(\"auth\", data));\n"
            + "  }\n"
            + "}";
    assertThat(run(new PlaintextCookieCheck(), src))
        .as("CWE-315: a cookie that stores a hash does not fire")
        .isEmpty();
  }
}
