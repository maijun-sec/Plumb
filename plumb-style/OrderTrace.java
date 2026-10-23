import java.nio.file.Path;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;
import org.plumb.style.quality.OrmLostUpdateCheck;
import org.plumb.reporter.Finding;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

public class OrderTrace implements AstChecker {
  public final List<String> log = new ArrayList<>();
  public final List<String> seenEntities = new ArrayList<>();
  @Override public String name() { return "trace"; }
  @Override public void init(StyleContext ctx) {}
  @Override public void visit(CompilationUnit n, StyleContext ctx) {
    log.add("CU@" + n.getBegin().map(p -> p.line).orElse(-1));
  }
  @Override public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    log.add("Class@" + n.getBegin().map(p -> p.line).orElse(-1) + " name=" + n.getNameAsString());
    seenEntities.add(n.getNameAsString());
  }
  @Override public void visit(MethodDeclaration n, StyleContext ctx) {
    log.add("Method@" + n.getBegin().map(p -> p.line).orElse(-1) + " name=" + n.getNameAsString()
        + " seenEntities=" + seenEntities);
  }
}

class Audit7thPassOrmProbeOrder {
  public static void main(String[] args) {
    String[] cases = new String[2];
    cases[0] = "import javax.persistence.Entity;\n"
        + "import javax.persistence.EntityManager;\n"
        + "@Entity class User { String name; }\n"
        + "class N {\n"
        + "  void m(EntityManager em) {\n"
        + "    User u = new User();\n"
        + "    u.name = \"x\";\n"
        + "  }\n"
        + "}\n";
    cases[1] = "import javax.persistence.Entity;\n"
        + "import javax.persistence.EntityManager;\n"
        + "class N {\n"
        + "  void m(EntityManager em) {\n"
        + "    User u = new User();\n"
        + "    u.name = \"x\";\n"
        + "  }\n"
        + "}\n"
        + "@Entity class User { String name; }\n";

    for (int i = 0; i < cases.length; i++) {
      String src = cases[i];
      ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
      StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
      OrderTrace c = new OrderTrace();
      AstDispatcher d = new AstDispatcher().register(c);
      d.run(ctx);
      System.out.println("=== Case " + (i+1) + " (OrderTrace only) ===");
      for (String e : c.log) System.out.println("  " + e);

      // Now run with OrmLostUpdateCheck
      StyleContext ctx2 = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
      OrmLostUpdateCheck oc = new OrmLostUpdateCheck();
      new AstDispatcher().register(oc).run(ctx2);
      List<Finding> findings = ctx2.findings();
      try {
        java.lang.reflect.Field f1 = OrmLostUpdateCheck.class.getDeclaredField("entityClassNames");
        f1.setAccessible(true);
        Set<?> ents = (Set<?>) f1.get(oc);
        java.lang.reflect.Field f2 = OrmLostUpdateCheck.class.getDeclaredField("classesSeenInThisFile");
        f2.setAccessible(true);
        int seen = (Integer) f2.get(oc);
        System.out.println("=== Case " + (i+1) + " (OrmLostUpdate) ===");
        System.out.println("  entityClassNames=" + ents);
        System.out.println("  classesSeenInThisFile=" + seen);
        System.out.println("  findings=" + findings.size());
        for (Finding f : findings) {
          System.out.println("    ruleId=" + f.ruleId() + " line=" + f.line());
        }
      } catch (Exception e) { System.out.println("reflect fail: " + e); }
    }
  }
}
