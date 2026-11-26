// CASE pt-field-cross-method
//
// The field is filled with the tainted value in one method, and the sink is in
// another method. The taint has to travel through the field, across a call
// boundary.
//
// EXPECT: a dfa-taint-sql-injection finding.
class PtFieldCrossMethod {
  private String value;

  void fill(String userInput) {
    this.value = userInput;
  }

  void use(java.sql.Statement st) throws Exception {
    st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
  }
}
