package chain;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * LAYER 5 / FILE 8 — the sink.
 *
 * <p>Every method here is a genuine SQL-injection sink (CWE-89): the tainted value is concatenated
 * into a statement string that reaches {@code Statement#executeQuery} / {@code #executeUpdate} /
 * {@code Connection#prepareStatement}.
 *
 * <p>The point of this file is NOT that it contains a sink. It is that by the time the value
 * arrives here it has passed through 7 files and 20+ methods. A taint engine that tracks only
 * intra-procedural flow will find nothing here.
 */
public class SqlSink {

  /** SINK 1: concatenation into a query string. */
  public void queryByName(Connection conn, String userName) throws SQLException {
    Statement st = conn.createStatement();
    ResultSet rs = st.executeQuery("SELECT id FROM users WHERE name = '" + userName + "'");
    consume(rs);
  }

  /** SINK 2: concatenation into a DML statement. */
  public void deleteByName(Connection conn, String userName) throws SQLException {
    Statement st = conn.createStatement();
    st.executeUpdate("DELETE FROM users WHERE name = '" + userName + "'");
  }

  /** SINK 3: {@code prepareStatement} with a concatenated value. */
  public void prepared(Connection conn, String userName) throws SQLException {
    PreparedStatementHolder.execute(conn, "SELECT * FROM orders WHERE owner = '" + userName + "'");
  }

  /** SINK 4: concatenation via StringBuilder rather than the + operator. */
  public void viaBuilder(Connection conn, String table, String column) throws SQLException {
    StringBuilder q = new StringBuilder();
    q.append("SELECT ").append(column).append(" FROM ").append(table).append(" WHERE x = 1");
    Statement st = conn.createStatement();
    st.executeQuery(q.toString());
  }

  /** SINK 5: sink reached through a parameter object field. */
  public void viaContext(Connection conn, RequestContext ctx) throws SQLException {
    Statement st = conn.createStatement();
    st.executeQuery("SELECT * FROM t WHERE u = '" + ctx.getUser() + "'");
  }

  /** SINK 6: sink inside a loop, so the summary is reached repeatedly. */
  public void inLoop(Connection conn, java.util.List<String> names) throws SQLException {
    Statement st = conn.createStatement();
    for (String n : names) {
      st.executeQuery("SELECT * FROM t WHERE u = '" + n + "'");
    }
  }

  /** SINK 7: sink inside a try/catch, so the value passes an exception edge. */
  public void inTryCatch(Connection conn, String userName) {
    try {
      Statement st = conn.createStatement();
      st.executeQuery("SELECT * FROM t WHERE u = '" + userName + "'");
    } catch (SQLException e) {
      // swallow
    }
  }

  private static void consume(ResultSet rs) {
    try {
      while (rs.next()) {
        rs.getString(1);
      }
    } catch (SQLException ignored) {
      // no-op
    }
  }

  /** Small helper so the fixture does not need a raw JDBC import in every signature. */
  static final class PreparedStatementHolder {
    static void execute(Connection conn, String sql) throws SQLException {
      conn.prepareStatement(sql).executeQuery();
    }
  }
}