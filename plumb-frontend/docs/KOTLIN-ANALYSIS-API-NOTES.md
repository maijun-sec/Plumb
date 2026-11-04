# Kotlin Frontend — Implementation Notes

## Kotlin Analysis API Standalone — verified facts (javap, 2.5.0-Beta2)

### Nullability API is HIDDEN, not `getNullability()`
`KaType.getNullability()` is compiled with `ACC_SYNTHETIC` (Kotlin
`@Deprecated(level = HIDDEN)`), so **javac cannot see it**. Use instead:

```java
TypeInformationKt.isMarkedNullable(session, type)      // the "T?" marker
TypeInformationKt.isNullable(session, type)           // may hold null
TypeInformationKt.getHasFlexibleNullability(session, type) // flexible type
```

`KaTypeNullability` enum exists but is only reachable through the HIDDEN accessor.

### `KaSession` inherits component methods DIRECTLY
`KaSession extends KaSymbolProvider, KaScopeProvider, KaTypeProvider, ...` — there is
**no** `getSymbolProvider()` / `getScopeProvider()` accessor. Call `session.getSymbol(...)`
and `session.getDeclaredMemberScope(...)` directly.

### Kotlin `Function1` lambdas
`kotlin.jvm.functions.Function1<P1, Unit>` is a functional interface, but a Java lambda
body infers `void`, which does not convert to `Unit`. Use an **anonymous inner class**
returning `kotlin.Unit.INSTANCE`.

### `kotlin.sequences.Sequence`
Only has `iterator()`. There is no `toList()` in Java — drain it manually.

### Module building
`KtModuleBuilder.platform` is a `lateinit` — must call `setPlatform(...)` on the
**module** builder (not the container builder) or `build()` throws
`UninitializedPropertyAccessException`.

```java
moduleProvider.setPlatform(JvmPlatforms.INSTANCE.getDefaultJvmPlatform());
module.setPlatform(JvmPlatforms.INSTANCE.getDefaultJvmPlatform());
```

### Lifecycle
- The session's `Disposable` must be disposed **inside** `Application.runWriteAction(...)`,
  otherwise IntelliJ's `FileManagerImpl.dispose` throws
  "Write access is allowed inside write-action only".
- `StandaloneAnalysisAPISessionBuilderKt.disposeGlobalStandaloneApplicationServices()`
  must be called to let the JVM exit — otherwise the IntelliJ application keeps
  non-daemon threads alive and the process hangs. **Required for Surefire.**

### Synthetic detection
A data class's `componentN` / `copy` come back with
`KaSymbolOrigin.SOURCE_MEMBER_GENERATED`; hand-written members are `SOURCE`.
The data class itself is `KaNamedClassSymbol.isData() == true`.

## The content scope decides whether a file resolves at all

**This is the single most important fact about the standalone session**, and it is not
documented by the API.

`KotlinStandaloneProjectStructureProvider.getModule(element, useSiteModule)` picks the
module for a file like this:

```kotlin
for (module in getAllModules()) {
    if (module.contentScope.contains(file.virtualFile)) return module
}
return KaNotUnderContentRootModuleImpl(...)   // no dependencies, no libraries
```

Two consequences, both of which silently degrade *everything*:

1. The scope is a **frozen list of the files that existed when the module was built**.
   A file written to disk afterwards is not in it.
2. `KaNotUnderContentRootModuleImpl` has **no regular and no depends-on dependencies**.
   A file assigned to it resolves against nothing, so `StringBuilder` is an *error type*,
   `Regex` is an *error type*, and every value is unknown.

The frontend stages the file it analyses *after* the session is built, so the default
scope loses it. `GlobalSearchScope.filesScope(project, supplier)` looks like the fix but
is not: `LazyFilesScope.getFiles()` **freezes the supplier's result on the first query**
and never asks again — so the first file analysed resolves and every later one does not.

**The fix** is a scope that answers "is this file under the root?" on every query:
`KotlinDirectoryScope`. With it, every staged file resolves.

### `KaModule.getContentScope()` is not safe to call early
`KaModuleBase.getContentScope()` goes through `KaContentScopeProvider`, a project
service that is registered *after* the module provider runs. Calling it while building
the module structure throws
`IllegalStateException: Cannot find service ...KaContentScopeProvider`. Use
`getBaseContentScope()` instead — that is the constructor argument and is safe.

## Platform types: the symbol origin is the signal, not the nullability flags

`TypeInformationKt.getHasFlexibleNullability(session, type)` is **not** a reliable
platform-type test:

- `val sb = StringBuilder()` — inferred, flexible: `true`.
- `fun f(s: java.lang.StringBuilder)` — *named* in the source: `false`, and the type
  comes back as an ordinary non-null type.

The Analysis API models a Java declaration as "flexible" because the declaration says
nothing about null — but it only reports that through the flag when it *inferred* the
type. The reliable signal is the **origin of the type's classifier**:

| Classifier origin | Meaning | Nullability |
| --- | --- | --- |
| `JAVA_LIBRARY` | a compiled Java class | nullable (platform type) |
| `JAVA_SOURCE` | a Java class in the analysed sources | nullable (platform type) |
| `SOURCE` | a Kotlin class in the analysed sources | non-null unless `T?` |
| `LIBRARY` | a Kotlin class from a library | non-null unless `T?` |

So `isPlatformType(type)` checks the classifier's origin, with the flexible flag as a
secondary signal.

**Watch out for type aliases**: `kotlin.text.StringBuilder` is a type alias for
`java.lang.StringBuilder`, so its classifier *is* the Java class and it is a platform
type. Use a genuinely Kotlin class (`kotlin.text.Regex`) when a test needs one.

## The JDK on a modern JDK

`KtSdkModuleBuilder.addBinaryRootsFromJdkHome(jdkHome, false)` works on JDK 9+: the
binary roots come back as `<jdkHome>!<module>` paths (the JRT separator), and the
Analysis API mounts them through its own `CoreJrtFileSystem`. `getBinaryVirtualFiles()`
is empty — that is normal, not a failure.

## The Kotlin standard library

`kotlin-stdlib` must be a **library module's binary root**, and that library module must
be a regular *and* a depends-on dependency of the source module. The standard library
comes in transitively with `kotlin-analysis-api-intellij-implementation-components`, so
it is already on the running JVM's classpath; the frontend locates it there rather than
assuming a fixed path, because the Analysis API's own built-ins must agree with the
standard library it ships.
