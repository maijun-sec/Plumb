// CASE fs-param-reassign
//
// The parameter itself is overwritten by a constant. Nothing of the input
// reaches the sink.
//
// EXPECT: no dfa-taint-sql-injection finding.
class FsParamReassign {
  void go(String userInput, java.sql.Statement st) throws Exception {
    userInput = "constant";
    st.executeQuery("SELECT * FROM t WHERE c = '" + userInput + "'");
  }
}
