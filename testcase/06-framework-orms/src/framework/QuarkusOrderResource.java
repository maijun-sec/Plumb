package com.acme.shop.framework;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestCookie;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A Quarkus REST resource with ANNOTATION-BOUND parameters.
 *
 * <p>Quarkus REST is a Jakarta REST implementation, so it uses the JAX-RS endpoint annotations
 * ({@code @Path} on the class, {@code @GET} on the method) but its OWN binding annotations
 * ({@code @RestQuery}, {@code @RestHeader}, {@code @RestCookie}). The two families are deliberately
 * named differently, so the same name means the same thing in one framework and a different thing in
 * the other -- which is exactly why the binding annotation, not the endpoint annotation, is what
 * identifies the trust boundary.
 *
 * <p>Before the shared annotation catalogue, {@code @RestQuery} and {@code @RestHeader} were not
 * sources at all: a value from a Quarkus request reached the SQL sink with no taint flow to follow.
 */
@Path("/orders")
public class QuarkusOrderResource {

  private final OrderRepository repository;
  private final JdbcTemplate jdbc;

  public QuarkusOrderResource(OrderRepository repository, JdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  /** A query parameter reaches the repository, which reaches the SQL. */
  @GET
  public void search(@RestQuery("customer") String customer) {
    repository.findByCustomer(customer);
  }

  /** A header parameter reaches the repository too. */
  @GET
  public void rename(@RestHeader("X-Order-Id") String id, @RestQuery("name") String name) {
    repository.rename(id, name);
  }

  /** SAFE: the query parameter never reaches a sink. */
  @GET
  public void preview(@RestCookie("session") String session) {
    repository.logOnly(session);
  }

  /**
   * The @RestQuery value reaches the SQL text in the SAME method.
   *
   * <p>This is the case where the source label is visible in the finding itself: it must say which
   * trust boundary the value crossed, and that boundary is Quarkus, not JAX-RS -- even though the
   * endpoint annotations ({@code @Path}, {@code @GET}) are shared with JAX-RS.
   */
  @GET
  public void direct(@RestQuery("customer") String customer) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }

  /** A plain parameter with no framework annotation: the source stays a bare parameter. */
  @GET
  public void plain(String customer) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }
}
