package com.acme.shop.framework;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller that reaches the SQL sink DIRECTLY, so the finding's source is the controller's own
 * framework-bound parameter.
 */
@RestController
public class DirectSinkController {

  private final JdbcTemplate jdbc;

  public DirectSinkController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The @RequestParam value reaches the SQL text in the same method. */
  public void search(@RequestParam("customer") String customer) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }

  /** A plain parameter with no framework annotation: the source stays a bare parameter. */
  public void plain(String customer) {
    jdbc.queryForList("SELECT * FROM orders WHERE customer = '" + customer + "'");
  }
}
