package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A parameter bound by a FRAMEWORK entry point names the trust boundary it came from.
 *
 * <p>A servlet-aware scanner recognises {@code HttpServletRequest.getParameter(...)} as a taint
 * source. A Spring MVC application has no such call: the framework binds the request value to the
 * method parameter BY ANNOTATION, and the controller body never touches a request object.
 *
 * <pre>
 *   &#64;RestController
 *   public class C {
 *     &#64;GetMapping("/x")
 *     public void search(&#64;RequestParam("customer") String customer) { ... }
 *   }
 * </pre>
 *
 * <p>So the source is not a CALL, it is a PARAMETER ANNOTATION. The IR already records these
 * ({@code TacFunction.paramAnnotations()}), and the AST checkers already use the same annotation
 * list for input validation. Before this model, the taint engine labelled such a parameter exactly
 * like any other parameter, so a finding could not tell a request-bound value from an internal one.
 *
 * <p>The label keeps the {@code "param:"} prefix every other taint site understands and appends the
 * framework qualifier: {@code "param:customer@spring-mvc:RequestParam"}.
 */
class FrameworkEntryPointSourceTest {

  private static final String RULE = "dfa-taint-sql-injection";

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
                2,
                1000,
                1000,
                100,
                4,
                false,
                false,
                false,
                "full",
                "cwe_family",
                "sarif",
                false,
                0),
            base.receiverPassthroughPatterns());

    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /**
   * The source label the finding carries, e.g. {@code "param:customer@spring-mvc:RequestParam"}.
   */
  private String sourceLabelOf(Finding f) {
    String dataflow = f.dataflowHead();
    if (dataflow == null || !dataflow.startsWith("taint: ")) {
      return null;
    }
    String rest = dataflow.substring("taint: ".length());
    int arrow = rest.indexOf(" -> ");
    return arrow < 0 ? rest : rest.substring(0, arrow);
  }

  /**
   * The framework-bound parameter is labelled with the framework annotation.
   *
   * <p>The source step in the trace must name it too, so a reader of the trace sees the same origin
   * the summary line does.
   */
  @Test
  void aRequestParamBoundParameterNamesTheSpringEntryPoint() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RequestParam("customer") String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "SpringController.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    String label = sourceLabelOf(out.get(0));
    assertThat(label)
        .as("the source must name the framework entry point")
        .isEqualTo("param:customer@spring-mvc:RequestParam");
  }

  /** The trace's source step carries the same label as the summary line. */
  @Test
  void theTraceSourceStepNamesTheFrameworkEntryPoint() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RequestParam("customer") String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "SpringTrace.java");
    assertThat(out).hasSize(1);
    List<String> sources = new ArrayList<>();
    for (FlowStep step : out.get(0).dataflowSteps()) {
      if ("source".equals(step.role())) {
        sources.add(step.expr());
      }
    }
    assertThat(sources).isNotEmpty();
    assertThat(sources.get(0)).contains("spring-mvc:RequestParam");
  }

  /**
   * Control: a parameter with NO framework annotation keeps the plain label.
   *
   * <p>Without this control the test above would pass even if EVERY parameter were labelled as a
   * framework entry point.
   */
  @Test
  void aPlainParameterKeepsThePlainLabel() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            public class C {
              private final java.sql.Statement statement = null;
              public void search(String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "PlainController.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("a parameter with no framework annotation is not a framework entry point")
        .isEqualTo("param:customer");
  }

  /** A JAX-RS parameter is recognised too, and names the JAX-RS framework. */
  @Test
  void aJaxRsQueryParamIsRecognised() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import jakarta.ws.rs.QueryParam;
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@QueryParam("customer") String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "JaxRsResource.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0))).contains("QueryParam");
  }

  /**
   * A Micronaut parameter is recognised too, and names the Micronaut framework.
   *
   * <p>Micronaut names its binding annotations differently from everyone else: {@code @Header}
   * rather than {@code @RequestHeader}, {@code @QueryValue} rather than {@code @QueryParam}. Before
   * the shared annotation catalogue, the taint engine's list held {@code HeaderValue} and {@code
   * PathValue} -- names no framework defines -- while the real {@code @Header} was missing. A value
   * from a Micronaut request header therefore reached the sink with no taint flow to follow.
   */
  @Test
  void aMicronautHeaderIsRecognised() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import io.micronaut.http.annotation.Header;
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@Header("X-Customer") String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "MicronautController.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    String label = sourceLabelOf(out.get(0));
    assertThat(label)
        .as("the source must name the Micronaut entry point")
        .isEqualTo("param:customer@micronaut:Header");
  }

  /**
   * A Quarkus parameter is recognised too, and names the Quarkus framework.
   *
   * <p>{@code @RestCookie} and {@code @RestMatrix} were missing entirely before the shared
   * catalogue, so a value from a Quarkus cookie reached the sink untainted.
   */
  @Test
  void aQuarkusRestCookieIsRecognised() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.jboss.resteasy.reactive.RestCookie;
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RestCookie("customer") String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "QuarkusResource.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("the source must name the Quarkus entry point")
        .isEqualTo("param:customer@quarkus-rest:RestCookie");
  }

  /**
   * A non-user-input annotation does NOT make the parameter a framework entry point.
   *
   * <p>{@code @Autowired} and friends say nothing about where the value came from.
   */
  @Test
  void aNonUserInputAnnotationIsNotAnEntryPoint() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.springframework.beans.factory.annotation.Autowired;
            public class C {
              private final java.sql.Statement statement = null;
              @Autowired
              public void search(String customer) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + customer + "'");
              }
            }
            """,
            "AutowiredMethod.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("@Autowired says nothing about the value's origin")
        .isEqualTo("param:customer");
  }
}
