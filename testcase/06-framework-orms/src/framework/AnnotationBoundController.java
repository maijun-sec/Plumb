package com.acme.shop.framework;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Spring MVC controller with ANNOTATION-BOUND parameters.
 *
 * <p>This is the shape that distinguishes a framework-aware scanner from a servlet-aware one. There
 * is no {@code getParameter()} call anywhere: Spring binds the request value to the method parameter
 * by annotation. A scanner that only knows {@code HttpServletRequest.getParameter} sees no source
 * at all.
 *
 * <p>The value reaches the SQL sink in ANOTHER file, through the repository. The finding is
 * attributed to the sink, which is where the engine reports it: the taint engine records a
 * parameter as a source at the function's entry boundary, and the cross-file chain is summarised
 * rather than replayed into the trace. This is the same attribution behaviour as category 01.
 */
@RestController
public class AnnotationBoundController {

  private final OrderRepository repository;

  public AnnotationBoundController(OrderRepository repository) {
    this.repository = repository;
  }

  /** The request parameter reaches the repository, which reaches the SQL. */
  public void search(@RequestParam("customer") String customer) {
    repository.findByCustomer(customer);
  }

  /** The path variable reaches the repository too. */
  public void rename(@PathVariable("id") String id, @RequestParam("name") String name) {
    repository.rename(id, name);
  }

  /** SAFE: the request parameter never reaches a sink. */
  public void preview(@RequestParam("customer") String customer) {
    repository.logOnly(customer);
  }
}
