package org.plumb.rules.framework;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The framework binding-annotation catalogue is complete and self-consistent.
 *
 * <p>This catalogue is the single definition of "a parameter bound to a request value". Two layers
 * read it: the taint engine (which decides where a taint flow starts) and the input-validation
 * checker (which demands that such a value be validated).
 *
 * <p>Before it existed, each layer declared its own list and they drifted. The drift was not
 * symmetric: thirteen annotations were an HTTP trust boundary to the taint engine but invisible to
 * the validation checker, and the taint engine listed three names that no framework defines at all
 * -- one of which, {@code CookieValue2}, was a typo.
 *
 * <p>The catalogue also distinguishes CONTAINER annotations -- the ones that bind a bean of
 * parameters rather than a single value -- because the two have different meanings for the taint
 * engine. A container parameter is a carrier: its FIELDS are the sources.
 */
class FrameworkInputAnnotationsTest {

  /** The annotations each framework actually defines, checked against its documentation. */
  @Test
  void everySpringMvcBindingAnnotationIsPresent() {
    for (String name :
        new String[] {
          "RequestParam",
          "PathVariable",
          "RequestBody",
          "RequestHeader",
          "RequestAttribute",
          "CookieValue",
          "RequestPart",
          "ModelAttribute",
          "SessionAttribute",
          "MatrixVariable"
        }) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("Spring MVC binding annotation %s", name)
          .isTrue();
      assertThat(FrameworkInputAnnotations.frameworkOf(name))
          .as("framework of %s", name)
          .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_SPRING_MVC);
    }
  }

  /** The JAX-RS / Jakarta REST binding annotations, checked against the Jakarta REST spec. */
  @Test
  void everyJaxRsBindingAnnotationIsPresent() {
    for (String name :
        new String[] {
          "QueryParam",
          "PathParam",
          "HeaderParam",
          "CookieParam",
          "FormParam",
          "BeanParam",
          "MatrixParam"
        }) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("JAX-RS binding annotation %s", name)
          .isTrue();
      assertThat(FrameworkInputAnnotations.frameworkOf(name))
          .as("framework of %s", name)
          .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_JAX_RS);
    }
  }

  /**
   * The Quarkus REST binding annotations, checked against the Quarkus REST guide.
   *
   * <p>{@code @RestCookie} and {@code @RestMatrix} were missing before: a Quarkus cookie or matrix
   * parameter was not a taint source at all.
   */
  @Test
  void everyQuarkusRestBindingAnnotationIsPresent() {
    for (String name :
        new String[] {
          "RestPath",
          "RestQuery",
          "RestHeader",
          "RestCookie",
          "RestForm",
          "RestMatrix",
          "RestBeanParam"
        }) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("Quarkus REST binding annotation %s", name)
          .isTrue();
      assertThat(FrameworkInputAnnotations.frameworkOf(name))
          .as("framework of %s", name)
          .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_QUARKUS_REST);
    }
  }

  /**
   * The Micronaut binding annotations, checked against the Micronaut HTTP guide.
   *
   * <p>Micronaut names them differently from everyone else: {@code @Header} rather than
   * {@code @RequestHeader}, {@code @QueryValue} rather than {@code @QueryParam}, {@code @Body}
   * rather than {@code @RequestBody}. The taint engine used to list {@code HeaderValue} and {@code
   * PathValue} -- names no framework defines.
   */
  @Test
  void everyMicronautBindingAnnotationIsPresent() {
    for (String name : new String[] {"Body", "Header", "QueryValue", "Part", "RequestBean"}) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("Micronaut binding annotation %s", name)
          .isTrue();
      assertThat(FrameworkInputAnnotations.frameworkOf(name))
          .as("framework of %s", name)
          .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_MICRONAUT);
    }
  }

  /**
   * The names no framework defines are NOT in the catalogue.
   *
   * <p>{@code CookieValue2} was a typo in the taint engine's list. {@code HeaderValue} and {@code
   * PathValue} look like plausible Micronaut names but Micronaut defines {@code Header}, {@code
   * QueryValue} and {@code PathVariable}. A name that no framework defines is a name that will
   * never match real code, so it is dead weight that hides a real gap.
   */
  @Test
  void namesNoFrameworkDefinesAreNotPresent() {
    for (String name : new String[] {"CookieValue2", "HeaderValue", "PathValue"}) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("no framework defines %s", name)
          .isFalse();
    }
  }

  /** An annotation that says nothing about a request value is not a binding annotation. */
  @Test
  void anUnrelatedAnnotationIsNotPresent() {
    for (String name : new String[] {"Autowired", "Valid", "Validated", "Override", "Nullable"}) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("%s does not bind a request value", name)
          .isFalse();
    }
  }

  /** A null annotation is not a binding annotation, and must not throw. */
  @Test
  void aNullAnnotationIsNotPresent() {
    assertThat(FrameworkInputAnnotations.isUserInputAnnotation(null)).isFalse();
    assertThat(FrameworkInputAnnotations.frameworkOf(null))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_UNKNOWN);
  }

  /** The catalogue is not empty, and its size is what the set reports. */
  @Test
  void theCatalogueIsNotEmptyAndItsSizeAgrees() {
    assertThat(FrameworkInputAnnotations.size()).isGreaterThan(20);
    assertThat(FrameworkInputAnnotations.USER_INPUT_PARAM_ANNOTATIONS)
        .hasSize(FrameworkInputAnnotations.size());
  }

  /** The set is unmodifiable: a caller cannot corrupt the shared catalogue. */
  @Test
  void theSharedSetCannotBeModified() {
    assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> FrameworkInputAnnotations.USER_INPUT_PARAM_ANNOTATIONS.add("Injected")))
        .isNotNull();
  }

  /**
   * The endpoint map identifies the frameworks, and is only a HINT.
   *
   * <p>JAX-RS and Quarkus REST share {@code @Path} and {@code @GET}, so the endpoint map cannot
   * distinguish them -- the binding annotation on the parameter does. This test records that
   * limitation rather than pretending it away.
   */
  @Test
  void theEndpointMapIdentifiesFrameworksButCannotSeparateJaxRsFromQuarkus() {
    assertThat(FrameworkInputAnnotations.frameworkOfEndpointAnnotation("GetMapping"))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_SPRING_MVC);
    assertThat(FrameworkInputAnnotations.frameworkOfEndpointAnnotation("Path"))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_JAX_RS);
    assertThat(FrameworkInputAnnotations.frameworkOfEndpointAnnotation("Get"))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_MICRONAUT);
    assertThat(FrameworkInputAnnotations.frameworkOfEndpointAnnotation("NotAnEndpoint")).isNull();
    assertThat(FrameworkInputAnnotations.frameworkOfEndpointAnnotation(null)).isNull();
  }

  /**
   * The container annotations are present, and each names its own framework.
   *
   * <p>A container annotation binds a BEAN of parameters, not a value: {@code @BeanParam} (JAX-RS),
   * {@code @RestBeanParam} (Quarkus REST) and {@code @RequestBean} (Micronaut). The parameter they
   * sit on is a carrier -- the request values are the bean's FIELDS.
   */
  @Test
  void everyContainerAnnotationIsPresent() {
    assertThat(FrameworkInputAnnotations.isContainerAnnotation("BeanParam")).isTrue();
    assertThat(FrameworkInputAnnotations.frameworkOfContainerAnnotation("BeanParam"))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_JAX_RS);
    assertThat(FrameworkInputAnnotations.isContainerAnnotation("RestBeanParam")).isTrue();
    assertThat(FrameworkInputAnnotations.frameworkOfContainerAnnotation("RestBeanParam"))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_QUARKUS_REST);
    assertThat(FrameworkInputAnnotations.isContainerAnnotation("RequestBean")).isTrue();
    assertThat(FrameworkInputAnnotations.frameworkOfContainerAnnotation("RequestBean"))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_MICRONAUT);
  }

  /**
   * A container annotation is also a binding annotation, so the two sets OVERLAP.
   *
   * <p>The container set is not a partition of the binding set: {@code @RestBeanParam} binds a
   * request-supplied bean, so it is a binding annotation too. The container set is an ADDITIONAL
   * classification, used to tell "this parameter is a carrier" from "this parameter is a value".
   */
  @Test
  void aContainerAnnotationIsAlsoABindingAnnotation() {
    for (String name : FrameworkInputAnnotations.CONTAINER_BINDING_ANNOTATIONS) {
      assertThat(FrameworkInputAnnotations.isUserInputAnnotation(name))
          .as("%s binds a request-supplied bean, so it binds a request value too", name)
          .isTrue();
    }
  }

  /**
   * A plain binding annotation is NOT a container annotation.
   *
   * <p>This is the direction that matters: {@code @RequestParam} binds a VALUE to the parameter, so
   * the parameter is the source. Treating it as a container would move the source to a field that
   * does not exist.
   */
  @Test
  void aPlainBindingAnnotationIsNotAContainerAnnotation() {
    for (String name :
        new String[] {
          "RequestParam", "PathVariable", "QueryParam", "RestQuery", "QueryValue", "Header", "Body"
        }) {
      assertThat(FrameworkInputAnnotations.isContainerAnnotation(name))
          .as("%s binds a value, not a bean of values", name)
          .isFalse();
    }
  }

  /** An unrelated annotation is not a container annotation either. */
  @Test
  void anUnrelatedAnnotationIsNotAContainerAnnotation() {
    for (String name :
        new String[] {"Autowired", "Valid", "Override", "Nullable", "NotAnAnnotation"}) {
      assertThat(FrameworkInputAnnotations.isContainerAnnotation(name)).isFalse();
    }
    assertThat(FrameworkInputAnnotations.isContainerAnnotation(null)).isFalse();
    assertThat(FrameworkInputAnnotations.frameworkOfContainerAnnotation(null))
        .isEqualTo(FrameworkInputAnnotations.FRAMEWORK_UNKNOWN);
  }

  /** The container set is unmodifiable, like every other shared set. */
  @Test
  void theContainerSetCannotBeModified() {
    assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> FrameworkInputAnnotations.CONTAINER_BINDING_ANNOTATIONS.add("Injected")))
        .isNotNull();
  }
}
