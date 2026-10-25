package com.acme.shop.framework;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestBeanParam;
import org.jboss.resteasy.reactive.RestQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A Quarkus REST resource that takes a request BEAN.
 *
 * <p>{@code @RestBeanParam} does not bind a value: it binds a container of values. The request
 * values are the bean's fields, each with its own binding annotation.
 *
 * <p>Before the field-level model, the endpoint that read {@code params.customer} had no way to
 * learn that the field was request-bound, so the finding that named the origin of the tainted
 * value named an opaque parameter instead of the trust boundary.
 *
 * <p>The bean is a nested type, so it is a separate CLASS with its own field declarations --
 * which is what the resolution has to cope with -- while still living in the same compilation
 * unit. A bean in a separate FILE is the same shape; the cross-file case is covered by the DFA
 * test {@code FrameworkFieldBindingSourceTest}, because the category is measured in per-file mode,
 * where each unit is scanned alone.
 */
@Path("/orders")
public class QuarkusBeanParamResource {

  /**
   * The request bean: the fields the framework binds to the request.
   *
   * <p>The binding annotation is on the FIELD, not on the parameter. {@code @RestBeanParam} names
   * the container; each field's own annotation says which request value fills it.
   */
  public static class OrderFilter {

    /** The customer name, bound from the request's query string. */
    @RestQuery("customer")
    public String customer;

    /** A second request value, also bound from the query string. */
    @RestQuery("region")
    public String region;

    /** A field the framework does NOT bind: the application sets it, not the request. */
    public String internalNote;
  }

  private final JdbcTemplate jdbc;

  public QuarkusBeanParamResource(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The bound field reaches the SQL sink in the SAME method, so the source is visible in the
   * finding itself: it must name the FIELD and the field's own binding annotation.
   */
  @GET
  public void direct(@RestBeanParam OrderFilter params) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + params.customer + "'");
  }

  /**
   * A second bound field of the same bean.
   *
   * <p>The resolution is per FIELD, not per bean: the field name comes from the FIELDLOAD and the
   * annotation from that field's own declaration.
   */
  @GET
  public void byRegion(@RestBeanParam OrderFilter params) {
    jdbc.queryForList("SELECT * FROM orders WHERE region = '" + params.region + "'");
  }

  /**
   * A field the framework did NOT bind.
   *
   * <p>It is still a value derived from a request-supplied bean, so it is still reported -- but
   * its label must not claim a framework entry point, because no annotation bound it.
   */
  @GET
  public void byInternalNote(@RestBeanParam OrderFilter params) {
    jdbc.queryForList("SELECT * FROM orders WHERE note = '" + params.internalNote + "'");
  }

  /** SAFE: the bound field only reaches a log call. */
  @GET
  public void preview(@RestBeanParam OrderFilter params) {
    System.out.println(params.customer);
  }
}
