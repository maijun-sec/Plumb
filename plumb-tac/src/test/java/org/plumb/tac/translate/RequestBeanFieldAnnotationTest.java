package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The IR keeps the field annotations of a class that declares no function at all.
 *
 * <p>A request bean is exactly that shape:
 *
 * <pre>
 *   public class Parameters {
 *     &#64;RestQuery("customer")
 *     public String customer;      // no initializer, no methods
 *   }
 * </pre>
 *
 * <p>A function's own {@code fieldAnnotations()} describes the fields of the class that DECLARES
 * the function, and it is only populated when that class happens to emit a synthetic {@code
 * <clinit>} or {@code <init>} -- which needs a static field or an initialized instance field. A
 * bean has neither, so it produced NO function and its annotation was invisible to the whole
 * engine.
 *
 * <p>The program-level table fixes that: it records every declared class's field annotations,
 * whether or not the class has functions.
 */
class RequestBeanFieldAnnotationTest {

  @TempDir Path tmp;

  private TacProgram translate(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  private static final String BEAN_SOURCE =
      """
      package app;
      import org.jboss.resteasy.reactive.RestQuery;
      public class Parameters {
        @RestQuery("customer")
        public String customer;
        public String plain;
      }
      """;

  /** A class that declares no function still contributes its field annotations. */
  @Test
  void aClassWithoutFunctionsStillContributesItsFieldAnnotations() throws Exception {
    TacProgram program = translate(BEAN_SOURCE, "Parameters.java");

    assertThat(program.functions())
        .as("a bean with no methods and no initializers produces no function")
        .isEmpty();
    assertThat(program.fieldAnnotationsOf("Parameters", "customer"))
        .as("the bean's binding annotation must be visible")
        .containsExactly("RestQuery");
  }

  /** A field with no annotation is simply absent -- an empty list, not a wrong one. */
  @Test
  void aFieldWithoutAnnotationsIsNotListed() throws Exception {
    TacProgram program = translate(BEAN_SOURCE, "Parameters2.java");

    assertThat(program.fieldAnnotationsOf("Parameters", "plain")).isEmpty();
    assertThat(program.fieldAnnotationsOf("Parameters", "missing")).isEmpty();
    assertThat(program.fieldAnnotationsOf("NoSuchClass", "customer")).isEmpty();
  }

  /** The table is keyed by the DECLARING class, so two classes do not collide. */
  @Test
  void twoClassesKeepTheirOwnFieldAnnotationsApart() throws Exception {
    TacProgram program =
        translate(
            """
            package app;
            import org.jboss.resteasy.reactive.RestQuery;
            import jakarta.ws.rs.QueryParam;
            public class First {
              @RestQuery("a")
              public String a;
            }
            public class Second {
              @QueryParam("b")
              public String b;
            }
            """,
            "TwoClasses.java");

    assertThat(program.fieldAnnotationsOf("First", "a")).containsExactly("RestQuery");
    assertThat(program.fieldAnnotationsOf("Second", "b")).containsExactly("QueryParam");
    assertThat(program.fieldAnnotationsOf("First", "b")).isEmpty();
  }

  /** The merged program aggregates the tables across compilation units. */
  @Test
  void theMergedProgramAggregatesFieldAnnotationsAcrossFiles() throws Exception {
    TacProgram bean = translate(BEAN_SOURCE, "Bean.java");
    TacProgram endpoint =
        translate(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            public class OrderResource {
              private final java.sql.Statement statement = null;
              public void search(@RestBeanParam Parameters params) {
                statement.executeQuery("SELECT 1");
              }
            }
            """,
            "Endpoint.java");

    MergedProgram merged = new MergedProgram(List.of(bean, endpoint));
    TacProgram view = merged.asProgram();

    assertThat(view.fieldAnnotationsOf("Parameters", "customer"))
        .as("a bean declared in another file must still be visible")
        .containsExactly("RestQuery");
  }

  /**
   * The FIELDLOAD carries the scope's TYPE and the field's NAME.
   *
   * <p>The DFA needs both to resolve which bean's field was read: the type says whose fields to
   * look at, the name says which one.
   */
  @Test
  void theFieldLoadCarriesTheScopeTypeAndTheFieldName() throws Exception {
    TacProgram program =
        translate(
            """
            package app;
            import org.jboss.resteasy.reactive.RestBeanParam;
            public class Parameters {
              public String customer;
            }
            public class OrderResource {
              public void search(@RestBeanParam Parameters params) {
                String x = params.customer;
              }
            }
            """,
            "FieldLoadShape.java");

    TacFunction search =
        program.functions().stream()
            .filter(fn -> fn.name().equals("search"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the endpoint function must be present"));

    TacInstruction load =
        search.instructions().stream()
            .filter(ins -> ins.kind == TacInstruction.Kind.FIELDLOAD)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the field read must be a FIELDLOAD"));

    assertThat(load.fieldName).isEqualTo("customer");
    assertThat(load.lhs1).isInstanceOf(TacOperand.TacVar.class);
    TacOperand.TacVar scope = (TacOperand.TacVar) load.lhs1;
    assertThat(scope.name()).isEqualTo("params");
    assertThat(scope.type().className()).isEqualTo("Parameters");
  }
}
