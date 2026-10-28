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
/*
 * R-803 P3.3 ? SARIF 2.1.0 emitter tests. The emitter must produce
 * a valid SARIF document with v1 dataflow_steps rendered as standard
 * SARIF `codeFlows` and the v2 trace attached under
 * `properties.v2_trace`. v1-only findings (no v2 trace) must still
 * produce valid SARIF (omitting the v2_trace property and
 * codeFlows for empty dataflow_steps lists).
 */
package org.plumb.reporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.Trace.CompressionLevel;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

class SarifEmitterTest {

  @Test
  void emitsValidSarifTopLevel() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("dfa-taint-test", "TEST"),
            "taint",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "X",
            true);
    Finding f =
        new Finding(
            Path.of("Test.java"),
            10,
            5,
            rule,
            "Tainted value flows to sink",
            RuleDef.Severity.HIGH,
            "taint -> sink");
    StringWriter sw = new StringWriter();
    new SarifEmitter(sw).emit(List.of(f));
    String out = sw.toString();

    // SARIF 2.1.0 mandatory fields
    assertThat(out).contains("\"version\":\"2.1.0\"");
    assertThat(out).contains("\"$schema\":");
    assertThat(out).contains("\"runs\":[");
    assertThat(out).contains("\"tool\":");
    assertThat(out).contains("\"driver\":");
    assertThat(out).contains("\"name\":\"plumb\"");
    assertThat(out).contains("\"results\":[");
    // single result, valid JSON object
    assertThat(out).contains("\"ruleId\":\"dfa-taint-test\"");
    // CRITICAL/HIGH -> error; MEDIUM -> warning; LOW/INFO -> note
    assertThat(out).contains("\"level\":\"error\"");
    assertThat(out).contains("\"message\":{\"text\":");
    // location with file + line
    assertThat(out).contains("\"artifactLocation\":{\"uri\":\"Test.java\"}");
    assertThat(out).contains("\"startLine\":10");
    assertThat(out).contains("\"startColumn\":5");
    // cwe list in properties
    assertThat(out).contains("\"properties\":");
    assertThat(out).contains("\"cwe\":[\"CWE-89\"]");
  }

  @Test
  void severityMapMatchesSarifLevel() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("x", "X"),
            "x",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.TAINT,
            List.of(),
            "X",
            true);
    Finding f = new Finding(Path.of("X.java"), 1, 1, rule, "msg", RuleDef.Severity.MEDIUM, null);
    StringWriter sw = new StringWriter();
    new SarifEmitter(sw).emit(List.of(f));
    assertThat(sw.toString()).contains("\"level\":\"warning\"");
  }

  @Test
  void codeFlowsFromDataflowSteps() throws Exception {
    // Build a Finding with v1 dataflow_steps.
    RuleDef rule =
        new RuleDef(
            new RuleId("dfa-taint-sql", "SQL_INJECTION"),
            "taint",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "SqlInjectionChecker",
            true);
    List<FlowStep> v1Steps =
        List.of(
            new FlowStep("source", "Foo.java", 1, 5, "Foo#bad()", "BufferedReader.readLine"),
            new FlowStep("propagate", "Foo.java", 2, 9, "Foo#bad()", "MOVE x -> y"),
            new FlowStep("sink", "Foo.java", 3, 9, "Foo#bad()", "Statement.execute"));
    Finding f =
        new Finding(
            Path.of("Foo.java"),
            3,
            9,
            rule,
            "taint -> sink",
            RuleDef.Severity.CRITICAL,
            "taint",
            v1Steps);
    StringWriter sw = new StringWriter();
    new SarifEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    assertThat(out).contains("\"codeFlows\":[");
    assertThat(out).contains("\"threadFlows\":[");
    assertThat(out).contains("\"locations\":[");
    // 3 threadFlow locations, each with role property
    assertThat(out).contains("\"role\":\"source\"");
    assertThat(out).contains("\"role\":\"propagate\"");
    assertThat(out).contains("\"role\":\"sink\"");
    assertThat(out).contains("\"uri\":\"Foo.java\"");
  }

  @Test
  void v2TraceAttachedAsProperty() throws Exception {
    // Same source-propagate-sink DAG as NdjsonEmitterTest.
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.INPUT_READLINE,
            List.of(new OperandRef.CallRef("java.io.BufferedReader.readLine/0")),
            "input readLine",
            "Foo.java",
            1,
            5,
            "Foo#bad()");
    int mv =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("x", 0), new OperandRef.Var("y", 0)),
            src,
            "MOVE x -> y",
            "Foo.java",
            2,
            9,
            "Foo#bad()");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("Statement.execute/1")),
            mv,
            "sql sink",
            "Foo.java",
            3,
            9,
            "Foo#bad()");
    Trace v2 = tb.build(List.of(sink), CompressionLevel.FULL);

    RuleDef rule =
        new RuleDef(
            new RuleId("dfa-taint-sql-injection", "SQL_INJECTION"),
            "taint",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "SqlInjectionChecker",
            true);
    Finding f =
        new Finding(
                Path.of("Foo.java"),
                3,
                9,
                rule,
                "taint -> sink",
                RuleDef.Severity.CRITICAL,
                "taint")
            .withTrace(v2);
    StringWriter sw = new StringWriter();
    new SarifEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    assertThat(out).contains("\"v2_trace\":");
    assertThat(out).contains("\"SOURCE\"");
    assertThat(out).contains("\"SINK\"");
    assertThat(out).contains("INPUT_READLINE");
    assertThat(out).contains("SINK_SQL");
  }

  @Test
  void emitsMultipleFindingsAsMultipleResults() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("x", "X"),
            "x",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "X",
            true);
    Finding f1 = new Finding(Path.of("A.java"), 1, 1, rule, "m1", RuleDef.Severity.LOW, null);
    Finding f2 = new Finding(Path.of("B.java"), 2, 2, rule, "m2", RuleDef.Severity.LOW, null);
    StringWriter sw = new StringWriter();
    new SarifEmitter(sw).emit(List.of(f1, f2));
    String out = sw.toString();
    // two artifacts and two results
    assertThat(out).contains("\"uri\":\"A.java\"");
    assertThat(out).contains("\"uri\":\"B.java\"");
  }

  @Test
  void omitsStartColumnWhenNegative() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("x", "X"),
            "x",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "X",
            true);
    // column=-1 means unknown; SARIF region without column
    Finding f = new Finding(Path.of("X.java"), 1, -1, rule, "m", RuleDef.Severity.LOW, null);
    StringWriter sw = new StringWriter();
    new SarifEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    // startLine present, but no startColumn (none == -1 path)
    assertThat(out).contains("\"startLine\":1");
    assertThat(out).doesNotContain("\"startColumn\":-1");
  }
}
