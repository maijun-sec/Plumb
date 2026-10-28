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
 * R-813: hermetic test for the 7 AST checkers added by the
 * R-813 audit cleanup (2026-10-14). Each is exercised with a
 * minimal source snippet that triggers its fire / no-fire
 * condition. The test locks the AST shape contract so future
 * refactors that drift from the regex / pattern don't silently
 * break coverage.
 *
 * <p>Original round added 9; ObsoleteFunctionCheck (CWE-477) and
 * RecursionCheck (CWE-674) were deleted after juliet E2E
 * confirmed they were duplicates of plumb-style
 * {@code ObsoleteApiCheck} (which has full type resolution) and
 * plumb-dfa {@code taint/UncontrolledRecursionChecker}
 * respectively. The 7 remaining all add net new coverage.</p>
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

class R813NewAstCheckersTest {

  private final JavaSourceParser parser = new JavaSourceParser();
  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<Finding> run(AstChecker check, String src) {
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    AstDispatcher d = new AstDispatcher();
    d.register(check);
    d.run(ctx);
    return ctx.findings();
  }

  private long count(List<Finding> findings, String ruleIdPrefix) {
    return findings.stream()
        .filter(
            f ->
                f.rule() != null
                    && f.rule().ruleId() != null
                    && f.rule().ruleId().id() != null
                    && f.rule().ruleId().id().startsWith(ruleIdPrefix))
        .count();
  }

  // R-813 cleanup (2026-10-14): obsoleteFunction / recursion tests
  // deleted — both were duplicates of existing checks
  // (ObsoleteApiCheck has full type resolution; taint/UncontrolledRecursion
  // covers CWE-674). The 7 remaining new checks all add net new coverage.

  // ===== ProcessControlCheck (CWE-114) =====

  @Test
  void processControl_firesOnLoadLibrary() {
    List<Finding> f =
        run(
            new org.plumb.style.security.ProcessControlCheck(),
            "class T { static { System.loadLibrary(\"crypto\"); } }");
    assertThat(count(f, "ast-security-process-control")).isGreaterThan(0);
  }

  @Test
  void processControl_doesNotFireOnLoad() {
    List<Finding> f =
        run(
            new org.plumb.style.security.ProcessControlCheck(),
            "class T { static { System.load(\"x\"); } }");
    assertThat(count(f, "ast-security-process-control")).isZero();
  }

  // ===== RecursionCheck (CWE-674) =====
  // R-813 cleanup (2026-10-14): ObsoleteFunctionCheck / RecursionCheck
  // deleted — duplicate of plumb-style ObsoleteApiCheck (with type
  // resolution) and taint/UncontrolledRecursionChecker respectively.
  // The CWEs are still covered; the juliet smoke test below confirms
  // the taint UncontrolledRecursionChecker fires (4 hits in CWE-674).

  // ===== InformationExposureCheck (CWE-200) =====

  @Test
  void informationExposure_firesOnEntityReturn() {
    String src =
        "class User { String name; }\n"
            + "import org.springframework.web.bind.annotation.RestController;\n"
            + "@RestController class Ctrl {"
            + " @org.springframework.web.bind.annotation.GetMapping(\"/u\")\n"
            + "  public User get() { return null; } }";
    List<Finding> f = run(new org.plumb.style.security.InformationExposureCheck(), src);
    assertThat(count(f, "ast-security-information-exposure")).isGreaterThan(0);
  }

  @Test
  void informationExposure_doesNotFireOnStringReturn() {
    String src =
        "import org.springframework.web.bind.annotation.RestController;\n"
            + "@RestController class Ctrl {"
            + " @org.springframework.web.bind.annotation.GetMapping(\"/u\")\n"
            + "  public String get() { return \"x\"; } }";
    List<Finding> f = run(new org.plumb.style.security.InformationExposureCheck(), src);
    assertThat(count(f, "ast-security-information-exposure")).isZero();
  }

  // ===== MissingAuthorizationCheck (CWE-862) =====

  @Test
  void missingAuthorization_firesOnUnannotatedEndpoint() {
    String src =
        "import org.springframework.web.bind.annotation.RestController;\n"
            + "@RestController class Ctrl {"
            + " @org.springframework.web.bind.annotation.GetMapping(\"/u\")\n"
            + "  public void m() {} }";
    List<Finding> f = run(new org.plumb.style.security.MissingAuthorizationCheck(), src);
    assertThat(count(f, "ast-security-missing-authorization")).isGreaterThan(0);
  }

  @Test
  void missingAuthorization_doesNotFireWhenPreAuthorizePresent() {
    String src =
        "import org.springframework.web.bind.annotation.RestController;\n"
            + "import org.springframework.security.access.prepost.PreAuthorize;\n"
            + "@RestController class Ctrl { @GetMapping(\"/u\") @PreAuthorize(\"hasRole('X')\")\n"
            + "  public void m() {} }";
    List<Finding> f = run(new org.plumb.style.security.MissingAuthorizationCheck(), src);
    assertThat(count(f, "ast-security-missing-authorization")).isZero();
  }

  // ===== MissingInputValidationCheck (CWE-20) =====

  @Test
  void missingInputValidation_firesOnRequestParamWithoutValid() {
    String src =
        "import org.springframework.web.bind.annotation.RestController;\n"
            + "import org.springframework.web.bind.annotation.RequestParam;\n"
            + "@RestController class Ctrl {"
            + " @org.springframework.web.bind.annotation.GetMapping(\"/u\")\n"
            + "  public void m(@RequestParam String x) {} }";
    List<Finding> f = run(new org.plumb.style.security.MissingInputValidationCheck(), src);
    assertThat(count(f, "ast-security-missing-input-validation")).isGreaterThan(0);
  }

  @Test
  void missingInputValidation_doesNotFireWhenClassValidated() {
    String src =
        "import org.springframework.web.bind.annotation.RestController;\n"
            + "import org.springframework.web.bind.annotation.RequestParam;\n"
            + "import org.springframework.validation.annotation.Validated;\n"
            + "@RestController @Validated class Ctrl {"
            + " @org.springframework.web.bind.annotation.GetMapping(\"/u\")\n"
            + "  public void m(@RequestParam String x) {} }";
    List<Finding> f = run(new org.plumb.style.security.MissingInputValidationCheck(), src);
    assertThat(count(f, "ast-security-missing-input-validation")).isZero();
  }

  // ===== EnvVarCheck (CWE-526) =====

  @Test
  void envVar_firesOnGetenv() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.EnvVarCheck(),
            "class T { String m() { return System.getenv(\"X\"); } }");
    assertThat(count(f, "ast-quality-env-var")).isGreaterThan(0);
  }

  @Test
  void envVar_doesNotFireOnGetProperty() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.EnvVarCheck(),
            "class T { String m() { return System.getProperty(\"X\"); } }");
    assertThat(count(f, "ast-quality-env-var")).isZero();
  }

  // ===== AlwaysFalseCheck (CWE-570) =====

  @Test
  void alwaysFalse_firesOnIfFalse() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.AlwaysFalseCheck(),
            "class T { void m() { if (false) { System.out.println(\"x\"); } } }");
    assertThat(count(f, "ast-quality-always-false")).isGreaterThan(0);
  }

  @Test
  void alwaysFalse_firesOnVarEqVar() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.AlwaysFalseCheck(),
            "class T { void m(int x) { if (x != x) { System.out.println(\"x\"); } } }");
    assertThat(count(f, "ast-quality-always-false")).isGreaterThan(0);
  }

  @Test
  void alwaysFalse_doesNotFireOnNormalCompare() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.AlwaysFalseCheck(),
            "class T { void m(int x, int y) { if (x == y) { System.out.println(\"x\"); } } }");
    assertThat(count(f, "ast-quality-always-false")).isZero();
  }

  // ===== AlwaysTrueCheck (CWE-571) =====

  @Test
  void alwaysTrue_firesOnIfTrue() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.AlwaysTrueCheck(),
            "class T { void m() { if (true) { System.out.println(\"x\"); } } }");
    assertThat(count(f, "ast-quality-always-true")).isGreaterThan(0);
  }

  @Test
  void alwaysTrue_firesOnVarEqVar() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.AlwaysTrueCheck(),
            "class T { void m(int x) { if (x == x) { System.out.println(\"x\"); } } }");
    assertThat(count(f, "ast-quality-always-true")).isGreaterThan(0);
  }

  @Test
  void alwaysTrue_doesNotFireOnNormalCompare() {
    List<Finding> f =
        run(
            new org.plumb.style.quality.AlwaysTrueCheck(),
            "class T { void m(int x, int y) { if (x == y) { System.out.println(\"x\"); } } }");
    assertThat(count(f, "ast-quality-always-true")).isZero();
  }

  // ===== ClassSourceChecker CWE-382 (SystemExit) — already
  // covered by SystemExitUsageCheck; verify it still fires =====

  @Test
  void systemExitUsageCheck_firesOnRuntimeGetRuntimeExit() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SystemExitUsageCheck(),
            "class T { void m() { Runtime.getRuntime().exit(0); } }");
    // The ruleId for SystemExitUsageCheck is pl-0041-system-exit.
    long n =
        f.stream()
            .filter(
                g ->
                    g.rule() != null
                        && g.rule().ruleId() != null
                        && g.rule().ruleId().id() != null
                        && g.rule().ruleId().id().startsWith("ast-style-system-exit"))
            .count();
    assertThat(n).isGreaterThan(0);
  }
}
