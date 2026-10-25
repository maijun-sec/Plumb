package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

/**
 * The input-validation checker recognises the same binding annotations the taint engine does.
 *
 * <p>Before the shared annotation catalogue, this checker knew thirteen binding annotations while
 * the taint engine knew twenty-six. The gap meant a {@code @RestQuery} or {@code @QueryValue}
 * parameter was tracked as attacker-controlled by the taint engine and treated as
 * trusted-by-default here: the checker never asked for it to be validated.
 *
 * <p>So this checker now has to fire on a Quarkus resource and on a Micronaut controller, not only
 * on a Spring controller.
 */
class MissingInputValidationFrameworkTest {

  private static final String RULE = "ast-security-missing-input-validation";

  private final SecurityAstCheckTestHarness harness =
      new SecurityAstCheckTestHarness(new MissingInputValidationCheck());

  private List<Finding> run(String src) {
    return harness.run(src);
  }

  private boolean fires(String src) {
    for (Finding f : run(src)) {
      if (RULE.equals(f.rule().ruleId().id())) {
        return true;
      }
    }
    return false;
  }

  /** A Spring controller parameter without validation is reported. */
  @Test
  void aSpringControllerParameterIsReported() {
    assertThat(
            fires(
                "import org.springframework.web.bind.annotation.RequestParam;\n"
                    + "import org.springframework.web.bind.annotation.RestController;\n"
                    + "@RestController\n"
                    + "class C {\n"
                    + "  public void search(@RequestParam(\"customer\") String customer) {}\n"
                    + "}\n"))
        .isTrue();
  }

  /**
   * A Quarkus resource parameter is reported.
   *
   * <p>Before the shared catalogue, {@code @RestQuery} was invisible here, so a Quarkus endpoint
   * received no CWE-20 finding at all.
   */
  @Test
  void aQuarkusResourceParameterIsReported() {
    assertThat(
            fires(
                "import jakarta.ws.rs.GET;\n"
                    + "import jakarta.ws.rs.Path;\n"
                    + "import org.jboss.resteasy.reactive.RestQuery;\n"
                    + "@Path(\"/orders\")\n"
                    + "class C {\n"
                    + "  @GET\n"
                    + "  public void search(@RestQuery(\"customer\") String customer) {}\n"
                    + "}\n"))
        .isTrue();
  }

  /**
   * A Micronaut controller parameter is reported.
   *
   * <p>Before the shared catalogue, {@code @QueryValue} was invisible here. Micronaut names its
   * annotations differently from every other framework, which is exactly the kind of difference a
   * hand-maintained list misses.
   */
  @Test
  void aMicronautControllerParameterIsReported() {
    assertThat(
            fires(
                "import io.micronaut.http.annotation.Controller;\n"
                    + "import io.micronaut.http.annotation.Get;\n"
                    + "import io.micronaut.http.annotation.QueryValue;\n"
                    + "@Controller\n"
                    + "class C {\n"
                    + "  @Get\n"
                    + "  public void search(@QueryValue(\"customer\") String customer) {}\n"
                    + "}\n"))
        .isTrue();
  }

  /**
   * A JAX-RS resource parameter is reported.
   *
   * <p>{@code @QueryParam} was already known, so this is the control: it proves the shared
   * catalogue did not LOSE the annotations the checker already had.
   */
  @Test
  void aJaxRsResourceParameterIsReported() {
    assertThat(
            fires(
                "import jakarta.ws.rs.GET;\n"
                    + "import jakarta.ws.rs.Path;\n"
                    + "import jakarta.ws.rs.QueryParam;\n"
                    + "@Path(\"/orders\")\n"
                    + "class C {\n"
                    + "  @GET\n"
                    + "  public void search(@QueryParam(\"customer\") String customer) {}\n"
                    + "}\n"))
        .isTrue();
  }

  /**
   * A parameter with no binding annotation is NOT reported.
   *
   * <p>Without this control the tests above would pass even if EVERY parameter were reported.
   */
  @Test
  void aPlainParameterIsNotReported() {
    assertThat(fires("class C {\n  public void search(String customer) {}\n}\n")).isFalse();
  }

  /**
   * A parameter that opts into validation is NOT reported.
   *
   * <p>The checker is about the ABSENCE of validation, not the presence of user input.
   */
  @Test
  void aValidatedParameterIsNotReported() {
    assertThat(
            fires(
                "import jakarta.validation.Valid;\n"
                    + "import org.springframework.web.bind.annotation.RequestParam;\n"
                    + "import org.springframework.web.bind.annotation.RestController;\n"
                    + "@RestController\n"
                    + "class C {\n"
                    + "  public void search(@Valid @RequestParam(\"customer\") String customer)"
                    + " {}\n"
                    + "}\n"))
        .isFalse();
  }
}
