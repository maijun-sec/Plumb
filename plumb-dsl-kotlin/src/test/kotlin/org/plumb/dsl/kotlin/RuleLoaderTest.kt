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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rule loader: both file formats, and the metadata each one carries.
 */
class RuleLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an xml rule file is loaded`() {
        val file = tempDir.resolve("rules.xml")
        Files.writeString(
            file,
            """
            <rules>
                <rule language="java">
                    <id>BF350B98-B7D9-45B8-AF63-C3950D58E748</id>
                    <name>empty catch block</name>
                    <description>empty catch block</description>
                    <matcher>
                        <![CDATA[
                            CatchClause catchClause: catchClause match { empty }
                        ]]>
                    </matcher>
                </rule>
            </rules>
            """.trimIndent()
        )
        val rules = RuleLoader.load(file)
        assertThat(rules).hasSize(1)
        val rule = rules[0]
        assertThat(rule.id).isEqualTo("BF350B98-B7D9-45B8-AF63-C3950D58E748")
        assertThat(rule.name).isEqualTo("empty catch block")
        assertThat(rule.nodeType).isEqualTo("CatchClause")
        assertThat(rule.matcher).contains("catchClause match { empty }")
    }

    @Test
    fun `a yaml rule file is loaded with its metadata`() {
        val file = tempDir.resolve("rules.yaml")
        Files.writeString(
            file,
            """
            rules:
              - id: plumb.dsl.empty-catch-block
                name: empty catch block
                description: Catch clause with an empty body
                severity: MEDIUM
                type: BAD_PRACTICE
                cwe: [CWE-390]
                nodeType: CatchClause
                matcher: |
                  CatchClause catchClause: catchClause match { empty }
            """.trimIndent()
        )
        val rules = RuleLoader.load(file)
        assertThat(rules).hasSize(1)
        val rule = rules[0]
        assertThat(rule.id).isEqualTo("plumb.dsl.empty-catch-block")
        assertThat(rule.severity).isEqualTo("MEDIUM")
        assertThat(rule.type).isEqualTo("BAD_PRACTICE")
        assertThat(rule.cwe).containsExactly("CWE-390")
        assertThat(rule.nodeType).isEqualTo("CatchClause")
    }

    @Test
    fun `a missing file yields no rules instead of throwing`() {
        val rules = RuleLoader.load(tempDir.resolve("nope.xml"))
        assertThat(rules).isEmpty()
    }

    @Test
    fun `a malformed file yields no rules instead of throwing`() {
        val file = tempDir.resolve("broken.xml")
        Files.writeString(file, "<rules><rule>unclosed")
        val rules = RuleLoader.load(file)
        assertThat(rules).isEmpty()
    }

    @Test
    fun `an upstream id is normalised to the plumb convention`() {
        val rule = DslRule(
            id = "BF350B98-B7D9-45B8-AF63-C3950D58E748",
            name = "empty catch block",
            description = "empty catch block",
            nodeType = "CatchClause",
            matcher = "CatchClause catchClause: catchClause match { empty }"
        )
        assertThat(rule.normalisedId).isEqualTo("plumb.dsl.empty-catch-block")
        // The original id is kept as an alias so it stays traceable.
        val def = rule.toRuleDef()
        assertThat(def.aliases()).containsExactly("BF350B98-B7D9-45B8-AF63-C3950D58E748")
    }

    @Test
    fun `a plumb id is left alone`() {
        val rule = DslRule(
            id = "plumb.dsl.empty-catch-block",
            name = "empty catch block",
            description = "",
            nodeType = "CatchClause",
            matcher = ""
        )
        assertThat(rule.normalisedId).isEqualTo("plumb.dsl.empty-catch-block")
        assertThat(rule.toRuleDef().aliases()).isEmpty()
    }
}
