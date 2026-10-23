package org.plumb.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildProjectTest {

  @Test
  void builder_isolatesAndCopies() {
    List<Path> sources = List.of(Path.of("a.java"), Path.of("b.java"));
    BuildProject bp =
        BuildProject.builder("maven")
            .version("3.9.9")
            .sourceFiles(sources)
            .classpathJars(List.of(Path.of("x.jar")))
            .build();

    assertThat(bp.systemName()).isEqualTo("maven");
    assertThat(bp.version()).isEqualTo("3.9.9");
    assertThat(bp.sourceFiles()).containsExactly(Path.of("a.java"), Path.of("b.java"));
    assertThat(bp.classpathJars()).containsExactly(Path.of("x.jar"));
  }

  @Test
  void module_holdsOwnSourcesAndClasspath() {
    BuildProject.Module m =
        new BuildProject.Module(
            "core",
            Path.of("modules/core/src/main/java"),
            List.of(Path.of("modules/core/src/main/java/Foo.java")),
            List.of(),
            List.of(Path.of("lib/a.jar")));
    assertThat(m.id()).isEqualTo("core");
    assertThat(m.sourceRoot()).isEqualTo(Path.of("modules/core/src/main/java"));
    assertThat(m.sources()).hasSize(1);
    assertThat(m.classpathJars()).containsExactly(Path.of("lib/a.jar"));
  }

  @Test
  void emptyProject_isLegal() {
    BuildProject bp = BuildProject.builder("gradle").build();
    assertThat(bp.sourceFiles()).isEmpty();
    assertThat(bp.modules()).isEmpty();
  }

  @Test
  void modules_canBeEmptyOrPopulated() {
    BuildProject withMods =
        BuildProject.builder("bazel")
            .modules(
                Map.of(
                    "core",
                        new BuildProject.Module(
                            "core", Path.of("c"), List.of(), List.of(), List.of()),
                    "lib",
                        new BuildProject.Module(
                            "lib", Path.of("l"), List.of(), List.of(), List.of())))
            .build();
    assertThat(withMods.modules()).containsKeys("core", "lib");
  }

  @Test
  void builder_carriesLanguageLevelAndEncoding() {
    BuildProject bp =
        BuildProject.builder("maven")
            .version("1.0")
            .languageLevel("11")
            .sourceEncoding(java.nio.charset.StandardCharsets.ISO_8859_1)
            .build();
    assertThat(bp.languageLevel()).isEqualTo("11");
    assertThat(bp.sourceEncoding()).isEqualTo(java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  @Test
  void module_carriesScopeLanguageLevelAndEncoding() {
    BuildProject.Module m =
        new BuildProject.Module(
            "core",
            Path.of("src/main/java"),
            List.of(),
            List.of(),
            List.of(),
            BuildProject.Scope.TEST,
            "17",
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(m.scope()).isEqualTo(BuildProject.Scope.TEST);
    assertThat(m.languageLevel()).isEqualTo("17");
    assertThat(m.sourceEncoding()).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void scope_fromString_handlesGradleAliases() {
    assertThat(BuildProject.Scope.fromString("implementation")).isEqualTo(BuildProject.Scope.COMPILE);
    assertThat(BuildProject.Scope.fromString("api")).isEqualTo(BuildProject.Scope.COMPILE);
    assertThat(BuildProject.Scope.fromString("compileOnly")).isEqualTo(BuildProject.Scope.PROVIDED);
    assertThat(BuildProject.Scope.fromString("runtimeOnly")).isEqualTo(BuildProject.Scope.RUNTIME);
    assertThat(BuildProject.Scope.fromString("testImplementation"))
        .isEqualTo(BuildProject.Scope.TEST);
    assertThat(BuildProject.Scope.fromString(null)).isEqualTo(BuildProject.Scope.COMPILE);
    assertThat(BuildProject.Scope.fromString("")).isEqualTo(BuildProject.Scope.COMPILE);
    assertThat(BuildProject.Scope.fromString("unknown")).isEqualTo(BuildProject.Scope.COMPILE);
  }

  @Test
  void module_legacyConstructorDefaultsToCompileScope() {
    BuildProject.Module m =
        new BuildProject.Module("legacy", Path.of("."), List.of(), List.of(), List.of());
    assertThat(m.scope()).isEqualTo(BuildProject.Scope.COMPILE);
    assertThat(m.languageLevel()).isNull();
    assertThat(m.sourceEncoding()).isNull();
  }
}
