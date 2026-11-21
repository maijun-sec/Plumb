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
package org.plumb.tac.frontend.arkts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.DefaultCompileContext;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The ArkTS frontend: a library type is named by its own name.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>The owner of a call is the receiver's type, and the sink catalogue is written against that
 * name: the rule that matches a log injection says {@code Console.log}, not the path of the file
 * the standard library happens to be installed in. An owner that carries a module prefix stops the
 * catalogue from claiming the call -- and the symptom is a program that scans clean.
 *
 * <p>A class of THIS project is a different thing, and it keeps its module: two files that each
 * declare a class called {@code Store} are two classes, and a call key that names only the simple
 * name addresses every method of that name in every class.
 *
 * <p><b>Why this file exists next to the sibling one.</b> The ArkTS sidecar is a DIFFERENT program
 * on a DIFFERENT compiler build, and one catalogue entry has to serve both languages -- so the two
 * sidecars have to agree on the shape, and a change that is verified on one sidecar and assumed on
 * the other is a change that is verified once.
 *
 * <p><b>Why this drives the real sidecar.</b> The module of a declaration is a fact the COMPILER
 * knows and the sidecar asks it for: the resolved signature names the declaration the call reaches,
 * and the declaration's file names the module. A test that only checked a mapping would pass if the
 * sidecar stopped asking -- and that is exactly the failure this is about.
 */
@DisplayName("ArkTS: a library type is named by its own name")
class ArktsLibraryTypeOwnerTest {

  /** The class of the project: the module it lives in is part of its identity. */
  private static final String STORE =
      """
      export class Store {
        static make(): Store {
          return new Store();
        }
        find(id: string): string {
          return id;
        }
      }
      """;

  /** A dependency: the file lives INSIDE the project, under node_modules. */
  private static final String DEPENDENCY =
      """
      export class Helper {
        run(id: string): string {
          return id;
        }
      }
      """;

  /** The caller: it reaches the project, the dependency and the standard library. */
  private static final String CALLER =
      """
      import { Store } from "./src/store";
      import { Helper } from "pkg";

      export function report(input: string): void {
        const store = new Store();
        store.find(input);
        const helper = new Helper();
        helper.run(input);
        console.log(input);
        new Date();
        Store.make();
      }

      export class Reporter {
        send(input: string): void {
          console.error(input);
        }
      }
      """;

  @Test
  @DisplayName("a call to a library type names the type itself")
  void aCallToALibraryTypeNamesTheTypeItself(@TempDir Path directory) throws IOException {
    List<String> keys = callKeysOf(translate(directory), "report");

    assertThat(keys)
        .as(
            "the sink catalogue names a library class by its own name -- a module prefix in front"
                + " of it stops the catalogue from claiming the call, and the symptom is a program"
                + " that scans clean")
        .contains("Console.log/1");
  }

  @Test
  @DisplayName("a call to a dependency inside the project names the type itself")
  void aCallToADependencyInsideTheProjectNamesTheTypeItself(@TempDir Path directory)
      throws IOException {
    List<String> keys = callKeysOf(translate(directory), "report");

    assertThat(keys)
        .as(
            "a dependency is not a class of this project: the source root cannot tell them apart,"
                + " because the dependency sits INSIDE the root, and the file's path under the root"
                + " is not a module name")
        .contains("Helper.run/1");
  }

  @Test
  @DisplayName("a call to a class of the project carries the module the class lives in")
  void aCallToAClassOfTheProjectCarriesTheModuleTheClassLivesIn(@TempDir Path directory)
      throws IOException {
    List<String> keys = callKeysOf(translate(directory), "report");

    assertThat(keys)
        .as(
            "a class of this project is identified by its module plus its name: a key whose owner"
                + " is a simple name addresses every method of that name in every class, so the"
                + " call graph would answer with whichever of them it found first")
        .contains("src/store.Store.find/1");
  }

  @Test
  @DisplayName("a static member of a class of the project carries the module too")
  void aStaticMemberOfAClassOfTheProjectCarriesTheModuleToo(@TempDir Path directory)
      throws IOException {
    List<String> keys = callKeysOf(translate(directory), "report");

    assertThat(keys)
        .as(
            "a static member is reached through the class itself, so the class's identity -- the"
                + " module plus the name -- is what the key has to carry")
        .contains("src/store.Store.make/0");
  }

  @Test
  @DisplayName("a construction of a library type names the type it constructs")
  void aConstructionOfALibraryTypeNamesTheTypeItConstructs(@TempDir Path directory)
      throws IOException {
    List<String> keys = callKeysOf(translate(directory), "report");

    assertThat(keys)
        .as(
            "the engine looks a construction up in the same catalogue as a call, so the key has to"
                + " name the class the way the catalogue names it -- by its own name, with no path"
                + " in front of it")
        .contains("Date.<init>/0");
  }

  @Test
  @DisplayName("a library type is named by its own name inside a method of the project too")
  void aLibraryTypeIsNamedByItsOwnNameInsideAMethodOfTheProjectToo(@TempDir Path directory)
      throws IOException {
    List<String> keys = callKeysOf(translate(directory), "send");

    assertThat(keys)
        .as(
            "the class the call is written in is not the receiver's class: a method that logs"
                + " something still reaches the library's type, and the key has to say so")
        .contains("Console.error/1");
  }

  /** The project's files, translated through the real sidecar. */
  private static List<TacFunction> translate(Path directory) throws IOException {
    Path store = directory.resolve("src").resolve("store.ets");
    Path dependency = directory.resolve("node_modules").resolve("pkg").resolve("index.ets");
    Path caller = directory.resolve("caller.ets");
    Files.createDirectories(store.getParent());
    Files.createDirectories(dependency.getParent());
    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Files.writeString(store, STORE);
    Files.writeString(dependency, DEPENDENCY);
    Files.writeString(
        dependency.getParent().resolve("package.json"),
        "{\"name\": \"pkg\", \"version\": \"1.0.0\", \"main\": \"index.js\"}\n");
    Files.writeString(caller, CALLER);

    // The source root is what makes the module path a PROJECT-wide identity. Without one every
    // file is its own project, and the module of a file is its own name -- which would leave two
    // same-named files in two directories indistinguishable. The scan always gives one
    // (FrontendRegistry builds the context), and so does this test.
    CompileContext context =
        new DefaultCompileContext(
            List.of(
                new CompileContext.Module(
                    "arkts", directory, List.of(), List.of(), List.of(), null)));

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

      List<TacFunction> functions = new ArrayList<>();
      functions.addAll(frontend.parse(CALLER, caller, context).functions());
      functions.addAll(frontend.parse(STORE, store, context).functions());
      return functions;
    }
  }

  /**
   * The keys of the calls and the constructions of one function.
   *
   * @param functions the translated functions
   * @param name the function's name
   * @return the keys, in the order the function makes them
   */
  private static List<String> callKeysOf(List<TacFunction> functions, String name) {
    TacFunction function =
        functions.stream()
            .filter(candidate -> name.equals(candidate.name()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the function is not in the translation: " + name));
    List<String> keys = new ArrayList<>();
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.CALL
          && instruction.kind != TacInstruction.Kind.NEW) {
        continue;
      }
      String key = instruction.calleeSig;
      if (key != null && !key.isEmpty()) {
        keys.add(key);
      }
    }
    return keys;
  }

  /**
   * Whether the sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the compiler the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean sidecarAvailable() {
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
