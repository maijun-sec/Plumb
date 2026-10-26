package org.plumb.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSystemRegistryTest {

  @Test
  void serviceLoaderFindsMavenAdapter() {
    BuildSystemRegistry registry = BuildSystemRegistry.get();
    // Maven is always on the classpath because plumb-build declares
    // MavenAdapter in META-INF/services. The test would fail to
    // compile if the registry wasn't pulling from ServiceLoader
    // (we'd see an empty list).
    assertThat(registry.adapters()).extracting(BuildAdapter::systemName).contains("maven");
  }

  @Test
  void byName_returnsRegisteredAdapter() {
    BuildAdapter a = BuildSystemRegistry.get().byName("maven");
    assertThat(a).isNotNull();
    assertThat(a.systemName()).isEqualTo("maven");
  }

  @Test
  void byName_returnsNullForUnknown() {
    assertThat(BuildSystemRegistry.get().byName("nonexistent")).isNull();
  }
}
