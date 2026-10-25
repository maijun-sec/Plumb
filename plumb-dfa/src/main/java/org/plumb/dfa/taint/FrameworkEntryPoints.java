package org.plumb.dfa.taint;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.framework.FrameworkInputAnnotations;
import org.plumb.tac.ir.TacFunction;

/**
 * Framework entry points: parameter annotations that BIND a request value to a method parameter.
 *
 * <p>A servlet-aware scanner recognises {@code HttpServletRequest.getParameter(...)} as a taint
 * source. A Spring MVC application has no such call anywhere: the framework binds the request value
 * to the method parameter BY ANNOTATION, and the controller body never touches a request object.
 *
 * <pre>
 *   &#64;GetMapping("/orders")
 *   public void search(&#64;RequestParam("customer") String customer) {
 *     repository.findByCustomer(customer);   // the value came from the HTTP request
 *   }
 * </pre>
 *
 * <p>So the source is not a CALL -- it is a PARAMETER ANNOTATION. The IR already records these
 * ({@link TacFunction#paramAnnotations()}), keyed by parameter name, with the annotation's simple
 * name.
 *
 * <p>This class answers one question: is a given parameter bound to a framework entry point, and
 * which one? The taint engine uses it to label such a parameter with the framework annotation
 * rather than a bare parameter name, so a finding names the actual trust boundary instead of an
 * opaque local.
 *
 * <p>The annotation list is the same one the AST checkers use for input validation ({@code
 * MissingInputValidationCheck}), so the two layers agree on what "user input" means in this
 * codebase.
 */
public final class FrameworkEntryPoints {

  /**
   * Parameter annotations that bind a value supplied by the caller.
   *
   * <p>The list lives in {@link FrameworkInputAnnotations}, which is the single catalogue shared
   * with the AST input-validation checker. It must not be copied here: the two layers have to agree
   * about what "user input" means, and they previously did not -- thirteen annotations were an HTTP
   * trust boundary to the taint engine but invisible to the validation checker, and three names
   * here were not defined by any framework at all.
   *
   * <p>Names are matched by SIMPLE NAME, which is what the IR records: {@code @RequestParam} and a
   * hypothetical {@code @org.acme.RequestParam} are the same name to this model.
   */
  public static final Set<String> USER_INPUT_PARAM_ANNOTATIONS =
      FrameworkInputAnnotations.USER_INPUT_PARAM_ANNOTATIONS;

  /**
   * The class-level annotation groups that make a method a framework endpoint. Used to qualify a
   * source label, so the finding says WHERE the value entered ("spring-mvc") rather than only WHICH
   * annotation bound it.
   *
   * <p>The map lives in the shared catalogue. It is only a HINT: JAX-RS and Quarkus REST use the
   * SAME endpoint annotations ({@code @Path}, {@code @GET}, ...), so the endpoint annotation cannot
   * distinguish them. The binding annotation on the parameter can, and it wins.
   */
  private static final Map<String, String> FRAMEWORK_BY_ENDPOINT_ANNOTATION =
      Map.ofEntries(
          Map.entry("RestController", "spring-mvc"),
          Map.entry("RequestMapping", "spring-mvc"),
          Map.entry("GetMapping", "spring-mvc"),
          Map.entry("PostMapping", "spring-mvc"),
          Map.entry("PutMapping", "spring-mvc"),
          Map.entry("DeleteMapping", "spring-mvc"),
          Map.entry("PatchMapping", "spring-mvc"),
          Map.entry("Path", "jax-rs"),
          Map.entry("GET", "jax-rs"),
          Map.entry("POST", "jax-rs"),
          Map.entry("PUT", "jax-rs"),
          Map.entry("DELETE", "jax-rs"),
          Map.entry("HEAD", "jax-rs"),
          Map.entry("OPTIONS", "jax-rs"),
          Map.entry("PATCH", "jax-rs"),
          Map.entry("Controller", "micronaut"),
          Map.entry("Get", "micronaut"),
          Map.entry("Post", "micronaut"),
          Map.entry("Put", "micronaut"),
          Map.entry("Delete", "micronaut"),
          Map.entry("Patch", "micronaut"),
          Map.entry("Head", "micronaut"),
          Map.entry("Options", "micronaut"));

  /** A parameter bound by a framework entry point, and which annotation bound it. */
  public record EntryPoint(String annotation, String framework) {}

  private FrameworkEntryPoints() {}

  /**
   * The framework entry point bound to {@code paramName}, or {@code null} when the parameter is not
   * bound by one.
   *
   * @param fn the function whose parameters are being inspected
   * @param paramName the parameter's name, as the IR records it
   */
  public static EntryPoint entryPointFor(TacFunction fn, String paramName) {
    if (fn == null || paramName == null) {
      return null;
    }
    Map<String, List<String>> paramAnns = fn.paramAnnotations();
    if (paramAnns == null || paramAnns.isEmpty()) {
      return null;
    }
    List<String> annotations = paramAnns.get(paramName);
    if (annotations == null || annotations.isEmpty()) {
      return null;
    }
    for (String annotation : annotations) {
      if (annotation != null && USER_INPUT_PARAM_ANNOTATIONS.contains(annotation)) {
        return new EntryPoint(annotation, frameworkOf(fn, annotation));
      }
    }
    return null;
  }

  /**
   * The label a framework-bound parameter carries as its taint source.
   *
   * <p>The label keeps the {@code "param:"} prefix every other taint site already understands, and
   * appends the framework qualifier after an {@code '@'}: {@code
   * "param:customer@spring-mvc:RequestParam"}.
   *
   * <p>The prefix is kept deliberately. Dozens of places in the taint engine ask "is this label a
   * parameter label?" with a {@code startsWith("param:")} test, and a few parse the parameter NAME
   * out of the label. Changing the prefix would silently disable all of them. The qualifier is
   * additive: it tells a reader which trust boundary the value came from, and it changes nothing
   * about how the taint flows.
   */
  public static String sourceLabelFor(TacFunction fn, String paramName) {
    EntryPoint ep = entryPointFor(fn, paramName);
    if (ep == null) {
      return null;
    }
    return "param:" + paramName + "@" + ep.framework() + ":" + ep.annotation();
  }

  /**
   * The parameter name a taint label refers to, or {@code null} when the label is not a parameter
   * label.
   *
   * <p>Handles the framework qualifier: {@code "param:customer@spring-mvc:RequestParam"} yields
   * {@code "customer"}.
   */
  public static String paramNameOf(String label) {
    if (label == null || !label.startsWith("param:")) {
      return null;
    }
    String rest = label.substring("param:".length());
    int at = rest.indexOf('@');
    return at >= 0 ? rest.substring(0, at) : rest;
  }

  /**
   * The framework qualifier a taint label carries, or {@code null}.
   *
   * <p>{@code "param:customer@spring-mvc:RequestParam"} yields {@code "spring-mvc:RequestParam"}.
   */
  public static String qualifierOf(String label) {
    if (label == null || !label.startsWith("param:")) {
      return null;
    }
    int at = label.indexOf('@');
    return at >= 0 ? label.substring(at + 1) : null;
  }

  /**
   * The framework a function belongs to, inferred from the binding annotation on the parameter and,
   * failing that, from the class-level and method-level endpoint annotations.
   *
   * <p>The BINDING annotation wins, because it is unambiguous. JAX-RS and Quarkus REST share the
   * same endpoint annotations ({@code @Path}, {@code @GET}, {@code @POST}, ...), so an endpoint
   * annotation cannot tell a Quarkus resource from a JAX-RS one -- but their binding annotations
   * are different ({@code @RestQuery} versus {@code @QueryParam}), and that difference is exactly
   * what a reader needs to see.
   *
   * <p>Within the endpoint annotations, the method-level ones win over the class-level ones,
   * because a mapping annotation is specific to the method that actually receives the request.
   */
  private static String frameworkOf(TacFunction fn, String bindingAnnotation) {
    String fromBinding = FrameworkInputAnnotations.frameworkOf(bindingAnnotation);
    if (!FrameworkInputAnnotations.FRAMEWORK_UNKNOWN.equals(fromBinding)) {
      return fromBinding;
    }
    List<String> methodAnns = fn.methodAnnotations();
    if (methodAnns != null) {
      for (String annotation : methodAnns) {
        String framework = FRAMEWORK_BY_ENDPOINT_ANNOTATION.get(annotation);
        if (framework != null) {
          return framework;
        }
      }
    }
    List<String> classAnns = fn.classAnnotations();
    if (classAnns != null) {
      for (String annotation : classAnns) {
        String framework = FRAMEWORK_BY_ENDPOINT_ANNOTATION.get(annotation);
        if (framework != null) {
          return framework;
        }
      }
    }
    return FrameworkInputAnnotations.FRAMEWORK_UNKNOWN;
  }
}
