import java.nio.file.Path;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;
import org.plumb.style.quality.OrmLostUpdateCheck;
import java.util.List;

public class Audit7thPassOrmProbe {
  public static void main(String[] args) {
    String src = args.length > 0 ? args[0] : "";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new OrmLostUpdateCheck()).run(ctx);
    List<Finding> findings = ctx.findings();
    System.out.println("findings=" + findings.size());
    for (Finding f : findings) {
      System.out.println("  ruleId=" + f.ruleId() + " line=" + f.line() + " msg=" + f.message());
    }
  }
}
