package org.plumb.dfa.taint;

import java.util.List;
import java.util.Map;
import org.plumb.rules.framework.FrameworkInputAnnotations;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * Framework entry points that bind a FIELD of a request bean.
 *
 * <p>A bean parameter is not itself the request value. The framework binds each FIELD of the bean
 * class to its own request value, so the binding annotation lives on the bean's FIELD:
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
 * <p>The parameter only names the CONTAINER. Reading {@code params.customer} is what actually
 * brings the request value into the program, and that is the point the taint flow has to start
 * from.
 *
 * <p>Resolving this needs three facts, and only the first two are in the endpoint function:
 *
 * <ol>
 *   <li>the parameter's container annotation ({@code @RestBeanParam}) -- {@link
 *       TacFunction#paramAnnotations()};
 *   <li>the bean TYPE and the FIELD NAME -- the {@code FIELDLOAD} instruction carries both;
 *   <li>the binding annotation on that field -- which lives in a DIFFERENT compilation unit, in the
 *       bean's own class, and is not reachable from the endpoint function at all.
 * </ol>
 *
 * <p>The third fact is why this class exists. {@link TacFunction#fieldAnnotations()} describes the
 * fields of the class that DECLARES the function, and a request bean declares no function at all
 * (no methods, no initializers), so it produced no function and its annotations were invisible. The
 * TAC now records every declared class's field annotations on the program itself ({@link
 * TacProgram#classFieldAnnotations()}), and the merged program aggregates them across compilation
 * units.
 *
 * <p><b>What this class does NOT claim.</b> A missing bean class, a missing field, or a field with
 * no binding annotation means UNKNOWN -- never "safe". The resolver returns {@code null} in those
 * cases, and the caller falls back to the ordinary parameter treatment. Nothing here suppresses a
 * finding; it only adds a more precise SOURCE LABEL.
 */
public final class FrameworkFieldBindings {

  /**
   * A field bound by a framework entry point: which annotation bound it, and which framework it
   * belongs to.
   */
  public record FieldBinding(String annotation, String framework) {}

  private FrameworkFieldBindings() {}

  /**
   * The container annotation on {@code paramName}, or {@code null} when the parameter is not a
   * request bean.
   *
   * @param fn the function whose parameters are being inspected
   * @param paramName the parameter's name, as the IR records it
   */
  public static String containerAnnotationFor(TacFunction fn, String paramName) {
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
      if (FrameworkInputAnnotations.isContainerAnnotation(annotation)) {
        return annotation;
      }
    }
    return null;
  }

  /**
   * The binding annotation on {@code beanType}.{@code fieldName}, or {@code null} when the field
   * carries none.
   *
   * @param program the merged program, which holds every declared class's field annotations
   * @param beanType the bean's type name, as the {@code FIELDLOAD}'s scope carries it
   * @param fieldName the field's name, as the {@code FIELDLOAD} carries it
   */
  public static String bindingAnnotationFor(TacProgram program, String beanType, String fieldName) {
    if (program == null || beanType == null || fieldName == null) {
      return null;
    }
    List<String> annotations = program.fieldAnnotationsOf(beanType, fieldName);
    for (String annotation : annotations) {
      if (FrameworkInputAnnotations.isUserInputAnnotation(annotation)) {
        return annotation;
      }
    }
    return null;
  }

  /**
   * The source label a container-bound field carries.
   *
   * <p>The shape mirrors the parameter-level label -- {@code "param:<name>@<framework>:<
   * Annotation>"} -- so every consumer that already understands a parameter label understands this
   * one too. The name is the qualified field expression, {@code "params.customer"}, because that is
   * what a reader needs to see: which bean, and which field of it.
   *
   * <p>Returns {@code null} when the field is not bound by a framework entry point.
   *
   * @param program the merged program
   * @param paramName the container parameter's name
   * @param beanType the bean's type name
   * @param fieldName the field's name
   */
  public static String sourceLabelFor(
      TacProgram program, String paramName, String beanType, String fieldName) {
    String annotation = bindingAnnotationFor(program, beanType, fieldName);
    if (annotation == null) {
      return null;
    }
    String framework = FrameworkInputAnnotations.frameworkOf(annotation);
    if (FrameworkInputAnnotations.FRAMEWORK_UNKNOWN.equals(framework)) {
      return null;
    }
    return "param:" + paramName + "." + fieldName + "@" + framework + ":" + annotation;
  }

  /**
   * The bean's type name as a {@code FIELDLOAD}'s scope carries it, or {@code null} when the scope
   * is not a typed variable.
   */
  public static String beanTypeOf(TacOperand scope) {
    if (!(scope instanceof TacOperand.TacVar v)) {
      return null;
    }
    if (v.type() == null || v.type().className() == null) {
      return null;
    }
    String className = v.type().className();
    if (className.isEmpty()) {
      return null;
    }
    return className;
  }
}
