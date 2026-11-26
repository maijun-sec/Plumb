// CASE pt-return-value-taint
//
// The tainted value comes back out of a method, and goes straight to the sink.
//
// EXPECT: a dfa-taint-sql-injection finding.
class PtReturnValueTaint {
  static String pass(String raw) {
    return raw;
  }

  void go(String userInput, java.sql.Statement st) throws Exception {
    String v = pass(userInput);
    st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
  }
}
