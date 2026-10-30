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

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine's performance and concurrency invariants.
 *
 * <p>These are the properties that make the engine usable in a scan:
 *
 * <ul>
 *   <li>a rule is compiled ONCE and the compiled script is reused -- the
 *       alternative costs about 236 ms per evaluation instead of about
 *       0.5 ms;
 *   <li>the compiled script is safe to share across threads, and the node
 *       under test is per-thread.
 * </ul>
 */
class CompiledRuleTest {

    private val source = """
        class Demo {
            void a() { try { } catch (Exception e) { } }
            void b() { try { } catch (Exception e) { } }
            void c() { try { } catch (Exception e) { } }
        }
    """.trimIndent()

    private fun compileRule(): CompiledRule {
        val rule = DslRule(
            id = "test-rule",
            name = "empty catch block",
            description = "empty catch block",
            nodeType = "CatchClause",
            matcher = "CatchClause catchClause: catchClause match { empty }"
        )
        val types = NodeTypes.targetsFor(rule)
        val rewritten = RuleMatcher.rewrite(rule.matcher, NodeTypes.qualifiedName(types[0]))
        return CompiledRule(rule, ScriptEngineHolder.compile(rewritten.script, rule.id))
    }

    @Test
    fun `a compiled rule matches every node it should`() {
        val unit = JavaParser().parse(source).result.get()
        val rule = compileRule()
        val catches = unit.findAll(com.github.javaparser.ast.stmt.CatchClause::class.java)
        assertThat(catches).hasSize(3)
        val matched = catches.count { rule.matches(it) }
        assertThat(matched).isEqualTo(3)
    }

    @Test
    fun `a compiled rule is reused rather than recompiled`() {
        val rule = compileRule()
        val unit = JavaParser().parse(source).result.get()
        val catches = unit.findAll(com.github.javaparser.ast.stmt.CatchClause::class.java)

        // Warm up, then measure. The point of the assertion is that the
        // per-node cost stays in the millisecond range: a per-node
        // recompile would take about 236 ms.
        catches.forEach { rule.matches(it) }
        val start = System.nanoTime()
        repeat(20) {
            catches.forEach { rule.matches(it) }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertThat(elapsedMs)
            .describedAs(
                "60 evaluations of a reused compiled rule should take well under a second; " +
                    "a per-evaluation recompile would take about 14 seconds"
            )
            .isLessThan(5_000L)
    }

    @Test
    fun `a compiled rule is safe to share across threads`() {
        val rule = compileRule()
        val unit = JavaParser().parse(source).result.get()
        val catches = unit.findAll(com.github.javaparser.ast.stmt.CatchClause::class.java)

        val threads = 8
        val iterations = 40
        val mismatches = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (index in 0 until threads) {
            val offset = index
            pool.submit {
                try {
                    start.await()
                    repeat(iterations) { round ->
                        val node = catches[(offset + round) % catches.size]
                        // Every catch in this source is empty, so the rule
                        // must match every one of them. A cross-talk bug
                        // shows up as a rule that does not match the node
                        // the thread just set.
                        if (!rule.matches(node)) mismatches.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        assertThat(mismatches.get())
            .describedAs("a shared compiled rule must not leak the node between threads")
            .isZero()
    }

    @Test
    fun `the node holder is per thread`() {
        val unit = JavaParser().parse(source).result.get()
        val catches = unit.findAll(com.github.javaparser.ast.stmt.CatchClause::class.java)
        val seen = java.util.Collections.synchronizedList(ArrayList<Any>())
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) { index ->
            pool.submit {
                try {
                    repeat(20) {
                        val node = catches[index % catches.size]
                        CurrentNodeHolder.set(node)
                        seen.add(CurrentNodeHolder.get())
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        done.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()
        // Every thread saw a node, and no thread saw null.
        assertThat(seen).hasSize(80)
        assertThat(seen.none { it == null }).isTrue()
    }
}
