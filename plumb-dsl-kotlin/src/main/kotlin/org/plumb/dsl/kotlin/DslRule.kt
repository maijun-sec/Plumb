package org.plumb.dsl.kotlin

import org.plumb.rules.RuleDef

/**
 * One AST rule, as it appears in a rule file.
 *
 * <p>The upstream project uses a random UUID as the rule id. Plumb's rule
 * registry, SARIF output and documentation gates all expect the
 * `plumb.dsl.<slug>` convention, so [normalisedId] maps the id onto that
 * convention while [id] keeps the original for traceability.
 */
data class DslRule(
    /** The id as written in the rule file. */
    val id: String,
    /** The rule's short name. */
    val name: String,
    /** The human-readable description shown in a finding. */
    val description: String,
    /** The AST node type the rule applies to, e.g. `CatchClause`. */
    val nodeType: String,
    /** The matcher text -- the Kotlin-flavoured DSL. */
    val matcher: String,
    /** The rule's severity label; defaults to `MEDIUM`. */
    val severity: String = "MEDIUM",
    /** The rule's type label; defaults to `OTHER`. */
    val type: String = "OTHER",
    /** The CWE ids this rule reports. */
    val cwe: List<String> = emptyList(),
    /** Whether the rule runs by default. */
    val enabled: Boolean = true,
    /** Where the rule came from -- the file path. */
    val sourceFile: String = "",
    /** The 1-based line the rule's matcher starts on, for error messages. */
    val sourceLine: Int = 0
) {

    /**
     * The id in Plumb's convention.
     *
     * <p>An id that already looks like a Plumb rule id is kept as-is; any
     * other id (the upstream UUIDs, say) is reduced to a slug derived from
     * the rule's name.
     */
    val normalisedId: String
        get() {
            val trimmed = id.trim()
            if (trimmed.startsWith("plumb.")) return trimmed
            val slug = name.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "rule" }
            return "plumb.dsl.$slug"
        }

    /** The rule's metadata in Plumb's own model. */
    fun toRuleDef(): RuleDef {
        val severityEnum =
            try {
                RuleDef.Severity.valueOf(severity.trim().uppercase())
            } catch (ex: IllegalArgumentException) {
                RuleDef.Severity.MEDIUM
            }
        val typeEnum =
            try {
                RuleDef.Type.valueOf(type.trim().uppercase())
            } catch (ex: IllegalArgumentException) {
                RuleDef.Type.OTHER
            }
        val upperSnake = name.trim().uppercase().replace('-', '_').replace(' ', '_')
        return RuleDef(
            org.plumb.rules.RuleId(normalisedId, upperSnake),
            description.ifBlank { name },
            severityEnum,
            typeEnum,
            cwe,
            "org.plumb.dsl.kotlin.DslAstChecker",
            enabled,
            if (normalisedId == id.trim()) emptyList() else listOf(id.trim())
        )
    }
}
