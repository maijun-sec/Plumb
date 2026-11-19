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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.arkts.ArktsSidecarClient;
import org.plumb.tac.frontend.ts.TsSidecarClient;

/**
 * The languages whose objects are NAMESPACES: a member that is assigned and never read is reported.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>In these languages an object is a namespace, so a class member is an entry of that namespace
 * and not a field. The engine's "assigned but never read" rule watches field writes, and it used to
 * watch the wrong thing twice over:
 *
 * <ul>
 *   <li>the front end used to write a member's DECLARATION as a field write while every READ of it
 *       was a keyed read, so the rule saw an assignment and a read of two different locations --
 *       and it reported members that were read all the time;
 *   <li>once the declaration became a keyed store (the one-mechanism fix), the rule saw no
 *       assignment at all, and it reported NOTHING -- including the members that really are never
 *       read.
 * </ul>
 *
 * <p>So the rule went from "wrong" to "silent", and both are failures a reader cannot forgive: the
 * first invents a defect, the second hides one.
 *
 * <p>This test is the end-to-end proof that the rule works on a real source, through the real side
 * car, and that it stays quiet when it has nothing to say. The negative cases are the point: a rule
 * that reports everything passes every positive case.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecars need Node and the compilers they import, which npm installs. A machine without
 * them is missing an environment, not carrying a broken product -- the same reason the Go front
 * end's test skips when there is no Go toolchain. The message names the command that fixes it, so a
 * skip is a to-do and not a shrug.
 */
@DisplayName("the namespace languages: a member that is assigned and never read is reported")
class KeyedUnusedPrivateMemberScanE2ETest {

  /** The rule this test is about. */
  private static final String RULE = "dfa-taint-unused-private-field";

  /** A TypeScript class whose private member is never read. */
  private static final String TS_NEVER_READ =
      """
      export class Unread {
        private value: number = 1;

        run(): void {
          const other = 2;
        }
      }
      """;

  /** The same shape, but the member IS read. */
  private static final String TS_READ =
      """
      export class Read {
        private value: number = 1;

        run(): number {
          return this.value;
        }
      }
      """;

  /** Two classes that declare a member of the same name: two members, two reports. */
  private static final String TS_TWO_CLASSES =
      """
      export class First {
        private slot: number = 1;

        run(): void {
          const other = 2;
        }
      }

      export class Second {
        private slot: number = 2;

        run(): void {
          const other = 3;
        }
      }
      """;

  /** A member that is not private is not this rule's business. */
  private static final String TS_PUBLIC =
      """
      export class Public {
        public value: number = 1;

        run(): void {
          const other = 2;
        }
      }
      """;

  /** An ArkTS struct whose private member is never read. */
  private static final String ARKTS_NEVER_READ =
      """
      @Entry
      @Component
      struct Unread {
        private value: number = 1;

        build() {
        }

        render(): number {
          return 2;
        }
      }
      """;

  /** The same class name in two files: the members are two members. */
  private static final String TS_SAME_NAME_UNREAD =
      """
      export class Holder {
        private slot: number = 1;

        run(): void {
          const other = 2;
        }
      }
      """;

  /** The same class name in a second file, and its member IS read. */
  private static final String TS_SAME_NAME_READ =
      """
      export class Holder {
        private slot: number = 1;

        run(): number {
          return this.slot;
        }
      }
      """;

  @Test
  @DisplayName("a TypeScript private member that is never read is reported")
  void aTypeScriptPrivateMemberThatIsNeverReadIsReported(@TempDir Path directory) throws Exception {
    assumeTrue(
        tsSidecarAvailable(),
        "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm ci"
            + " in plumb-frontend/ts-sidecar");

    List<String> findings = scan(directory, "app.ts", TS_NEVER_READ);

    assertThat(findings)
        .as(
            "a private member that is assigned and never read is exactly what this rule is for; a"
                + " scan that finds nothing here is a scan that says nothing")
        .hasSize(1);
    assertThat(findings.get(0)).contains("'value'");
  }

  @Test
  @DisplayName("a TypeScript private member that is read is not reported")
  void aTypeScriptPrivateMemberThatIsReadIsNotReported(@TempDir Path directory) throws Exception {
    assumeTrue(
        tsSidecarAvailable(),
        "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm ci"
            + " in plumb-frontend/ts-sidecar");

    List<String> findings = scan(directory, "app.ts", TS_READ);

    assertThat(findings)
        .as(
            "the member IS read, so reporting it would be the false positive this rule used to"
                + " produce on every member it saw")
        .isEmpty();
  }

  @Test
  @DisplayName("two TypeScript classes that declare a member of the same name are two reports")
  void twoTypeScriptClassesThatDeclareAMemberOfTheSameNameAreTwoReports(@TempDir Path directory)
      throws Exception {
    assumeTrue(
        tsSidecarAvailable(),
        "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm ci"
            + " in plumb-frontend/ts-sidecar");

    List<String> findings = scan(directory, "app.ts", TS_TWO_CLASSES);

    assertThat(findings)
        .as(
            "the two classes declare two members: a member belongs to its class, so one class's"
                + " report must not swallow the other class's")
        .hasSize(2);
  }

  @Test
  @DisplayName("a TypeScript member that is not private is not reported")
  void aTypeScriptMemberThatIsNotPrivateIsNotReported(@TempDir Path directory) throws Exception {
    assumeTrue(
        tsSidecarAvailable(),
        "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm ci"
            + " in plumb-frontend/ts-sidecar");

    List<String> findings = scan(directory, "app.ts", TS_PUBLIC);

    assertThat(findings)
        .as(
            "the rule is about PRIVATE members; a public one is the program's business, not this"
                + " rule's")
        .isEmpty();
  }

  @Test
  @DisplayName("an ArkTS private member that is never read is reported")
  void anArktsPrivateMemberThatIsNeverReadIsReported(@TempDir Path directory) throws Exception {
    assumeTrue(
        arktsSidecarAvailable(),
        "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
            + " plumb-frontend/arkts-sidecar");

    List<String> findings = scan(directory, "Page.ets", ARKTS_NEVER_READ);

    assertThat(findings).as("the same rule, the same shape, the other sidecar").hasSize(1);
    assertThat(findings.get(0)).contains("'value'");
  }

  @Test
  @DisplayName("two TypeScript classes with the same name in two files are two reports")
  void twoTypeScriptClassesWithTheSameNameInTwoFilesAreTwoReports(@TempDir Path directory)
      throws Exception {
    assumeTrue(
        tsSidecarAvailable(),
        "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm ci"
            + " in plumb-frontend/ts-sidecar");

    List<String> findings =
        scanFiles(directory, Map.of("x.ts", TS_SAME_NAME_UNREAD, "y.ts", TS_SAME_NAME_UNREAD));

    assertThat(findings)
        .as(
            "the two files declare two classes of the same name, so they declare two members: a"
                + " member belongs to its class, and one class's report must not swallow the other"
                + " class's")
        .hasSize(2);
  }

  @Test
  @DisplayName("a TypeScript class's read does not suppress another file's class of the same name")
  void aTypeScriptClasssReadDoesNotSuppressAnotherFilesClassOfTheSameName(@TempDir Path directory)
      throws Exception {
    assumeTrue(
        tsSidecarAvailable(),
        "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm ci"
            + " in plumb-frontend/ts-sidecar");

    List<String> findings =
        scanFiles(directory, Map.of("x.ts", TS_SAME_NAME_UNREAD, "y.ts", TS_SAME_NAME_READ));

    assertThat(findings)
        .as(
            "the read belongs to y.ts's class, so it suppresses that class's member and not the"
                + " member of the class of the same name in the other file")
        .hasSize(1);
  }

  // ---- helpers -------------------------------------------------------------------------------

  /**
   * Runs a scan over one source file and returns the rule's findings.
   *
   * @param directory the directory to scan
   * @param fileName the name of the source file
   * @param source the source
   * @return the messages of the findings the rule produced
   */
  private static List<String> scan(Path directory, String fileName, String source)
      throws Exception {
    Path input = directory.resolve(fileName);
    Files.writeString(input, source);
    Path outFile = directory.resolve("out.ndjson");
    try {
      return runScan(input, outFile);
    } finally {
      deleteRecursively(directory);
    }
  }

  /**
   * Runs a scan over a directory of source files and returns the rule's findings.
   *
   * <p>The files are scanned together, because that is the shape the question is about: two classes
   * of the same name in two files are two classes only when the analysis sees both files.
   *
   * @param directory the directory to scan
   * @param files the file name and the source of each file
   * @return the messages of the findings the rule produced
   */
  private static List<String> scanFiles(Path directory, Map<String, String> files)
      throws Exception {
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Files.writeString(directory.resolve(entry.getKey()), entry.getValue());
    }
    Path outFile = directory.resolve("out.ndjson");
    try {
      return runScan(directory, outFile);
    } finally {
      deleteRecursively(directory);
    }
  }

  /**
   * Runs the scan and collects the rule's findings.
   *
   * @param input the file or directory to scan
   * @param outFile where the NDJSON findings are written
   * @return the messages of the findings the rule produced
   */
  private static List<String> runScan(Path input, Path outFile) throws Exception {
    {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(input);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      // The rule under test is a taint rule, so the taint engine is the one that runs.
      cfg.runTaint = true;
      cfg.runStyle = false;
      cfg.runDivByZero = false;
      cfg.runResourceLeak = false;
      cfg.runNullDeref = false;
      cfg.runHardcodedCred = false;
      cfg.runIntOverflow = false;
      cfg.runArrayBounds = false;
      cfg.runWeakCrypto = false;
      cfg.runInsecureRandom = false;
      cfg.runPredictableSeed = false;
      cfg.runPredictableKey = false;
      cfg.runStaticApiKey = false;
      cfg.runInsecureComms = false;
      cfg.runCorsMisconfig = false;
      cfg.runInsecureCookie = false;
      cfg.runBadShift = false;
      cfg.runInfiniteLoop = false;
      cfg.runConstantCondition = false;
      cfg.runUncheckedReturn = false;
      cfg.runIncorrectReturn = false;
      cfg.runThreadRun = false;
      cfg.runStringEquality = false;
      cfg.runCatchGeneric = false;
      cfg.runObsoleteFunction = false;
      cfg.runSensitiveDataLeak = false;
      cfg.runFieldHardcodedPassword = false;
      cfg.runMissingAuthorization = false;
      cfg.runMissingInputValidation = false;
      cfg.runInformationExposure = false;
      cfg.runErrorMessageInfoLeak = false;
      cfg.runCommentInfoLeak = false;
      cfg.runTryCatchFinally = false;
      cfg.runOffByOne = false;
      cfg.runEqualsCalledOnNull = false;
      cfg.runCompareWithSelf = false;
      cfg.runXxe = false;
      cfg.runReturnFromFinally = false;
      cfg.runEmptyCatchHandler = false;

      int rc = new ScanRunner(cfg).run();
      assertThat(rc).as("the scan must succeed").isEqualTo(0);

      List<String> messages = new ArrayList<>();
      if (Files.exists(outFile)) {
        for (String line : Files.readString(outFile).split("\\R")) {
          if (line.isBlank()) {
            continue;
          }
          if (!line.contains("\"" + RULE + "\"")) {
            continue;
          }
          int at = line.indexOf("\"message\":\"");
          if (at < 0) {
            continue;
          }
          int start = at + "\"message\":\"".length();
          int end = line.indexOf('"', start);
          messages.add(end < 0 ? line.substring(start) : line.substring(start, end));
        }
      }
      return messages;
    }
  }

  /**
   * Deletes a directory tree.
   *
   * @param dir the root
   */
  private static void deleteRecursively(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ignored) {
                  // A file that cannot be deleted must not fail the test: the
                  // assertions have already run.
                }
              });
    }
  }

  /** Whether the TypeScript sidecar can run here. */
  private static boolean tsSidecarAvailable() {
    if (TsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = TsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("typescript"));
  }

  /** Whether the ArkTS sidecar can run here. */
  private static boolean arktsSidecarAvailable() {
    if (ArktsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = ArktsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null
        && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }
}
