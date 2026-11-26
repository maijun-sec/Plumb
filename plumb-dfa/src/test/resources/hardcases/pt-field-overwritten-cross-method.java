// CASE pt-field-overwritten-cross-method
//
// The field is filled with the tainted value, then overwritten with a constant
// by another method, and only then read at the sink. A field-sensitive
// inter-procedural analysis has to see that the value at the sink is a literal.
//
// EXPECT: no dfa-taint-sql-injection finding.
class PtFieldOverwrittenCrossMethod {
  private String value;

  void fill(String userInput) {
    this.value = userInput;
  }

  void clear() {
    this.value = "constant";
  }

  void use(java.sql.Statement st) throws Exception {
    this.clear();
    st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
  }
}
