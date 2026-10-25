package org.plumb.reporter;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceJsonSerializer;
import org.plumb.rules.RuleDef;

/**
 * Emit findings as NDJSON (one JSON object per line). We hand-roll the JSON to avoid dragging in a
 * heavy dependency. All string values are properly escaped.
 */
public final class NdjsonEmitter {

  private final Writer out;

  public NdjsonEmitter(Writer out) {
    this.out = out;
  }

  public void emit(List<Finding> findings) throws IOException {
    for (Finding f : findings) emitOne(f);
    out.flush();
  }

  public void emitOne(Finding f) throws IOException {
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    kv(sb, "file", f.file().toString());
    sb.append(',');
    // M-1 (audit 2026-10-08): line and column are JSON
    // numbers, not strings, so downstream `jq '.line > 100'`
    // and `int(row["line"])` work without surprises.
    sb.append("\"line\":").append(f.line()).append(',');
    sb.append("\"column\":").append(f.column()).append(',');
    kv(sb, "severity", f.severity().name().toLowerCase(Locale.ROOT));
    sb.append(',');
    // when a RuleDef is present, use it as the canonical
    // source for rule_id/rule_name/rule_type/cwe. When absent
    // (DFA-emitted finding before ScanRunner enrichment), fall
    // back to the legacy ruleId/cwe fields so the NDJSON line
    // still carries the CWE family the eval pipeline needs.
    if (f.rule() != null) {
      RuleDef r = f.rule();
      kv(sb, "rule_id", r.ruleId().id());
      sb.append(',');
      kv(sb, "rule_name", r.ruleId().name());
      sb.append(',');
      kv(sb, "rule_type", r.type().name().toLowerCase(Locale.ROOT));
      sb.append(',');
      if (!r.cwe().isEmpty()) {
        sb.append("\"cwe\":[");
        for (int i = 0; i < r.cwe().size(); i++) {
          if (i > 0) sb.append(',');
          sb.append('"').append(esc(r.cwe().get(i))).append('"');
        }
        sb.append("],");
      }
    } else if (f.ruleId() != null) {
      kv(sb, "rule_id", f.ruleId());
      sb.append(',');
      if (f.checkerName() != null) {
        kv(sb, "rule_name", f.checkerName());
        sb.append(',');
      }
      if (f.cwe() != null) {
        sb.append("\"cwe\":[\"").append(esc(f.cwe())).append("\"],");
      }
    }
    kv(sb, "message", f.message());
    if (f.dataflowHead() != null) {
      sb.append(',');
      kv(sb, "dataflow", f.dataflowHead());
    }
    // R-— — emit the structured source-to-sink trace
    // as a JSON array of strings, one per step. Each
    // entry is "<role> | <file>:<line>:<col> | <expr>"
    // (FlowStep.toCanonicalString()) so consumers can
    // render the full path without having to
    // re-parse. Empty for findings without a
    // tracked trace.
    if (!f.dataflowSteps().isEmpty()) {
      sb.append(",\"dataflow_steps\":[");
      for (int i = 0; i < f.dataflowSteps().size(); i++) {
        if (i > 0) sb.append(',');
        sb.append('"').append(esc(f.dataflowSteps().get(i).toCanonicalString())).append('"');
      }
      sb.append(']');
    }
    // P3.2: emit the v2 structured trace as
    // a JSON object. Only emitted when the finding
    // was dual-written (P2 + P3.1 emit path). v1-only
    // findings have trace() == null and the field
    // is omitted to keep back-compat with consumers
    // that don't recognise the new key. The DAG is
    // canonical; the v1 dataflow_steps list above is
    // the legacy flat-path rendering derived from it.
    Trace v2 = f.trace();
    if (v2 != null) {
      sb.append(",\"v2_trace\":");
      sb.append(TraceJsonSerializer.toJson(v2));
    }
    // R-— — AI dedup verdict (TP / FP / UNKNOWN) +
    // the model's one-sentence reason. Only emitted
    // when --ai-dedup was run.
    if (f.aiVerdict() != null) {
      sb.append(',');
      kv(sb, "ai_verdict", f.aiVerdict());
      if (f.aiReason() != null) {
        sb.append(',');
        kv(sb, "ai_reason", f.aiReason());
      }
    }
    // single sink_function field. The function IS
    // its own signature ("FQN#method(args):ret"). One
    // field is enough; consumers that need just the method
    // name can split at '#' and strip from '(' onward.
    if (f.sinkFunction() != null && !f.sinkFunction().isEmpty()) {
      sb.append(',');
      kv(sb, "sink_function", f.sinkFunction());
    }
    sb.append("}\n");
    out.write(sb.toString());
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
