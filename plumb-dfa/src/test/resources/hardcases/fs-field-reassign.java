// CASE fs-field-reassign
//
// The field carries the tainted value, and is then overwritten by a constant
// before the sink. The sink reads the field, so a field-sensitive analysis has
// to see the overwrite.
//
// EXPECT: no dfa-taint-sql-injection finding.
class FsFieldReassign {
  private String value;

  void go(String userInput, java.sql.Statement st) throws Exception {
    this.value = userInput;
    this.value = "constant";
    st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
  }
}
