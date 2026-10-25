/*
 * P3.3 ? SARIF 2.1.0 emitter consuming the v2 structured
 * trace. The v1 dataflow_steps is rendered as standard SARIF
 * `codeFlows` (the conventional shape that SARIF-aware IDEs and
 * GitHub Code Scanning understand). The v2 trace is attached
 * verbatim under `properties.v2_trace` so SARIF v2-aware
 * consumers (Plumb-UI trace viewer, custom IDE plugins) see the
 * full DAG without having to re-parse the v1 flat path.
 *
 * <p>SARIF shape (relevant fields):</p>
 * <pre>
 * { "version": "2.1.0",
 *   "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
 *   "runs": [{
 *     "tool": { "driver": { "name": "plumb", "version": "...", "informationUri": "..." } },
 *     "results": [{
 *       "ruleId":     "dfa-taint-sql-injection",
 *       "level":      "error" | "warning" | "note",
 *       "message":    { "text": "..." },
 *       "locations":  [{ "physicalLocation": { "artifactLocation": { "uri": "..." }, "region": { "startLine": ..., "startColumn": ... } } }],
 *       "properties": {
 *         "cwe":        ["CWE-89"],
 *         "v2_trace":   { ... full DAG as in TraceJsonSerializer ... }
 *       },
 *       "codeFlows":  [{ "threadFlows": [{ "locations": [{ "location": { "physicalLocation": { ... }, "message": { "text": "..." }, "properties": { "role": "SOURCE"|"PROPAGATE"|"SINK" } } }] }] }]
 *     }]
 *   }]
 * }
 * </pre>
 *
 * <p>Backwards compat: when {@code f.trace()} is null, the
 * v2_trace property is omitted. {@code codeFlows} is omitted
 * when {@code f.dataflowSteps()} is empty.</p>
 */
package org.plumb.reporter;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceJsonSerializer;
import org.plumb.rules.RuleDef;

public final class SarifEmitter {

  private static final String SCHEMA_URL =
      "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
  private static final String TOOL_NAME = "plumb";
  private static final String TOOL_VERSION = "0.1.0";
  private static final String TOOL_INFO_URI = "https://github.com/example/plumb";

  private final Writer out;

  public SarifEmitter(Writer out) {
    this.out = out;
  }

  public void emit(List<Finding> findings) throws IOException {
    StringBuilder sb = new StringBuilder(4096);
    sb.append('{');
    kv(sb, "version", "2.1.0");
    sb.append(',');
    kv(sb, "$schema", SCHEMA_URL);
    sb.append(',');
    sb.append("\"runs\":[");
    sb.append('{');
    sb.append("\"tool\":{");
    sb.append("\"driver\":{");
    kv(sb, "name", TOOL_NAME);
    sb.append(',');
    kv(sb, "version", TOOL_VERSION);
    sb.append(',');
    kv(sb, "informationUri", TOOL_INFO_URI);
    sb.append("}},");
    sb.append("\"results\":[");
    boolean first = true;
    for (Finding f : findings) {
      if (!first) sb.append(',');
      first = false;
      emitResult(sb, f);
    }
    sb.append("]");
    sb.append('}');
    sb.append("]}");
    out.write(sb.toString());
    out.flush();
  }

  private static void emitResult(StringBuilder sb, Finding f) {
    sb.append('{');
    kv(sb, "ruleId", ruleIdOf(f));
    sb.append(',');
    kv(sb, "level", levelOf(f.severity()));
    sb.append(',');
    sb.append("\"message\":{");
    kv(sb, "text", f.message());
    sb.append("},");
    sb.append("\"locations\":[{");
    sb.append("\"physicalLocation\":{");
    sb.append("\"artifactLocation\":{");
    kv(sb, "uri", f.file() == null ? "" : f.file().toString());
    sb.append("},");
    sb.append("\"region\":{");
    sb.append("\"startLine\":").append(f.line());
    if (f.column() >= 0) {
      sb.append(",\"startColumn\":").append(f.column());
    }
    sb.append("}}"); // close region, physicalLocation
    sb.append("}]"); // close location-outer, locations array

    // codeFlows from v1 dataflow_steps
    if (!f.dataflowSteps().isEmpty()) {
      // codeFlows = [ CodeFlow ]
      // CodeFlow  = { threadFlows: [ ThreadFlow ] }
      // ThreadFlow = { locations: [ ThreadFlowLocation ] }
      // ThreadFlowLocation = { location: Location, properties: {role:"..."} }
      // Location = { physicalLocation: {artifactLocation:{uri:""}, region:{startLine,startColumn}},
      // message:{text:""} }
      sb.append(",\"codeFlows\":[{"); // [CodeFlow + open CodeFlow object
      sb.append("\"threadFlows\":[{"); // threadFlows array + open ThreadFlow object
      sb.append("\"locations\":["); // locations array
      for (int i = 0; i < f.dataflowSteps().size(); i++) {
        if (i > 0) sb.append(',');
        FlowStep st = f.dataflowSteps().get(i);
        // open ThreadFlowLocation
        sb.append("{\"location\":{");
        // physicalLocation
        sb.append("\"physicalLocation\":{");
        sb.append("\"artifactLocation\":{");
        kv(sb, "uri", st.filePath() == null ? "" : st.filePath());
        sb.append("},\"region\":{");
        sb.append("\"startLine\":").append(st.line());
        if (st.column() >= 0) {
          sb.append(",\"startColumn\":").append(st.column());
        }
        sb.append("}}"); // close region, physicalLocation
        // message
        sb.append(",\"message\":{");
        kv(sb, "text", st.expr() == null ? "" : st.expr());
        sb.append("}"); // close message object
        // close Location object, close ThreadFlowLocation object
        sb.append("},\"properties\":{");
        kv(sb, "role", st.role() == null ? "" : st.role());
        sb.append("}}"); // close properties, ThreadFlowLocation
      }
      sb.append("]"); // close locations array
      sb.append("}]"); // close ThreadFlow, threadFlows array
      sb.append("}]"); // close CodeFlow, codeFlows array
    }

    // properties — cwe + optional v2_trace
    sb.append(",\"properties\":{");
    List<String> cweList = cweListOf(f);
    if (!cweList.isEmpty()) {
      sb.append("\"cwe\":[");
      for (int i = 0; i < cweList.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append('"').append(esc(cweList.get(i))).append('"');
      }
      sb.append(']');
    }
    Trace v2 = f.trace();
    if (v2 != null) {
      if (!cweList.isEmpty()) sb.append(',');
      sb.append("\"v2_trace\":");
      sb.append(TraceJsonSerializer.toJson(v2));
    }
    // dataflow head as a one-line summary property
    if (f.dataflowHead() != null && !f.dataflowHead().isEmpty()) {
      if (!cweList.isEmpty() || v2 != null) sb.append(',');
      kv(sb, "dataflow", f.dataflowHead());
    }
    sb.append("}");
    sb.append('}');
  }

  private static String ruleIdOf(Finding f) {
    if (f.rule() != null) return f.rule().ruleId().id();
    if (f.ruleId() != null) return f.ruleId();
    return "unknown";
  }

  private static List<String> cweListOf(Finding f) {
    if (f.rule() != null && !f.rule().cwe().isEmpty()) {
      return f.rule().cwe();
    }
    if (f.cwe() != null) {
      return List.of(f.cwe());
    }
    return List.of();
  }

  private static String levelOf(RuleDef.Severity s) {
    return switch (s) {
      case CRITICAL, HIGH -> "error";
      case MEDIUM -> "warning";
      case LOW, INFO -> "note";
    };
  }

  private static void kv(StringBuilder sb, String k, String v) {
    sb.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"');
  }

  private static String esc(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.toString();
  }
}
