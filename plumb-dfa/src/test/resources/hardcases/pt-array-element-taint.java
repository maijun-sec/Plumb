// CASE pt-array-element-taint
//
// The tainted value is put into an array, and read back out of it at the sink.
//
// EXPECT: a dfa-taint-sql-injection finding.
class PtArrayElementTaint {
  void go(String userInput, java.sql.Statement st) throws Exception {
    String[] holder = new String[1];
    holder[0] = userInput;
    st.executeQuery("SELECT * FROM t WHERE c = '" + holder[0] + "'");
  }
}
