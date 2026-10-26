package org.plumb.main;

import java.nio.file.Path;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildProject;
import org.plumb.build.BuildSystemRegistry;

/** CLI entry point. Run with {@code --help} for options. */
public final class SastMain {

  /**
   * The width the help is printed at.
   *
   * <p>{@code HelpFormatter} defaults to 74 columns, and its wrapping algorithm degenerates when a
   * description is longer than the space left for it: the text is broken one character per line.
   * The longest option description here is several hundred characters, so the default width turned
   * the help into well over a thousand single-character lines. 100 columns is wide enough for the
   * option column and leaves a readable description column.
   */
  private static final int HELP_WIDTH = 100;

  private SastMain() {}

  /** Print the option list, with a layout that does not collapse. */
  private static void printHelp(Options opts) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.setWidth(HELP_WIDTH);
    formatter.setLeftPadding(2);
    formatter.setDescPadding(1);
    formatter.printHelp("sast-scan", opts, true);
  }

  public static void main(String[] args) {
    Options opts = buildOptions();
    CommandLine cli;
    try {
      cli = new DefaultParser().parse(opts, args);
    } catch (ParseException e) {
      System.err.println("Argument error: " + e.getMessage());
      printHelp(opts);
      System.exit(2);
      return;
    }

    if (cli.hasOption("help")) {
      printHelp(opts);
      return;
    }
    if (cli.hasOption("version")) {
      System.out.println("plumb 0.1.0-SNAPSHOT");
      return;
    }

    ScanConfig cfg;
    try {
      if (cli.hasOption("config")) {
        cfg = ConfigLoader.load(Path.of(cli.getOptionValue("config")));
      } else {
        cfg = new ScanConfig();
      }
      if (cli.hasOption("input")) {
        for (String s : cli.getOptionValues("input")) {
          cfg.inputs.add(Path.of(s));
        }
      }
      // external type-resolution inputs.
      // These are consumed by JavaSourceParser's TypeSolver
      // only — never enter the TAC pipeline.
      if (cli.hasOption("source-root")) {
        for (String s : cli.getOptionValues("source-root")) {
          cfg.sourceRoots.add(Path.of(s));
        }
      }
      if (cli.hasOption("module-root")) {
        for (String s : cli.getOptionValues("module-root")) {
          cfg.moduleRoots.add(Path.of(s));
        }
      }
      if (cli.hasOption("classpath")) {
        for (String s : cli.getOptionValues("classpath")) {
          cfg.classpathJars.add(Path.of(s));
        }
      }
      if (cli.hasOption("output")) {
        cfg.outputPath = Path.of(cli.getOptionValue("output"));
      }
      if (cli.hasOption("output-dir")) {
        cfg.outputDir = Path.of(cli.getOptionValue("output-dir"));
      }
      if (cli.hasOption("format")) {
        cfg.outputFormat =
            ScanConfig.OutputFormat.valueOf(cli.getOptionValue("format").toUpperCase());
      }
      if (cli.hasOption("cache-mode")) {
        cfg.cacheMode =
            ScanConfig.CacheMode.valueOf(cli.getOptionValue("cache-mode").toUpperCase());
      }
      if (cli.hasOption("max-parallelism")) {
        cfg.maxParallelism = Integer.parseInt(cli.getOptionValue("max-parallelism"));
      }
      if (cli.hasOption("cg-strategy")) {
        cfg.cgStrategy = cli.getOptionValue("cg-strategy");
      }
      if (cli.hasOption("ai-dedup")) {
        // Step 7 — flip the master switch. The
        // LlmConnection (baseUrl / apiKey / model /
        // timeout) is loaded from the YAML or env
        // vars; the CLI flag is just the
        // enable/disable switch.
        cfg.aiDedup = true;
      }
      if (cli.hasOption("rule")) {
        for (String s : cli.getOptionValues("rule")) {
          cfg.cliRuleFilter.add(s);
        }
      }
      if (cli.hasOption("rule-prompts-dir")) {
        cfg.cliRulePromptsDir = Path.of(cli.getOptionValue("rule-prompts-dir"));
      }
      if (cli.hasOption("list-rules")) {
        printRuleList();
        System.exit(0);
        return;
      }
      if (cli.hasOption("ai-dedup-stats")) {
        cfg.cliAiDedupStats = true;
      }
      // P2: build-system discovery. When --build-system is
      // given (or auto-detected via --project-root), the chosen
      // adapter's BuildProject feeds cfg.inputs / cfg.sourceRoots /
      // cfg.moduleRoots / cfg.classpathJars.
      if (cli.hasOption("project-root") || cli.hasOption("build-system")) {
        applyBuildSystem(cfg, cli);
      }
    } catch (Exception e) {
      System.err.println("Config error: " + e.getMessage());
      System.exit(2);
      return;
    }

    try {
      int rc = new ScanRunner(cfg).run();
      System.exit(rc);
    } catch (Exception e) {
      System.err.println("Scan failed: " + e.getMessage());
      e.printStackTrace();
      System.exit(3);
    }
  }

  private static Options buildOptions() {
    Options opts = new Options();
    opts.addOption(Option.builder("h").longOpt("help").desc("Show help").build());
    opts.addOption(Option.builder("v").longOpt("version").desc("Show version").build());
    opts.addOption(
        Option.builder("c")
            .longOpt("config")
            .hasArg()
            .argName("yaml")
            .desc("Load scan config from YAML")
            .build());
    opts.addOption(
        Option.builder("i")
            .longOpt("input")
            .hasArg()
            .argName("path")
            .desc("Input file or directory (may be repeated)")
            .build());
    // three new options that feed
    // JavaSourceParser's TypeSolver. None of them are
    // scanned for findings; the third-party jars in
    // --classpath in particular never enter the TAC
    // pipeline.
    opts.addOption(
        Option.builder()
            .longOpt("source-root")
            .hasArg()
            .argName("dir")
            .desc(
                "Project source root for cross-file symbol resolution "
                    + "(may be repeated). Treated as a flat JavaModule; "
                    + "not scanned for findings, only used by JavaParser's "
                    + "TypeSolver.")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("module-root")
            .hasArg()
            .argName("dir")
            .desc(
                "Module source root for cross-file symbol resolution "
                    + "(may be repeated). Same effect as --source-root; "
                    + "the two options differ only in UX semantics so a "
                    + "multi-module user can list modules separately.")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("classpath")
            .hasArg()
            .argName("jar|dir")
            .desc(
                "Third-party dependency jar or class-folder directory "
                    + "(may be repeated). Used by JavaParser's TypeSolver only; "
                    + "NEVER translated to TAC IR. Cardinal rule: third-party "
                    + "jars exist solely so the user source can resolve "
                    + "external type references.")
            .build());
    opts.addOption(
        Option.builder("o")
            .longOpt("output")
            .hasArg()
            .argName("path")
            .desc("Output file (defaults to stdout)")
            .build());
    opts.addOption(
        Option.builder("f")
            .longOpt("format")
            .hasArg()
            .argName("console|ndjson|sarif")
            .desc("Output format")
            .build());
    opts.addOption(
        Option.builder("D")
            .longOpt("output-dir")
            .hasArg()
            .argName("dir")
            .desc("Output directory for reports and on-disk caches (cache/, report/)")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("cache-mode")
            .hasArg()
            .argName("off|read|write|readwrite")
            .desc("How to use the on-disk IR cache: off (default), read, write, readwrite")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("max-parallelism")
            .hasArg()
            .argName("n")
            .desc("Upper bound on parallel parse/translate workers (default: nCPU-1, min 1)")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("cg-strategy")
            .hasArg()
            .argName("cha|rta|vta|1cfa|stub|global|cached:<name>:<path>")
            .desc(
                "Call-graph construction strategy for the unified engine (default: global). "
                    + "global=cross-TU signature lookup (default), "
                    + "vta=Variable Type Analysis (TU-local, most precise, slow), "
                    + "cha=Class Hierarchy (TU-local, fast/over-approx), "
                    + "rta=Rapid Type, 1cfa=1-CFA, stub=direct-only, "
                    + "cached:<name>:<path>=wrap with disk cache")
            .build());
    // Step 7 — AI options. (The --ai-scan and
    // --aggressiveness flags from earlier rounds are
    // gone: per-rule prompts live on the checker now,
    // and "scan" / "aggressiveness" folded into the
    // YAML's per-rule `checks.<rule>.enabled` switch.)
    opts.addOption(
        Option.builder()
            .longOpt("ai-dedup")
            .desc(
                "Send every finding whose rule has an AI "
                    + "prompt to the configured LLM "
                    + "(ai.baseUrl + ai.apiKey + ai.model in the YAML) and ask "
                    + "whether it is a true positive. The verdict lands in the "
                    + "NDJSON output as ai_verdict / ai_reason. Off by default; "
                    + "the LLM endpoint and key are configured in the YAML "
                    + "so the package can stay small (no Spring-AI / Spring Boot).")
            .build());
    // M-5 (audit 2026-10-08): the `--aggressiveness` flag
    // was a no-op (ConfigLoader ignored the YAML `aggressiveness:`
    // key; SastMain never read the CLI value). Removed
    // completely. Per-rule firing is controlled by
    // `checks.<rule>.<enabled>` in the YAML or `--rule <id>`.
    opts.addOption(
        Option.builder()
            .longOpt("rule")
            .hasArg()
            .argName("id")
            .desc(
                "Only run the rule with this id (may be repeated). "
                    + "Overrides the YAML's checks.* flags. Useful for "
                    + "triage: 'show me only the SQL injection findings'.")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("rule-prompts-dir")
            .hasArg()
            .argName("path")
            .desc(
                "Directory of <rule-id>.md files. Overrides the bundled "
                    + "classpath rule prompts. Use to add or customise the "
                    + "prompts the AI agent receives for specific rules.")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("list-rules")
            .desc("List every available rule id + name + CWE and exit.")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("ai-dedup-stats")
            .desc(
                "After --ai-dedup completes, print a verdict "
                    + "distribution (TP / FP / UNKNOWN) and per-rule "
                    + "FP ratio to stderr. The NDJSON on stdout is "
                    + "unchanged. Useful to gauge AI dedup quality on "
                    + "a fresh codebase. No effect without "
                    + "--ai-dedup (or ai.dedup: true in YAML).")
            .build());
    // P2: build-system integration. --build-system
    // explicitly picks an adapter; --project-root sets the project
    // root the adapter will resolve. When only --project-root is
    // given, the registry auto-detects the adapter (Maven / Gradle
    // / Bazel / Ant).
    opts.addOption(
        Option.builder()
            .longOpt("build-system")
            .hasArg()
            .argName("maven|gradle|bazel|ant")
            .desc(
                "Build-system adapter to use. Plumb ships Maven, "
                    + "Gradle, Bazel and Ant adapters. Combined with "
                    + "--project-root, the chosen adapter discovers "
                    + "the project's source files, classpath jars, "
                    + "and modules, which feed plumb-main's "
                    + "--input / --source-root / --module-root / "
                    + "--classpath flags. When omitted, the registry "
                    + "auto-detects by probing the project root.")
            .build());
    opts.addOption(
        Option.builder()
            .longOpt("project-root")
            .hasArg()
            .argName("dir")
            .desc(
                "Project root passed to the build-system adapter. "
                    + "When --build-system is omitted, the registry "
                    + "auto-detects which adapter applies by probing "
                    + "for pom.xml / build.gradle / BUILD / build.xml. "
                    + "The adapter's output is added to the input set; "
                    + "any explicit --input flags are kept in addition.")
            .build());
    return opts;
  }

  /**
   * P2: Resolve the build system and feed its {@link BuildProject} into the running {@link
   * ScanConfig}. The adapter's sourceRoots and moduleRoots are added to the corresponding {@code
   * cfg} lists; the classpathJars and source files are appended as well. The user retains full
   * control: explicit {@code --input} / {@code --classpath} flags are kept in addition to whatever
   * the adapter produces.
   */
  private static void applyBuildSystem(ScanConfig cfg, CommandLine cli) {
    Path projectRoot =
        cli.hasOption("project-root") ? Path.of(cli.getOptionValue("project-root")) : null;
    if (projectRoot == null) {
      System.err.println("--build-system requires --project-root");
      System.exit(2);
      return;
    }
    BuildSystemRegistry registry = BuildSystemRegistry.get();
    BuildAdapter adapter =
        cli.hasOption("build-system")
            ? registry.byName(cli.getOptionValue("build-system"))
            : registry.detect(projectRoot);
    if (adapter == null) {
      System.err.println(
          "No build-system adapter could handle "
              + projectRoot
              + " (try --build-system maven|gradle|bazel|ant).");
      System.exit(2);
      return;
    }
    BuildProject bp = adapter.discover(projectRoot);
    System.err.println(
        "Build adapter '"
            + adapter.systemName()
            + "' discovered: "
            + bp.sourceFiles().size()
            + " sources, "
            + bp.classpathJars().size()
            + " classpath jars, "
            + bp.modules().size()
            + " modules.");
    cfg.inputs.addAll(bp.sourceFiles());
    cfg.sourceRoots.addAll(bp.sourceRoots());
    cfg.moduleRoots.addAll(bp.moduleRoots());
    cfg.classpathJars.addAll(bp.classpathJars());
    cfg.buildProject = bp;
  }

  /**
   * Print the list of known rule ids (sourced from the plumb-ai RulePromptRegistry's bundled list,
   * which covers the long tail of rules the engine ships). Used by the --list-rules CLI flag.
   */
  private static void printRuleList() {
    System.out.println("Available rules (rule id / CWE / class):");
    System.out.println();
    // Step 7 — rule prompts are now embedded in
    // each checker's getAiPrompt() method, not in a
    // central registry. --list-rules still shows the
    // rule names that have a built-in prompt (the
    // ones the engine knows about), but the actual
    // prompt lives with the checker.
    for (org.plumb.rules.RuleDef def :
        new org.plumb.rules.RuleRegistry().withBuiltinDefaults().all()) {
      System.out.println("  " + def.ruleId().name());
    }
    System.out.println();
    System.out.println("Run a specific rule with: --rule <id>");
  }
}
