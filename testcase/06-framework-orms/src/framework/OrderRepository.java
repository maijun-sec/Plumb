package com.acme.shop.framework;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The persistence layer of a Spring Boot service.
 *
 * <p>Every type here comes from a third-party jar that is NOT on the analysis classpath, so the
 * TAC writes the callee signatures with SIMPLE receiver names
 * ({@code JdbcTemplate.query/2}) rather than fully qualified ones. That is the normal situation
 * when a project is scanned without its dependency jars, and it is the situation that decides
 * whether the framework sinks are reachable at all.
 */
public class OrderRepository {

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;

  public OrderRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
  }

  /** A tainted customer name reaches the SQL text. */
  public List<Map<String, Object>> findByCustomer(String customer) {
    return jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }

  /** The same shape through the query method rather than queryForList. */
  public List<Map<String, Object>> findByCustomerRows(String customer) {
    return jdbc.query("SELECT * FROM orders WHERE customer = '" + customer + "'", null);
  }

  /** A tainted value reaches a write statement. */
  public int rename(String customer, String newName) {
    return jdbc.update("UPDATE orders SET name = '" + newName + "' WHERE customer = '" + customer + "'");
  }

  /** A tainted value reaches the named-parameter form. */
  public List<Map<String, Object>> findByCustomerNamed(String customer) {
    return namedJdbc.query("SELECT * FROM orders WHERE customer = '" + customer + "'", Map.of());
  }

  /** SAFE: a bound parameter cannot change the statement. */
  public List<Map<String, Object>> findByCustomerSafe(String customer) {
    return jdbc.queryForList("SELECT * FROM orders WHERE customer = ?", customer);
  }

  /** SAFE: the tainted value never reaches a sink -- it is only logged. */
  public void logOnly(String customer) {
    System.out.println("customer " + customer);
  }
}
