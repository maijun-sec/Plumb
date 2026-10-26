package org.plumb.dsl.kotlin

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads AST rules from a rule file.
 *
 * <p>Two formats are supported:
 *
 * <ul>
 *   <li><b>XML</b> -- the upstream format, so an upstream rule file works
 *       unchanged. The matcher lives in a `matcher` element's CDATA.
 *   <li><b>YAML</b> -- the same rules with Plumb's metadata (severity, CWE,
 *       enabled) alongside.
 * </ul>
 *
 * <p>The format is chosen by file extension: `.xml` is XML, everything else
 * is YAML. A file that cannot be parsed is reported as an error and yields
 * no rules -- one bad rule file must not take the whole scan down.
 */
object RuleLoader {

    private val LOG = LoggerFactory.getLogger(RuleLoader::class.java)

    /** Loads the rules in the given file. Returns an empty list on failure. */
    fun load(file: Path): List<DslRule> {
        if (!Files.isRegularFile(file)) {
            LOG.error("DSL rule file not found: {}", file)
            return emptyList()
        }
        return try {
            val name = file.fileName.toString().lowercase()
            if (name.endsWith(".xml")) loadXml(file) else loadYaml(file)
        } catch (ex: Exception) {
            LOG.error("Failed to load DSL rule file {}: {}", file, ex.toString())
            emptyList()
        }
    }

    /** Loads the rules in the given files, concatenating the results. */
    fun loadAll(files: List<Path>): List<DslRule> = files.flatMap { load(it) }

    // ─── XML (the upstream format) ──────────────────────────────────────

    private fun loadXml(file: Path): List<DslRule> {
        val rules = ArrayList<DslRule>()
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file.toFile())
        val root = doc.documentElement
        val children = root.childNodes
        var i = 0
        while (i < children.length) {
            val node = children.item(i)
            i++
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            if (node.nodeName != "rule") continue
            rules.add(readXmlRule(node as org.w3c.dom.Element, file.toString()))
        }
        return rules
    }

    private fun readXmlRule(element: org.w3c.dom.Element, sourceFile: String): DslRule {
        var id = ""
        var name = ""
        var description = ""
        var matcher = ""
        var severity = "MEDIUM"
        var type = "OTHER"
        var cwe = emptyList<String>()
        var enabled = true

        val children = element.childNodes
        var i = 0
        while (i < children.length) {
            val child = children.item(i)
            i++
            if (child.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val el = child as org.w3c.dom.Element
            when (el.nodeName) {                "id" -> id = el.textContent.trim()
                "name" -> name = el.textContent.trim()
                "description" -> description = el.textContent.trim()
                "matcher" -> matcher = el.textContent
                "severity" -> severity = el.textContent.trim()
                "type" -> type = el.textContent.trim()
                "enabled" -> enabled = el.textContent.trim().toBoolean()
                "cwe" -> cwe = el.textContent.trim()
                    .split(",", " ", "\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                else -> {
                    // An unknown element is not fatal -- the upstream files
                    // carry a "language" attribute and a few extra tags.
                }
            }
        }
        return DslRule(
            id = id,
            name = name.ifBlank { id },
            description = description.ifBlank { name.ifBlank { id } },
            nodeType = nodeTypeOf(matcher),
            matcher = matcher,
            severity = severity,
            type = type,
            cwe = cwe,
            enabled = enabled,
            sourceFile = sourceFile
        )
    }

    /**
     * The node type a matcher names, taken from the `<NodeType> <binding>:`
     * header.
     *
     * <p>The upstream rule files do not carry the node type in a separate
     * element -- it is part of the matcher text -- so it is read from
     * there.
     */
    private fun nodeTypeOf(matcher: String): String {
        val text = matcher.trim()
        if (text.isEmpty()) return ""
        val colon = text.indexOf(':')
        if (colon < 0) return ""
        val header = text.substring(0, colon).trim()
        return header.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .firstOrNull() ?: ""
    }

    // ─── YAML ───────────────────────────────────────────────────────────

    private fun loadYaml(file: Path): List<DslRule> {
        val text = Files.readString(file)
        val parsed = Yaml().load<Any?>(text)
        val entries: List<Map<String, Any?>> = when (parsed) {
            null -> emptyList()
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val rules = parsed["rules"]
                if (rules is List<*>) {
                    rules.filterIsInstance<Map<*, *>>().map {
                        @Suppress("UNCHECKED_CAST")
                        it as Map<String, Any?>
                    }
                } else {
                    emptyList()
                }
            }
            is List<*> -> parsed.filterIsInstance<Map<*, *>>().map {
                @Suppress("UNCHECKED_CAST")
                it as Map<String, Any?>
            }
            else -> emptyList()
        }
        return entries.map { readYamlRule(it, file.toString()) }
    }

    private fun readYamlRule(entry: Map<String, Any?>, sourceFile: String): DslRule {
        fun str(key: String): String = (entry[key] as? String)?.trim() ?: ""
        val id = str("id")
        val name = str("name").ifBlank { id }
        val cwe = when (val raw = entry["cwe"]) {
            is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            is List<*> -> raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return DslRule(
            id = id,
            name = name,
            description = str("description").ifBlank { name },
            nodeType = str("nodeType"),
            matcher = str("matcher"),
            severity = str("severity").ifBlank { "MEDIUM" },
            type = str("type").ifBlank { "OTHER" },
            cwe = cwe,
            enabled = (entry["enabled"] as? Boolean) ?: true,
            sourceFile = sourceFile
        )
    }
}
