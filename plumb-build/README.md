# plumb-build

Aggregator for the build-introspection modules.

Each submodule answers one question about *how was this project actually
compiled?* and feeds the answer into `plumb-build-core`'s uniform
`BuildProject` model.

| Module | Question it answers | Mechanism |
| --- | --- | --- |
| `plumb-build-core` | What are the sources and the classpath? | The `BuildAdapter` SPI plus the built-in Maven / Gradle / Bazel / Ant adapters. Static file parsing, no external tool. |
| `plumb-build-maven` | What does the **transitive** dependency tree look like? | The Maven Resolver (Aether), with `settings.xml`-aware mirror handling. |
| `plumb-build-capture` | What command line did javac **actually** receive? | A ByteBuddy agent that hooks the compiler inside its own JVM. |

## Why three ways to answer the same question

They fail in different directions, so they complement each other:

- `plumb-build-core`'s `maven` adapter reads `pom.xml` and resolves only the
  dependencies a module declares **directly**. It is fast, offline, and has
  almost no dependencies -- but a classpath built from a pom alone can miss
  everything that arrives transitively.
- `plumb-build-maven` computes the real transitive closure, with Maven's
  "nearest wins" conflict resolution across the whole graph. It costs an
  Aether dependency and an actual resolution.
- `plumb-build-capture` is the ground truth. It observes the compiler
  itself, so it also covers projects whose classpath is assembled by a
  script, a plugin, or an IDE -- cases no static reading can handle.

## Using them

`plumb-build-maven` registers itself as the build system `maven-resolver`:

```bash
bin/sast-scan --project-root /opt/my-spring-app --build-system maven-resolver
```

Auto-detection prefers `maven-resolver` over `maven`, because the transitive
tree is the more accurate answer. `maven` remains as the fallback: it has no
external dependency and cannot fail because a local repository is broken.

`plumb-build-capture` is a standalone agent -- it is not on the analysis
classpath, it runs inside the compiler's JVM:

```bash
# capture
PLUMB_CAPTURE_DIR=/tmp/captures \
  javac -javaagent:plumb-build-capture.jar -d out Foo.java

# read back
CaptureReader.readAll(Path.of("/tmp/captures"))
```

## A note on repository ids

The Maven Resolver refuses to serve a cached artifact that was not obtained
from the repository being asked for. Every artifact in the local repository
carries a `_remote.repositories` marker naming the repository id it came from,
so a local repository populated through an `alimaven` mirror will not resolve
against a hard-coded `central` -- it fails with `(present, but unavailable)`
even though the jar is right there on disk.

`MavenSettings` therefore reads the user's `settings.xml` and uses the
mirrors' real ids and urls.
