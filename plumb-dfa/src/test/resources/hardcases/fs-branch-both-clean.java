// CASE fs-branch-both-clean
//
// Both arms of the branch overwrite the value with a constant, so on every path
// out of the branch the value is a literal. The join of the two arms is clean.
//
// EXPECT: no dfa-taint-sql-injection finding.
class FsBranchBothClean {
  void go(String userInput, boolean flag, java.sql.Statement st) throws Exception {
    String v = userInput;
    if (flag) {
      v = "a";
    } else {
      v = "b";
    }
    st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
  }
}
