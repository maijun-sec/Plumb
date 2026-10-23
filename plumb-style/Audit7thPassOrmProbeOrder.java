import java.nio.file.Path;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import java.util.ArrayList;

public class Audit7thPassOrmProbeOrder {
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
      List<String> order = new ArrayList<>();
      AstChecker c = new AstChecker() {
        @Override public String name() { return "order-trace"; }
        @Override public void init(StyleContext ctx) {}
        @Override public void visit(CompilationUnit n, StyleContext ctx) {
          order.add("CU@" + n.getBegin().map(p -> p.line).orElse(-1));
        }
        @Override public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
          order.add("Class@" + n.getBegin().map(p -> p.line).orElse(-1) + " name=" + n.getNameAsString());
        }
        @Override public void visit(MethodDeclaration n, StyleContext ctx) {
          order.add("Method@" + n.getBegin().map(p -> p.line).orElse(-1) + " name=" + n.getNameAsString());
        }
      };
      AstDispatcher d = new AstDispatcher().register(c);
      // Wrap with a try-catch to log invocation errors
      try {
        java.lang.reflect.Field f = AstDispatcher.class.getDeclaredField("dispatch");
        f.setAccessible(true);
        java.util.Map<Class<? extends com.github.javaparser.ast.Node>, ?> dispatch = (java.util.Map) f.get(d);
        d.run(ctx);
        System.out.println("  order.size=" + order.size());
        for (String e : order) System.out.println("    " + e);
      } catch (Throwable t) { System.out.println("  threw: " + t); t.printStackTrace(); }
      System.out.println("=== Case " + (i+1) + " ===");
      for (String e : order) System.out.println("  " + e);
    }
  }
}
