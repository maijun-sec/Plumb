/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.build.capture.CaptureReader;
import org.plumb.build.capture.CompdbWriter;
import org.plumb.tac.io.SourcePathMapper;
import org.plumb.tac.io.SqliteTacStore;

/**
 * The commands that make up the three-stage pipeline.
 *
 * <p><b>The three stages.</b>
 *
 * <ol>
 *   <li><b>capture</b> -- the build writes down the compiles it performed, and those are turned
 *       into a compilation database;
 *   <li><b>build</b> -- the compilation database drives the frontends, and the TAC they produce is
 *       stored;
 *   <li><b>analyze</b> -- the store is loaded and the analysis runs on it.
 * </ol>
 *
 * <p><b>Why each stage can run on its own.</b> The point of splitting them is that the expensive
 * stages do not have to run again, and that the analysis can run somewhere the build never ran. The
 * capture says what the build did; the store says what the code means; the analysis says what is
 * wrong with it. Nothing in stage three needs the sources, the classpath, or the toolchain.
 *
 * <p><b>Why these are separate commands rather than flags.</b> A flag says "do this differently"; a
 * command says "do this instead". The stages run in different places, on different machines, at
 * different times, and each one is a whole job with its own inputs and its own outputs. Making them
 * commands is what makes that possible.
 */
public final class TacCommands {

  private TacCommands() {}

  /**
   * The commands this pipeline has.
   *
   * @return the command names
   */
  public static List<String> commandNames() {
    return List.of("capture", "build", "analyze");
  }

  /**
   * Whether a word names one of the pipeline's commands.
   *
   * @param word the word
   * @return true when it does
   */
  public static boolean isCommand(String word) {
    return word != null && commandNames().contains(word);
  }

  /**
   * Run a command.
   *
   * @param command the command name
   * @param args the command's arguments
   * @return the process's exit code
   */
  public static int run(String command, List<String> args) {
    return switch (command) {
      case "capture" -> runCapture(args);
      case "build" -> runBuild(args);
      case "analyze" -> runAnalyze(args);
      default -> {
        System.err.println("unknown command: " + command);
        System.err.println("the commands are: " + String.join(", ", commandNames()));
        yield 2;
      }
    };
  }

  /**
   * The capture stage: turn the captures a build wrote into a compilation database.
   *
   * @param args the arguments
   * @return the exit code
   */
  private static int runCapture(List<String> args) {
    Path captureDir = null;
    Path output = null;
    for (int i = 0; i < args.size(); i++) {
      switch (args.get(i)) {
        case "--capture-dir" -> {
          if (++i >= args.size()) {
            System.err.println("--capture-dir needs a directory");
            return 2;
          }
          captureDir = Path.of(args.get(i));
        }
        case "--output" -> {
          if (++i >= args.size()) {
            System.err.println("--output needs a file");
            return 2;
          }
          output = Path.of(args.get(i));
        }
        default -> {
          System.err.println("unknown option for capture: " + args.get(i));
          return 2;
        }
      }
    }
    if (captureDir == null) {
      System.err.println("capture needs --capture-dir");
      return 2;
    }
    List<CaptureEntry> captures = CaptureReader.readAll(captureDir);
    if (captures.isEmpty()) {
      System.err.println("no captures in " + captureDir);
      return 1;
    }
    String database = CompdbWriter.write(captures);
    if (output == null) {
      System.out.print(database);
      return 0;
    }
    try {
      Path parent = output.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(output, database, StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.err.println("could not write " + output + ": " + e.getMessage());
      return 1;
    }
    System.out.println("wrote " + captures.size() + " compile(s) to " + output);
    return 0;
  }

  /**
   * The build stage: turn the captured compiles into a stored TAC.
   *
   * @param args the arguments
   * @return the exit code
   */
  private static int runBuild(List<String> args) {
    Path captureDir = null;
    Path outputDir = null;
    for (int i = 0; i < args.size(); i++) {
      switch (args.get(i)) {
        case "--capture-dir" -> {
          if (++i >= args.size()) {
            System.err.println("--capture-dir needs a directory");
            return 2;
          }
          captureDir = Path.of(args.get(i));
        }
        case "--output-dir" -> {
          if (++i >= args.size()) {
            System.err.println("--output-dir needs a directory");
            return 2;
          }
          outputDir = Path.of(args.get(i));
        }
        default -> {
          System.err.println("unknown option for build: " + args.get(i));
          return 2;
        }
      }
    }
    if (captureDir == null) {
      System.err.println("build needs --capture-dir");
      return 2;
    }
    if (outputDir == null) {
      System.err.println("build needs --output-dir");
      return 2;
    }
    List<CaptureEntry> captures = CaptureReader.readAll(captureDir);
    if (captures.isEmpty()) {
      System.err.println("no captures in " + captureDir);
      return 1;
    }
    OutputLayout layout = new OutputLayout(outputDir);
    try {
      Files.createDirectories(layout.cacheDir());
      CaptureTacBuilder.BuildResult result = CaptureTacBuilder.build(captures);
      Map<String, String> manifest = new LinkedHashMap<>();
      manifest.put("producer_root", String.valueOf(captureDir.toAbsolutePath().normalize()));
      manifest.put(
          "source_root",
          CaptureToTacBridge.sourceRootsOf(captures).stream()
              .map(p -> p.toString())
              .collect(Collectors.joining(";")));
      try (SqliteTacStore store = SqliteTacStore.open(layout.tacDb())) {
        CaptureTacBuilder.store(store, result, manifest);
      }
      System.out.println(
          "stored "
              + result.programs().size()
              + " program(s) in "
              + layout.tacDb()
              + (result.problems().isEmpty()
                  ? ""
                  : " (" + result.problems().size() + " file(s) could not be read)"));
      return result.problems().isEmpty() ? 0 : 1;
    } catch (IOException e) {
      System.err.println("could not build the TAC: " + e.getMessage());
      return 1;
    }
  }

  /**
   * The analysis stage: load the store and run the analysis on it.
   *
   * @param args the arguments
   * @return the exit code
   */
  private static int runAnalyze(List<String> args) {
    Path store = null;
    Path output = null;
    List<String> mappings = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      switch (args.get(i)) {
        case "--store" -> {
          if (++i >= args.size()) {
            System.err.println("--store needs a file");
            return 2;
          }
          store = Path.of(args.get(i));
        }
        case "--output" -> {
          if (++i >= args.size()) {
            System.err.println("--output needs a file");
            return 2;
          }
          output = Path.of(args.get(i));
        }
        case "--source-path-map" -> {
          if (++i >= args.size()) {
            System.err.println("--source-path-map needs producerRoot=consumerRoot");
            return 2;
          }
          mappings.add(args.get(i));
        }
        default -> {
          System.err.println("unknown option for analyze: " + args.get(i));
          return 2;
        }
      }
    }
    if (store == null) {
      System.err.println("analyze needs --store");
      return 2;
    }
    if (!Files.exists(store)) {
      System.err.println("the store does not exist: " + store);
      return 1;
    }
    SourcePathMapper mapper;
    try {
      mapper = SourcePathMapper.of(SourcePathMapper.parseMappings(mappings));
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }
    try (SqliteTacStore tacStore = SqliteTacStore.open(store)) {
      IrAnalysisRunner runner = new IrAnalysisRunner();
      IrAnalysisRunner.IrAnalysisResult result = runner.analyzeStore(tacStore, mapper);
      String report = render(result);
      if (output == null) {
        System.out.print(report);
      } else {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println("wrote " + result.findings().size() + " finding(s) to " + output);
      }
      return result.findings().isEmpty() ? 0 : 1;
    } catch (IOException e) {
      System.err.println("could not analyze " + store + ": " + e.getMessage());
      return 1;
    }
  }

  /**
   * Render the analysis result as text.
   *
   * @param result the result
   * @return the report
   */
  private static String render(IrAnalysisRunner.IrAnalysisResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("analyzed ")
        .append(result.analyzedFunctions())
        .append(" function(s), ")
        .append(result.findings().size())
        .append(" finding(s)\n");
    if (!result.isComplete()) {
      sb.append("the IR reported ")
          .append(result.problems().size())
          .append(" problem(s), so the findings are a lower bound\n");
    }
    for (var finding : result.findings()) {
      sb.append(finding.filePath())
          .append(":")
          .append(finding.line())
          .append("  ")
          .append(finding.ruleId())
          .append("  ")
          .append(finding.message())
          .append('\n');
    }
    return sb.toString();
  }
}
