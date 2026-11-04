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
package org.plumb.dsl.kotlin

import java.net.URL
import java.net.URLClassLoader

/**
 * A classloader that serves the Kotlin COMPILER classes from the script engine's own
 * jars, and delegates everything else to the application loader.
 *
 * <p><b>Why this exists.</b> The repository runs two Kotlin stacks in one JVM:
 *
 * <ul>
 *   <li>the DSL rule engine, on the Kotlin 2.2.0 script engine, and
 *   <li>the Kotlin source frontend, on the Kotlin Analysis API 2.5.0-Beta2.
 * </ul>
 *
 * <p>Both need a Kotlin compiler, and both bundle one. The Analysis API's
 * implementation jar carries 594 classes under {@code org.jetbrains.kotlin.cli},
 * 4771 under {@code org.jetbrains.kotlin.fir}, 302 under
 * {@code org.jetbrains.kotlin.scripting}, the whole {@code kotlin.script} API, and
 * the UNSHADED IntelliJ platform. The script engine's
 * {@code kotlin-compiler-embeddable} carries the same class names from its own
 * release, with the SHADED IntelliJ platform
 * ({@code org.jetbrains.kotlin.com.intellij}).
 *
 * <p><b>THE TWO CANNOT SHARE A CLASSPATH, AT ANY VERSION.</b> This is not a
 * version-skew problem that a version bump would fix. The Analysis API links
 * against {@code com.intellij.*}; the embeddable compiler links against
 * {@code org.jetbrains.kotlin.com.intellij.*}. The two stacks disagree about
 * the INTELLIJ SHADING, so even the same Kotlin release produces two
 * incompatible copies of every class the compiler shares.
 *
 * <p>Observed failures when they met on one classpath:
 *
 * <pre>
 * NoSuchMethodError: CompilationContextKt.createIsolatedCompilationContext$default(
 *     ..., org.jetbrains.kotlin.com.intellij.openapi.Disposable, ...)
 * </pre>
 *
 * <p>Same method name, same arity, <b>different parameter type</b>: the caller's
 * descriptor does not match the callee's.
 *
 * <pre>
 * ClassCastException: org.jetbrains.kotlin.idea.KotlinFileType cannot be cast to
 *     org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
 * </pre>
 *
 * <p>The same shaded/unshaded split, seen through a cast.
 *
 * <p><b>WHY IT FAILED SILENTLY.</b> The DSL engine catches a compilation failure, logs
 * a warning, and carries on. So every DSL rule stopped matching while every built-in
 * rule kept working. The scan looked healthy and the user's rules were simply gone.
 *
 * <p><b>WHY THE BOUNDARY IS THIS ONE.</b> This loader owns
 * {@code org.jetbrains.kotlin.*} in full, plus {@code kotlin.script.*}.
 *
 * <ul>
 *   <li>{@code org.jetbrains.kotlin.*} covers the compiler itself
 *     ({@code .cli}, {@code .fir}, {@code .resolve}, {@code .config},
 *     {@code .main}, {@code .load}), the scripting compiler plugin, the IDE
 *     classes, AND the embeddable compiler's shaded IntelliJ
 *     ({@code org.jetbrains.kotlin.com.intellij.*}). Owning the whole namespace
 *     is what keeps the script world internally consistent: the 2.2.0 compiler
 *     and the 2.2.0 FIR must see each other, and they must not see the
 *     Analysis API's 2.5.0-Beta2 copies. A narrower boundary (owning only
 *     {@code .cli} and {@code .idea}) was tried and does not hold: the compiler
 *     classes reference each other across the whole namespace, so a partially
 *     owned namespace ends up mixing two releases of the same compiler.
 *   <li>{@code kotlin.script.*} is the script API the engine generates against
 *     ({@code kotlin.script.templates.standard.ScriptTemplateWithBindings} is
 *     the base class of every compiled rule). The Analysis API's jar ships a
 *     2.5.0-Beta2 copy of that API, which would otherwise win on the
 *     application classpath.
 * </ul>
 *
 * <p>It deliberately does NOT own {@code kotlin.*} otherwise. The standard
 * library is shared: {@code kotlin.coroutines.CoroutineContext},
 * {@code kotlin.Unit} and the rest must be ONE class in the JVM, or the
 * compiler's own code casts across the boundary and the JVM throws a loader
 * constraint violation. Sharing the stdlib is safe because the two stacks
 * agree on it, and it is required because they both use it. An earlier
 * experiment that owned {@code kotlin.*} as well failed exactly here, with
 *
 * <pre>
 * loader constraint violation: loader 'app' wants to load interface
 * kotlin.coroutines.CoroutineContext. A different interface with the same name
 * was previously loaded by 'plumb-dsl-kotlin-script-engine'
 * </pre>
 *
 * <p><b>WHAT THE APPLICATION LOADER MUST PROVIDE.</b> Everything outside the
 * owned namespaces, in particular {@code org.plumb.*} (the DSL API the rules
 * call), {@code com.github.javaparser.*} (the AST the rules inspect) and the
 * Kotlin standard library. Those are shared on purpose: a rule and the engine
 * that runs it must agree on the identity of the AST node types.
 */
internal class ScriptEngineClassLoader(
    parent: ClassLoader,
    /** The jars that hold the script engine's Kotlin compiler classes. */
    private val owned: List<URL>,
) : URLClassLoader("plumb-dsl-kotlin-script-engine", owned.toTypedArray(), parent) {

    /**
     * Loads a class, preferring this loader's own jars for the packages it owns.
     *
     * <p>[java.net.URLClassLoader] delegates to the parent FIRST, so the application
     * classpath's Analysis API classes would win over the script engine's own. The
     * order is therefore reversed for the owned packages: this loader's jars first,
     * the parent only as a fallback.
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!isOwned(name)) {
            return super.loadClass(name, resolve)
        }
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let {
                if (resolve) resolveClass(it)
                return it
            }
            try {
                val fromOwned = findClass(name)
                if (resolve) resolveClass(fromOwned)
                return fromOwned
            } catch (ex: ClassNotFoundException) {
                // The class belongs to the Analysis API, not to the script engine:
                // org.jetbrains.kotlin.analysis.*, .fir.* and .resolve.* exist only
                // in the Analysis API's jars. The script engine never asks for
                // them, so falling back to the parent is correct.
                return super.loadClass(name, resolve)
            }
        }
    }

    /**
     * Finds a resource, preferring this loader's own jars for the owned packages.
     *
     * <p><b>THIS IS NOT OPTIONAL, AND IT IS THE SUBTLE HALF OF THE BOUNDARY.</b>
     * [java.net.URLClassLoader.getResource] is parent-first, exactly like
     * [java.net.URLClassLoader.loadClass]. The compiler's IntelliJ platform locates
     * its extension points with
     *
     * <pre>
     * PathUtil.getResourcePathForClass(CompilerSystemProperties)
     * </pre>
     *
     * which resolves {@code /org/jetbrains/kotlin/cli/common/CompilerSystemProperties.class}
     * as a RESOURCE. Parent-first, that resource comes from the APPLICATION
     * classpath, where the Analysis API's jar sits ahead of the script engine's
     * compiler jar. The root it computes is therefore the Analysis API's jar, and the
     * compiler then reads
     *
     * <pre>
     * META-INF/extensions/compiler.xml
     * </pre>
     *
     * out of THAT jar. The Analysis API's copy of that file has no {@code <id>} and
     * no {@code <name>}, so the IntelliJ plugin loader throws
     *
     * <pre>
     * RuntimeException: Neither id nor name are specified
     * </pre>
     *
     * and the script engine cannot initialise its REPL at all. The symptom is
     * "every DSL rule failed to compile" with a compiler error that says nothing
     * about plugins.
     *
     * <p>So the boundary has to hold for RESOURCES as well as for CLASSES: a
     * resource that belongs to an owned package must be served from this loader's
     * own jars.
     *
     * @param name the resource name
     * @return the resource URL, or null when it is not found
     */
    override fun getResource(name: String): URL? {
        if (isOwnedResource(name)) {
            findResource(name)?.let { return it }
        }
        return super.getResource(name)
    }

    /**
     * Finds every copy of a resource, this loader's own jars first.
     *
     * @param name the resource name
     * @return the resource URLs, this loader's own first
     */
    override fun getResources(name: String): java.util.Enumeration<URL> {
        val own = if (isOwnedResource(name)) findResources(name) else null
        if (own == null || !own.hasMoreElements()) {
            return super.getResources(name)
        }
        val combined = java.util.ArrayList<URL>()
        while (own.hasMoreElements()) {
            combined.add(own.nextElement())
        }
        val parent = super.getResources(name)
        while (parent.hasMoreElements()) {
            combined.add(parent.nextElement())
        }
        return java.util.Collections.enumeration(combined)
    }

    /**
     * Whether a resource belongs to the packages this loader owns.
     *
     * <p>The check is on the resource PATH, and the path of a class resource starts
     * with the package directory. The IntelliJ platform looks for
     * {@code META-INF/extensions/compiler.xml}, which carries no package
     * information at all, so it cannot be classified this way. It is handled
     * separately below.
     *
     * @param name the resource name
     * @return true when this loader should serve the resource itself
     */
    private fun isOwnedResource(name: String): Boolean {
        if (name.startsWith("META-INF/extensions/")) {
            // The compiler's OWN extension points. The Analysis API ships a copy of
            // this file without an <id>, and reading THAT copy is what breaks the
            // script engine.
            return true
        }
        return OWNED_RESOURCE_PREFIXES.any { name.startsWith(it) }
    }

    /**
     * Whether a class belongs to the packages this loader owns.
     *
     * @param name the binary class name
     * @return true when this loader serves the class itself
     */
    private fun isOwned(name: String): Boolean =
        name.startsWith("org.jetbrains.kotlin.") ||
            name.startsWith("kotlin.script.")

    companion object {
        /**
         * The resource paths this loader serves from its own jars.
         *
         * <p>These are the compiler's own resources, which the Analysis API's jar
         * also carries. The script engine must read its own copy.
         */
        private val OWNED_RESOURCE_PREFIXES = listOf(
            "org/jetbrains/kotlin/",
            "kotlin/script/",
        )

        /**
         * The jars whose contents this loader owns.
         *
         * <p>Each entry is the artifact name followed by a dash, so the version is
         * whatever follows it in the file name.
         */
        private val OWNED_ARTIFACT_PREFIXES = listOf(
            "kotlin-scripting-jsr223-",
            "kotlin-scripting-jvm-host-",
            "kotlin-scripting-jvm-",
            "kotlin-scripting-common-",
            "kotlin-scripting-compiler-embeddable-",
            "kotlin-scripting-compiler-impl-embeddable-",
            "kotlin-compiler-embeddable-",
            "kotlin-script-runtime-",
            "kotlin-daemon-embeddable-",
        )

        /**
         * Selects the script engine's own jars from a classpath.
         *
         * <p>The standard library and the reflection library are NOT in the list: they
         * are shared with the application loader on purpose, so that classes like
         * {@code kotlin.coroutines.CoroutineContext} exist once in the JVM.
         *
         * <p>The Analysis API's jars are not in the list either, and must never be:
         * they carry a second Kotlin compiler, and this loader exists to keep the
         * two apart.
         *
         * @param classpath the application's classpath entries
         * @return the jars this loader should own
         */
        fun selectOwnedJars(classpath: List<URL>): List<URL> {
            val byArtifact = linkedMapOf<String, URL>()
            for (url in classpath) {
                val name = url.file.substringAfterLast('/')
                val prefix = OWNED_ARTIFACT_PREFIXES.firstOrNull { name.startsWith(it) } ?: continue
                // Keep the newest version of each artifact.
                val version = name.removePrefix(prefix).removeSuffix(".jar")
                val existing = byArtifact[prefix]
                if (existing == null) {
                    byArtifact[prefix] = url
                    continue
                }
                val existingVersion = existing.file.substringAfterLast('/')
                    .removePrefix(prefix).removeSuffix(".jar")
                if (compareVersions(version, existingVersion) > 0) {
                    byArtifact[prefix] = url
                }
            }
            return byArtifact.values.toList()
        }

        /**
         * Compares two version strings numerically, segment by segment.
         *
         * @param a the first version
         * @param b the second version
         * @return a negative number, zero, or a positive number
         */
        private fun compareVersions(a: String, b: String): Int {
            val left = a.split('.', '-')
            val right = b.split('.', '-')
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i)?.toIntOrNull() ?: 0
                val r = right.getOrNull(i)?.toIntOrNull() ?: 0
                if (l != r) {
                    return l - r
                }
            }
            return 0
        }
    }
}
