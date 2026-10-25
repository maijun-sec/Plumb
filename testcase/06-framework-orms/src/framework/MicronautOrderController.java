package com.acme.shop.framework;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A Micronaut controller with ANNOTATION-BOUND parameters.
 *
 * <p>Micronaut names its binding annotations differently from every other framework: {@code
 * @QueryValue} rather than {@code @QueryParam} or {@code @RestQuery}, and {@code @Header} rather than
 * {@code @RequestHeader}. A scanner that recognises Spring and JAX-RS but not Micronaut sees a
 * Micronaut application as having no sources at all.
 *
 * <p>The taint engine's list used to contain {@code HeaderValue} and {@code PathValue} -- names no
 * framework defines -- while the real {@code @QueryValue} was missing. The shared annotation
 * catalogue fixed that, and the names here are checked against the Micronaut HTTP guide.
 */
@Controller("/orders")
public class MicronautOrderController {

  private final OrderRepository repository;
  private final JdbcTemplate jdbc;

  public MicronautOrderController(OrderRepository repository, JdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  /** A query parameter reaches the repository, which reaches the SQL. */
  @Get
  public void search(@QueryValue("customer") String customer) {
    repository.findByCustomer(customer);
  }

  /** A header parameter reaches the repository too. */
  @Get
  public void rename(@io.micronaut.http.annotation.Header("X-Order-Id") String id,
      @QueryValue("name") String name) {
    repository.rename(id, name);
  }

  /** SAFE: the query parameter never reaches a sink. */
  @Get
  public void preview(@QueryValue("customer") String customer) {
    repository.logOnly(customer);
  }

  /**
   * The @QueryValue value reaches the SQL text in the SAME method.
   *
   * <p>This is the case where the source label is visible in the finding itself: it must say which
   * trust boundary the value crossed, and that boundary is Micronaut -- whose binding annotations
   * are named differently from every other framework's.
   */
  @Get
  public void direct(@QueryValue("customer") String customer) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }

  /** A plain parameter with no framework annotation: the source stays a bare parameter. */
  @Get
  public void plain(String customer) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }
}
