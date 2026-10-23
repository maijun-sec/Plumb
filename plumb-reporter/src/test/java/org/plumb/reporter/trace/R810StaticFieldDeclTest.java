/*
 * R-810 static-field resolution ⇒?hermetic fidelity gate
 * for the STATIC_FIELD_DECL emission path added in R-810.
 *
 * <p>R-810 adds two new v2 shape primitives to the trace
 * model so FIELDLOADs on class references (Juliet's
 * {@code IO.STATIC_FINAL_FIVE}, {@code Level.WARNING},
 * {@code IO.logger}) can attach a real parent instead of
 * falling through to the orphan-SOURCE shape:</p>
 * <ul>
 *   <li>{@link TraceNode.Op#STATIC_FIELD_DECL} ⇒?emitted
 *       by the resolver when a {@code public static
 *       final Foo = <literal>} field's value is folded at
 *       scan time. The node carries the
 *       {@code StaticFieldRef(className, fieldName, true)}
 *       operand plus the literal value (typed
 *       {@link OperandRef.ConstInt} /
 *       {@link OperandRef.ConstStr} /
 *       {@link OperandRef.ConstFloat} /
 *       {@link OperandRef.ConstNull}).</li>
 *   <li>{@link OperandRef.StaticFieldRef} ⇒?the
 *       structural marker for a static-field access. Pairs
 *       with the FIELDLOAD PROPAGATE that consumes the
 *       declaration. {@code isFinal=true} captures the
 *       "literal foldable" subset; the resolver's
 *       {@code lookup} skips non-final fields, which
 *       Phase 2 of R-810 will revisit.</li>
 * </ul>
 *
 * <p>This test verifies the v2 trace shape contract
 * (everything that survives NDJSON / SARIF). The full
 * engine integration is exercised by the Juliet
 * CWE89_01 {@code connect_tcp_execute_13} end-to-end
 * run, which proves two of the 21 FIELDLOADs
 * ({@code IO.STATIC_FINAL_FIVE}) now attach to a
 * {@code STATIC_FIELD_DECL} parent instead of an
 * orphan SOURCE.</p>
 */
package org.plumb.reporter.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class R810StaticFieldDeclTest {

  @Test
  void staticFieldDeclOpExistsOnTraceNode() {
    // The enum must include the
    // new op so TraceBuilder
    // .addSource(..., STATIC_FIELD_DECL, ...)
    // compiles. Without this, the
    // R-810 wire would fail at
    // compile time.
    assertThat(TraceNode.Op.valueOf("STATIC_FIELD_DECL"))
        .as("R-810: STATIC_FIELD_DECL is a valid " + "TraceNode.Op")
        .isNotNull();
  }

  @Test
  void staticFieldRefRendersAsClassDotField() {
    OperandRef ref = new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true);
    assertThat(ref.render())
        .as("StaticFieldRef.render() emits " + "className.fieldName verbatim")
        .isEqualTo("testcasesupport.IO.STATIC_FINAL_FIVE");
  }

  @Test
  void staticFieldRefRequiresClassAndField() {
    // Defensive constructor check.
    try {
      new OperandRef.StaticFieldRef(null, "X", true);
      assertThat(false).as("StaticFieldRef must reject null className").isTrue();
    } catch (NullPointerException ok) {
      // expected
    }
    try {
      new OperandRef.StaticFieldRef("X", null, true);
      assertThat(false).as("StaticFieldRef must reject null fieldName").isTrue();
    } catch (NullPointerException ok) {
      // expected
    }
  }

  @Test
  void staticFieldDeclPropagatesLiteralConstInt() {
    // Hero path: FIELDLOAD IO.STATIC_FINAL_FIVE
    // -> PROPAGATE node whose parent is a
    // STATIC_FIELD_DECL carrying the literal
    // value 5. The PROPAGATE node uses
    // StaticFieldRef(className, fieldName, true)
    // and a Var operand for the dest TacVar.
    TraceBuilder tb = new TraceBuilder();
    int decl =
        tb.addSource(
            TraceNode.Op.STATIC_FIELD_DECL,
            java.util.List.of(
                new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true),
                new OperandRef.ConstInt(5L)),
            "static field declaration " + "testcasesupport.IO.STATIC_FINAL_FIVE = INT:5",
            "",
            38,
            -1,
            "bad():void");
    int fl =
        tb.addPropagate(
            TraceNode.Op.FIELDLOAD,
            java.util.List.of(
                new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true),
                new OperandRef.Var("t_t1", 0)),
            java.util.List.of(decl),
            "FIELDLOAD testcasesupport.IO.STATIC_FINAL_FIVE " + "-> t_t1",
            "",
            38,
            -1,
            "bad():void");
    Trace trace = tb.build(java.util.List.of(), Trace.CompressionLevel.FULL);
    // Two nodes: decl (SOURCE) + fl (PROPAGATE).
    assertThat(trace.nodes())
        .as("R-810 hero path: 1 STATIC_FIELD_DECL " + "+ 1 FIELDLOAD PROPAGATE = 2 nodes")
        .hasSize(2);
    TraceNode declNode = trace.nodes().get(0);
    assertThat(declNode.op()).isEqualTo(TraceNode.Op.STATIC_FIELD_DECL);
    assertThat(declNode.role()).isEqualTo(TraceNode.Role.SOURCE);
    assertThat(declNode.parentIds()).as("STATIC_FIELD_DECL is a root (no parents)").isEmpty();
    assertThat(declNode.operands().get(0)).isInstanceOf(OperandRef.StaticFieldRef.class);
    assertThat(declNode.operands().get(1)).isInstanceOf(OperandRef.ConstInt.class);
    TraceNode flNode = trace.nodes().get(1);
    assertThat(flNode.op()).isEqualTo(TraceNode.Op.FIELDLOAD);
    assertThat(flNode.parentIds())
        .as(
            "R-810: FIELDLOAD PROPAGATE attaches the "
                + "STATIC_FIELD_DECL as a real parent "
                + "(not orphan)")
        .containsExactly(decl);
  }

  @Test
  void staticFieldDeclPropagatesLiteralConstStr() {
    // The other literal kind the
    // resolver indexes: a
    // public static final String
    // field. Same shape: decl
    // carries the ConstStr value.
    TraceBuilder tb = new TraceBuilder();
    int decl =
        tb.addSource(
            TraceNode.Op.STATIC_FIELD_DECL,
            java.util.List.of(
                new OperandRef.StaticFieldRef("Config", "DEFAULT_NAME", true),
                new OperandRef.ConstStr("anonymous")),
            "static field declaration " + "Config.DEFAULT_NAME = STRING:anonymous",
            "",
            10,
            -1,
            "init():void");
    TraceNode declNode = tb.build(java.util.List.of(), Trace.CompressionLevel.FULL).nodes().get(0);
    assertThat(declNode.operands().get(0)).isInstanceOf(OperandRef.StaticFieldRef.class);
    assertThat(declNode.operands().get(1)).isInstanceOf(OperandRef.ConstStr.class);
    assertThat(declNode.operands().get(1).render()).isEqualTo("\"anonymous\"");
    assertThat(decl)
        .as("STATIC_FIELD_DECL handle is non-negative " + "(real parent candidate)")
        .isGreaterThanOrEqualTo(0);
  }

  @Test
  void staticFieldDeclIsFinalFlagPreservesLiteralStatus() {
    // R-810 isFinal=true means "literal foldable";
    // R-810 Phase 2 will introduce
    // isFinal=false for init-path
    // (method-call) initialisers. The
    // record's accessor survives
    // both.
    OperandRef literal =
        new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true);
    OperandRef nonLiteral = new OperandRef.StaticFieldRef("testcasesupport.IO", "logger", false);
    assertThat(((OperandRef.StaticFieldRef) literal).isFinal())
        .as("literal-foldable flag")
        .isTrue();
    assertThat(((OperandRef.StaticFieldRef) nonLiteral).isFinal())
        .as("non-literal (Phase 2) flag")
        .isFalse();
    assertThat(literal.render()).isEqualTo("testcasesupport.IO.STATIC_FINAL_FIVE");
    assertThat(nonLiteral.render()).isEqualTo("testcasesupport.IO.logger");
  }

  // ---- R-810 P1.5: this.<Class> class identity fidelity mark ----

  @Test
  void classIdentityBindEmitsMarkSourceWithFidelityNote() {
    // P1.5 hero: the IR's synthetic
    // `t = this.<Class>` form (Juliet
    // `this.IO`, `this.Level` synthetic loads that
    // introduce chained static-field reads) gets a
    // `MARK_SOURCE` node tagged "class identity
    // bind: <ClassName>". This is structurally
    // distinct from a real static-field declaration
    // (no StaticFieldRef operand, no parent) and
    // from a generic orphan FIELDLOAD (note carries
    // the explicit class-name semantics).
    TraceBuilder tb = new TraceBuilder();
    int bind =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            java.util.List.of(
                new OperandRef.Var("this", 0),
                new OperandRef.Var("IO", 0),
                new OperandRef.Var("t_t0", 0)),
            "class identity bind: IO",
            "",
            38,
            -1,
            "bad():void");
    Trace trace = tb.build(java.util.List.of(), Trace.CompressionLevel.FULL);
    assertThat(trace.nodes()).as("class identity bind = 1 MARK_SOURCE").hasSize(1);
    TraceNode bindNode = trace.nodes().get(0);
    assertThat(bindNode.op())
        .as(
            "P1.5: op = MARK_SOURCE so the trace "
                + "consumer can distinguish a class "
                + "identity bind from a regular "
                + "FIELDLOAD or a STATIC_FIELD_DECL")
        .isEqualTo(TraceNode.Op.MARK_SOURCE);
    assertThat(bindNode.role()).isEqualTo(TraceNode.Role.SOURCE);
    assertThat(bindNode.parentIds())
        .as(
            "P1.5: class identity bind is a root "
                + "(no parents) - the class identity "
                + "itself is the trust boundary, not "
                + "a tagged declaration")
        .isEmpty();
    assertThat(bindNode.note())
        .as("P1.5: explicit note keeps the fidelity " + "contract visible to consumers")
        .isEqualTo("class identity bind: IO");
    assertThat(bind).as("class identity bind handle is non-negative").isGreaterThanOrEqualTo(0);
  }

  @Test
  void classIdentityBindChainFollowedByStaticFieldDeclPropagatesRealParent() {
    // P1.5 + P1 contract: the chained FIELDLOAD
    // that follows a class identity bind
    // (e.g. `t.STATIC_FINAL_FIVE` after
    // `t = this.IO`) still attaches the
    // STATIC_FIELD_DECL as its real parent. The
    // class identity bind is orthogonal ⇒?it's
    // a fidelity mark for the intermediate
    // load, not the declaration of a static
    // field.
    TraceBuilder tb = new TraceBuilder();
    int bind =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            java.util.List.of(
                new OperandRef.Var("this", 0),
                new OperandRef.Var("IO", 0),
                new OperandRef.Var("t_t0", 0)),
            "class identity bind: IO",
            "",
            38,
            -1,
            "bad():void");
    // The chained FIELDLOAD lands on a
    // separate STATIC_FIELD_DECL (in real
    // source: `t.STATIC_FINAL_FIVE` resolves
    // via classRefVars + the resolver). The
    // contract is: the bind does NOT become
    // the chained FIELDLOAD's parent.
    int decl =
        tb.addSource(
            TraceNode.Op.STATIC_FIELD_DECL,
            java.util.List.of(
                new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true),
                new OperandRef.ConstInt(5L)),
            "static field declaration " + "testcasesupport.IO.STATIC_FINAL_FIVE = INT:5",
            "",
            38,
            -1,
            "bad():void");
    int chained =
        tb.addPropagate(
            TraceNode.Op.FIELDLOAD,
            java.util.List.of(
                new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true),
                new OperandRef.Var("t_t1", 0)),
            java.util.List.of(decl),
            "FIELDLOAD testcasesupport.IO.STATIC_FINAL_FIVE " + "-> t_t1",
            "",
            38,
            -1,
            "bad():void");
    // Suppress the unused-var warning for
    // `bind` ⇒?it's here to lock the
    // "class identity bind is also a real
    // trace node, even though the chained
    // load doesn't reach it" contract.
    assertThat(bind).isGreaterThanOrEqualTo(0);
    Trace trace = tb.build(java.util.List.of(), Trace.CompressionLevel.FULL);
    assertThat(trace.nodes()).as("3 nodes: bind + decl + chained").hasSize(3);
    TraceNode chainedNode = trace.nodes().get(2);
    assertThat(chainedNode.op()).isEqualTo(TraceNode.Op.FIELDLOAD);
    assertThat(chainedNode.parentIds())
        .as(
            "chained FIELDLOAD still resolves via "
                + "STATIC_FIELD_DECL ⇒?class identity "
                + "bind is not in the chain")
        .containsExactly(decl);
  }

  @Test
  void classIdentityBindDistinguishedFromStaticFieldDecl() {
    // P1.5 fidelity gate: the class identity
    // bind node (MARK_SOURCE) is
    // structurally distinguishable from a
    // STATIC_FIELD_DECL (also a SOURCE).
    // Both are SOURCE-role, both have empty
    // parentIds. The distinction is the op
    // enum (MARK_SOURCE vs STATIC_FIELD_DECL)
    // and the operands: MARK_SOURCE only
    // carries Var operands, while
    // STATIC_FIELD_DECL carries a
    // StaticFieldRef + a typed Const.
    TraceBuilder tb = new TraceBuilder();
    tb.addSource(
        TraceNode.Op.MARK_SOURCE,
        java.util.List.of(new OperandRef.Var("this", 0), new OperandRef.Var("IO", 0)),
        "class identity bind: IO",
        "",
        38,
        -1,
        "bad():void");
    tb.addSource(
        TraceNode.Op.STATIC_FIELD_DECL,
        java.util.List.of(
            new OperandRef.StaticFieldRef("testcasesupport.IO", "STATIC_FINAL_FIVE", true),
            new OperandRef.ConstInt(5L)),
        "static field declaration " + "testcasesupport.IO.STATIC_FINAL_FIVE = INT:5",
        "",
        38,
        -1,
        "bad():void");
    Trace trace = tb.build(java.util.List.of(), Trace.CompressionLevel.FULL);
    TraceNode bind = trace.nodes().get(0);
    TraceNode decl = trace.nodes().get(1);
    assertThat(bind.op())
        .as("bind = MARK_SOURCE (op difference)")
        .isEqualTo(TraceNode.Op.MARK_SOURCE);
    assertThat(decl.op())
        .as("decl = STATIC_FIELD_DECL (op difference)")
        .isEqualTo(TraceNode.Op.STATIC_FIELD_DECL);
    assertThat(bind.op()).isNotEqualTo(decl.op());
    // Both SOURCE-role, but the
    // operand shapes diverge.
    assertThat(bind.role()).isEqualTo(TraceNode.Role.SOURCE);
    assertThat(decl.role()).isEqualTo(TraceNode.Role.SOURCE);
    // The bind carries no StaticFieldRef
    // (proves it isn't erroneously
    // pretending to be a static field
    // declaration).
    assertThat(bind.operands())
        .as("class identity bind carries only Var " + "operands ⇒?never a StaticFieldRef")
        .noneMatch(o -> o instanceof OperandRef.StaticFieldRef);
    // The decl carries StaticFieldRef +
    // typed Const.
    assertThat(decl.operands().get(0)).isInstanceOf(OperandRef.StaticFieldRef.class);
    assertThat(decl.operands().get(1)).isInstanceOf(OperandRef.ConstInt.class);
  }
}
