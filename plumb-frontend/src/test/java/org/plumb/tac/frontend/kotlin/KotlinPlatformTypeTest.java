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
package org.plumb.tac.frontend.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacOperand;

/**
 * The platform-type rule: a Java type seen from Kotlin may hold {@code null} at runtime.
 *
 * <p><b>Why this matters more than it looks.</b> Kotlin's type checker lets you
 * dereference a Java value without a {@code ?}, because the Java declaration says
 * nothing about null. But the Java declaration really does say nothing: the value can
 * be {@code null}, the Kotlin compiler's inserted assertion is removable with {@code
 * -Xno-call-assertions}, and ProGuard's {@code -assumenosideeffects} strips it from
 * bytecode. So a Java-interop value must be treated as nullable by the analysis, or
 * the null-dereference checker will miss a real crash.
 *
 * <p><b>Why the rule is tested at the source level.</b> The Analysis API's own
 * nullability flags cannot express this: a Java type named in the source
 * ({@code s: java.lang.StringBuilder}) comes back as an ordinary non-null type, and
 * only the <i>origin</i> of the type's classifier says it is a Java class. So the test
 * asserts the outcome the frontend produces, which is the thing that matters.
 */
class KotlinPlatformTypeTest {

  /**
   * A Java class the Kotlin source can name.
   *
   * <p>{@code java.lang.StringBuilder} is a JDK class, so it resolves through the SDK
   * module the session is built with -- no extra test fixture is needed.
   */
  private static final String JAVA_CLASS = "java.lang.StringBuilder";

  /**
   * A Kotlin class from the standard library.
   *
   * <p>Not {@code kotlin.text.StringBuilder}: that is a type alias for {@code
   * java.lang.StringBuilder}, so its classifier is the Java class and it is a platform
   * type. {@code kotlin.text.Regex} is a genuinely Kotlin class.
   */
  private static final String KOTLIN_CLASS = "kotlin.text.Regex";

  @Test
  void aJavaTypeSeenFromKotlinIsNullable() {
    TacFrontend.ParseResult result =
        parse(
            "fun takesJava(value: "
                + JAVA_CLASS
                + "): "
                + JAVA_CLASS
                + " {",
            "    return value",
            "}");

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar param = fn.params().get(0);

    assertThat(param.type().isNullable())
        .as("a Java type may hold null at runtime; got %s", param.type())
        .isTrue();
  }

  @Test
  void aJavaReturnTypeSeenFromKotlinIsNullable() {
    TacFrontend.ParseResult result =
        parse(
            "fun returnsJava(): " + JAVA_CLASS + " {",
            "    return " + JAVA_CLASS + "()",
            "}");

    TacFunction fn = result.functions().get(0);
    assertThat(fn.returnType().isNullable())
        .as("a Java return type may hold null at runtime; got %s", fn.returnType())
        .isTrue();
  }

  @Test
  void aKotlinTypeIsNotNullable() {
    TacFrontend.ParseResult result =
        parse(
            "fun takesKotlin(value: " + KOTLIN_CLASS + "): " + KOTLIN_CLASS + " {",
            "    return value",
            "}");

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar param = fn.params().get(0);

    assertThat(param.type().isNullable())
        .as("a Kotlin type without a '?' cannot hold null; got %s", param.type())
        .isFalse();
  }

  @Test
  void anExplicitlyNullableKotlinTypeIsNullable() {
    TacFrontend.ParseResult result =
        parse(
            "fun takesNullable(value: String?): String? {",
            "    return value",
            "}");

    TacFunction fn = result.functions().get(0);
    assertThat(fn.params().get(0).type().isNullable()).isTrue();
    assertThat(fn.returnType().isNullable()).isTrue();
  }

  /**
   * The JDK and the Kotlin standard library must actually resolve.
   *
   * <p>Without them every reference is an error type, every value is unknown, and the
   * nullability rules above would pass vacuously -- an unknown type is nullable too.
   * This test is what stops that from happening silently.
   */
  @Test
  void theJdkAndTheStandardLibraryResolve() {
    TacFrontend.ParseResult result =
        parse(
            "fun usesJdk(): String {",
            "    val builder = java.lang.StringBuilder()",
            "    builder.append(\"x\")",
            "    return builder.toString()",
            "}",
            "",
            "fun usesStdlib(): String {",
            "    val matcher = Regex(\"x\")",
            "    return matcher.pattern",
            "}");

    assertThat(result.problems()).as("problems: %s", result.problems()).isEmpty();

    TacFunction jdk =
        result.functions().stream()
            .filter(f -> "usesJdk".equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("usesJdk was not lowered"));
    TacOperand.TacVar builder =
        jdk.locals().stream()
            .filter(v -> "builder".equals(v.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("builder was not declared: " + jdk.locals()));

    // A JDK class resolves to a class type, not to an unknown one. Its nullability is
    // nullable because it is a Java type -- see the class javadoc.
    assertThat(builder.type().kind())
        .as("builder's type: %s", builder.type())
        .isNotEqualTo(org.plumb.tac.ir.TacType.Kind.UNKNOWN);
    assertThat(builder.type().displayName()).contains("StringBuilder");

    TacFunction stdlib =
        result.functions().stream()
            .filter(f -> "usesStdlib".equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("usesStdlib was not lowered"));
    TacOperand.TacVar matcher =
        stdlib.locals().stream()
            .filter(v -> "matcher".equals(v.name()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("matcher was not declared: " + stdlib.locals()));
    assertThat(matcher.type().kind())
        .as("matcher's type: %s", matcher.type())
        .isNotEqualTo(org.plumb.tac.ir.TacType.Kind.UNKNOWN);
    assertThat(matcher.type().displayName()).contains("Regex");
  }

  /** Parses a Kotlin source whose body is the given lines. */
  private static TacFrontend.ParseResult parse(String... lines) {
    List<String> all = new java.util.ArrayList<>();
    all.add("package demo");
    all.add("");
    all.addAll(List.of(lines));
    return new KotlinTestSupport().parse(String.join("\n", all));
  }
}
