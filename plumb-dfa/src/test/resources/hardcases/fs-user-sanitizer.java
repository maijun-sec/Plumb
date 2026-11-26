// CASE fs-user-sanitizer
//
// The value goes through a method that returns a cleaned copy. The engine
// recognises a sanitizer only by a hardcoded list of callee signatures, and a
// project's own cleaning method is not on that list, so the tainted value is
// carried into the sink.
//
// EXPECT: no dfa-taint-sql-injection finding.
class FsUserSanitizer {
  static String sanitize(String raw) {
    return raw.replace("<", "").replace(">", "");
  }

  void go(String userInput, java.sql.Statement st) throws Exception {
    String cleaned = sanitize(userInput);
    st.executeQuery("SELECT * FROM t WHERE c = '" + cleaned + "'");
  }
}
