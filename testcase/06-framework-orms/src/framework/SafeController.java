package com.acme.shop.framework;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller whose framework-bound parameter never reaches a sink.
 *
 * <p>This is the PRECISION PROBE for the framework entry-point source model. The parameter is
 * labelled as a request-bound value, so the taint engine knows it is attacker-controlled. If the
 * value never reaches a sink, nothing must be reported -- a framework model that taints everything
 * a request touches would report here.
 */
@RestController
public class SafeController {

  private final OrderRepository repository;

  public SafeController(OrderRepository repository) {
    this.repository = repository;
  }

  /** SAFE: the request parameter is only logged. */
  public void preview(@RequestParam("customer") String customer) {
    repository.logOnly(customer);
  }
}
