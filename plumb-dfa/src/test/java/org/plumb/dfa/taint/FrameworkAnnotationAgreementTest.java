package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.plumb.rules.framework.FrameworkInputAnnotations;

/**
 * The taint engine and the input-validation checker agree about what "user input" means.
 *
 * <p>These two layers used to keep SEPARATE lists of the framework annotations that bind a request
 * value to a parameter, and they drifted:
 *
 * <ul>
 *   <li>Thirteen annotations were an HTTP trust boundary to the taint engine but invisible to the
 *       validation checker. A {@code @RestQuery} parameter was tracked as attacker-controlled by
 *       one layer and treated as trusted-by-default by the other.
 *   <li>The taint engine listed three names no framework defines, one of which ({@code
 *       CookieValue2}) was a typo.
 *   <li>Seven real annotations were missing from the taint engine, so those parameters were not
 *       taint sources at all.
 * </ul>
 *
 * <p>Both layers now read {@link FrameworkInputAnnotations}. This test pins the agreement from the
 * side that can see both: the DFA module depends on the rules module, so it can read the catalogue
 * and compare it with what each layer actually consults.
 */
class FrameworkAnnotationAgreementTest {

  /**
   * The taint engine's own set is the shared catalogue, not a copy.
   *
   * <p>Not merely "the same contents" -- the SAME object. A copy is a second source of truth, and a
   * second source of truth is what caused the drift.
   */
  @Test
  void theTaintEngineUsesTheSharedCatalogueItself() {
    assertThat(FrameworkEntryPoints.USER_INPUT_PARAM_ANNOTATIONS)
        .as("the taint engine must hold the catalogue, not a copy of it")
        .isSameAs(FrameworkInputAnnotations.USER_INPUT_PARAM_ANNOTATIONS);
  }

  /** Every catalogue annotation is a taint source for the engine. */
  @Test
  void everyCatalogueAnnotationIsATaintSource() {
    Set<String> catalogue = FrameworkInputAnnotations.USER_INPUT_PARAM_ANNOTATIONS;
    assertThat(catalogue).isNotEmpty();
    for (String name : catalogue) {
      assertThat(FrameworkEntryPoints.USER_INPUT_PARAM_ANNOTATIONS)
          .as("%s must be a taint source", name)
          .contains(name);
    }
  }

  /**
   * The name the taint engine used to invent is gone.
   *
   * <p>{@code CookieValue2} was a typo that never matched real code, and its presence hid the fact
   * that Micronaut's real {@code @CookieValue} was handled by a different entry.
   */
  @Test
  void theInventedNamesAreGone() {
    assertThat(FrameworkEntryPoints.USER_INPUT_PARAM_ANNOTATIONS)
        .doesNotContain("CookieValue2", "HeaderValue", "PathValue");
  }

  /**
   * The annotations that were missing from the taint engine are now present.
   *
   * <p>Before the shared catalogue, a Micronaut {@code @Header} parameter and a Quarkus
   * {@code @RestCookie} parameter were NOT taint sources: a value from the request reached a SQL
   * sink with no taint flow to follow.
   */
  @Test
  void thePreviouslyMissingAnnotationsAreNowSources() {
    assertThat(FrameworkEntryPoints.USER_INPUT_PARAM_ANNOTATIONS)
        .contains(
            "Body", "Header", "Part", "RequestBean", "RestBeanParam", "RestCookie", "RestMatrix");
  }
}
