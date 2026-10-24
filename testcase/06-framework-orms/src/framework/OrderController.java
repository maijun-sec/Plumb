package com.acme.shop.framework;

import org.springframework.stereotype.Service;

/**
 * A Spring MVC controller. The request data is bound to METHOD PARAMETERS by the framework, so
 * there is no {@code getParameter()} call anywhere -- the value reaches the repository through
 * the parameter itself.
 */
@Service
public class OrderController {

  private final OrderRepository repository;

  public OrderController(OrderRepository repository) {
    this.repository = repository;
  }

  /** The request parameter reaches the repository, which reaches the SQL. */
  public void search(String customer) {
    repository.findByCustomer(customer);
  }

  /** The request parameter reaches the repository through a write statement. */
  public void rename(String customer, String newName) {
    repository.rename(customer, newName);
  }

  /** SAFE: the request parameter never reaches a sink. */
  public void preview(String customer) {
    repository.logOnly(customer);
  }
}
