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
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A FIELD of a request bean is the framework entry point, not the bean parameter.
 *
 * <p>A bean parameter is not itself the request value. The framework binds each FIELD of the bean
 * class to its own request value, so the binding annotation lives on the field:
 *
 * <pre>
 *   public class Parameters {
 *     &#64;RestQuery("customer")      // &lt;-- the binding annotation is HERE
 *     public String customer;
 *   }
 *
 *   public void search(&#64;RestBeanParam Parameters params) {
 *     repository.findByCustomer(params.customer);   // &lt;-- the value entered here
 *   }
 * </pre>
 *
 * <p>This was a silent false negative. The bean class declares no method and no initializer, so it
 * produced NO function in the IR, and its {@code @RestQuery} was invisible to the whole engine: the
 * endpoint reading {@code params.customer} had no way to learn the field was request-bound.
 *
 * <p>The fix spans two layers. The TAC now records every declared class's field annotations on the
 * program, because a function's own field annotations cannot describe a class that has no
 * functions. The DFA then resolves the field's binding annotation at the {@code FIELDLOAD} and
 * labels it.
 */
class FrameworkFieldBindingSourceTest {

  private static final String RULE = "dfa-taint-sql-injection";

  @TempDir Path tmp;

  /** Translate a source file into a TAC program, without running the engine. */
  private TacProgram translate(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

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
   * The source label the finding carries, e.g. {@code
   * "param:params.customer@quarkus-rest:RestQuery"}.
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
   * The Quarkus container binding names the field's own binding annotation.
   *
   * <p>The label names the FIELD, not the parameter: {@code params.customer}, because that is the
   * value that actually came from the request.
   *
   * <p>The bean is deliberately NOT called {@code Parameters}: a bean name that every test shares
   * would let an implementation that resolves a HARDCODED class name pass every test. The bean type
   * has to come from the receiver's type.
   */
  @Test
  void aQuarkusBeanFieldNamesItsOwnBindingAnnotation() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            import org.jboss.resteasy.reactive.RestQuery;
            public class QuarkusOrderFilter {
              @RestQuery("customer")
              public String customer;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RestBeanParam QuarkusOrderFilter params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
              }
            }
            """,
            "QuarkusBean.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("the source must name the bound field and its annotation")
        .isEqualTo("param:params.customer@quarkus-rest:RestQuery");
  }

  /** The JAX-RS container binding names the JAX-RS field annotation. */
  @Test
  void aJaxRsBeanFieldNamesItsOwnBindingAnnotation() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import jakarta.ws.rs.BeanParam;
            import jakarta.ws.rs.QueryParam;
            public class JaxRsOrderFilter {
              @QueryParam("customer")
              public String customer;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@BeanParam JaxRsOrderFilter params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
              }
            }
            """,
            "JaxRsBean.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("the source must name the bound field and its annotation")
        .isEqualTo("param:params.customer@jax-rs:QueryParam");
  }

  /** The Micronaut container binding names the Micronaut field annotation. */
  @Test
  void aMicronautBeanFieldNamesItsOwnBindingAnnotation() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import io.micronaut.http.annotation.RequestBean;
            import io.micronaut.http.annotation.QueryValue;
            public class MicronautOrderFilter {
              @QueryValue("customer")
              public String customer;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RequestBean MicronautOrderFilter params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
              }
            }
            """,
            "MicronautBean.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("the source must name the bound field and its annotation")
        .isEqualTo("param:params.customer@micronaut:QueryValue");
  }

  /**
   * The FIELD's own annotation decides the framework, not the container's.
   *
   * <p>Both annotations come from the same framework in every realistic bean, so this test uses a
   * bean whose CONTAINER says one framework and whose FIELD says another. The field wins, because
   * the field's annotation is the one that actually bound the value.
   *
   * <p>This is the check that a JAX-RS bean reached through a Quarkus-shaped container still names
   * JAX-RS -- and, more importantly, that the framework is READ FROM THE FIELD rather than
   * hardcoded or inherited from the container.
   */
  @Test
  void theFieldsOwnAnnotationDecidesTheFramework() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            import jakarta.ws.rs.QueryParam;
            public class MixedFrameworkFilter {
              @QueryParam("customer")
              public String customer;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RestBeanParam MixedFrameworkFilter params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
              }
            }
            """,
            "MixedFramework.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("the field's own annotation must decide the framework")
        .isEqualTo("param:params.customer@jax-rs:QueryParam");
  }

  /**
   * The bean and the endpoint in SEPARATE files still resolve.
   *
   * <p>This is the real-world shape: a request bean is its own type, usually in its own file. The
   * bean class produced no function at all, so its annotation could only be found through the
   * merged program's class field-annotation table -- which is aggregated across compilation units.
   *
   * <p>Without the aggregation, this case would resolve the bean in the same file and fail in a
   * different one, which is the kind of gap that only shows up in production code.
   */
  @Test
  void aBeanInAnotherFileStillResolves() throws Exception {
    Path beanFile = tmp.resolve("Parameters.java");
    Files.writeString(
        beanFile,
        """
        package app;
        import org.jboss.resteasy.reactive.RestQuery;
        public class Parameters {
          @RestQuery("customer")
          public String customer;
        }
        """);
    Path endpointFile = tmp.resolve("OrderResource.java");
    Files.writeString(
        endpointFile,
        """
        package app;
        import org.jboss.resteasy.reactive.RestBeanParam;
        public class OrderResource {
          private final java.sql.Statement statement = null;
          public void search(@RestBeanParam Parameters params) {
            statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
          }
        }
        """);

    List<TacProgram> programs = new ArrayList<>();
    for (Path file : List.of(beanFile, endpointFile)) {
      ParsedSource ps = new JavaSourceParser().parse(file);
      programs.add(new TacTranslator(ps).translate());
    }

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
            .runWithGlobalStore(programs, 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }

    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("a bean declared in another file must still name its binding annotation")
        .isEqualTo("param:params.customer@quarkus-rest:RestQuery");
  }

  /**
   * The container parameter is a CARRIER, not a source.
   *
   * <p>A bean parameter is not a request value: the request values are its FIELDS. If the parameter
   * itself were labelled as a framework entry point, the report would claim that the whole bean
   * came from the request -- a different (and unproven) statement -- and a finding would name "the
   * bean" instead of the field that actually entered the program.
   *
   * <p>The label keeps the {@code "param:"} prefix (the parameter IS a parameter) and adds a {@code
   * "carrier:"} qualifier.
   */
  @Test
  void theContainerParameterIsACarrierNotASource() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            import org.jboss.resteasy.reactive.RestQuery;
            public class CarrierFilter {
              @RestQuery("customer")
              public String customer;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RestBeanParam CarrierFilter params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
              }
            }
            """,
            "CarrierLabel.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("the source must be the FIELD, not the carrier")
        .isEqualTo("param:params.customer@quarkus-rest:RestQuery");
  }

  /**
   * The carrier label is what a container parameter carries when it is NOT read as a field.
   *
   * <p>This is the direct observation of the carrier label: the parameter is handed to a method
   * that is not a taint sink, so nothing fires, but the engine's own view of the parameter must
   * still say "carrier" rather than "source".
   */
  @Test
  void theContainerParameterIsLabelledAsACarrier() throws Exception {
    TacProgram program =
        translate(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            public class CarrierFilter {
              public String customer;
            }
            public class C {
              public void search(@RestBeanParam CarrierFilter params) {
                consume(params);
              }
              private void consume(CarrierFilter p) {
                int x = 1;
              }
            }
            """,
            "CarrierObservation.java");

    TacFunction search =
        program.functions().stream()
            .filter(fn -> fn.name().equals("search"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the endpoint function must be present"));

    assertThat(FrameworkFieldBindings.containerAnnotationFor(search, "params"))
        .as("the parameter must be recognised as a container")
        .isEqualTo("RestBeanParam");
    assertThat(FrameworkEntryPoints.entryPointFor(search, "params"))
        .as("a container parameter is not a parameter-level entry point")
        .isNull();
  }

  /**
   * Control: a field of the SAME bean with NO binding annotation is not a framework entry point.
   *
   * <p>Without this control the tests above would pass even if EVERY field of every bean were
   * labelled as a framework entry point.
   */
  @Test
  void anUnboundBeanFieldKeepsThePlainLabel() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            import org.jboss.resteasy.reactive.RestQuery;
            public class Parameters {
              @RestQuery("customer")
              public String customer;
              public String plain;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(@RestBeanParam Parameters params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.plain + "'");
              }
            }
            """,
            "UnboundBeanField.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("a field with no binding annotation is not a framework entry point")
        .doesNotContain("RestQuery");
  }

  /**
   * Control: a bean parameter with NO container annotation is not a container binding.
   *
   * <p>The value still reaches the sink as a plain parameter, but the report must not claim a
   * framework entry point that the code does not have.
   */
  @Test
  void aBeanWithoutAContainerAnnotationIsNotAFrameworkEntryPoint() throws Exception {
    List<Finding> out =
        scan(
            """
            package app;
            import org.jboss.resteasy.reactive.RestQuery;
            public class Parameters {
              @RestQuery("customer")
              public String customer;
            }
            public class C {
              private final java.sql.Statement statement = null;
              public void search(Parameters params) {
                statement.executeQuery("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
              }
            }
            """,
            "NoContainerAnnotation.java");
    assertThat(out).as("the sink must be reached").hasSize(1);
    assertThat(sourceLabelOf(out.get(0)))
        .as("without @RestBeanParam there is no framework entry point")
        .doesNotContain("RestQuery");
  }
}
