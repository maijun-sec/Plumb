// CASE fs-reassign-const
//
// The tainted value is overwritten by a constant before it reaches the sink.
// The value that reaches the SQL text is a literal, so the statement cannot be
// changed by the input: there is nothing to find.
//
// EXPECT: no dfa-taint-sql-injection finding.
class FsReassignConst {
  void go(String userInput, java.sql.Statement st) throws Exception {
    String v = userInput;
    v = "constant";
    st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
  }
}
