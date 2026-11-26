// CASE fs-branch-one-dirty
//
// The control for fs-branch-both-clean: one arm leaves the value tainted, so
// the join of the two arms is tainted and the sink is reachable.
//
// EXPECT: a dfa-taint-sql-injection finding.
class FsBranchOneDirty {
  void go(String userInput, boolean flag, java.sql.Statement st) throws Exception {
    String v = userInput;
    if (flag) {
      v = "a";
    }
    st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
  }
}
