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
package org.plumb.tac.frontend.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacGuard;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacLabelRef;
import org.plumb.tac.ir.TacMemberKind;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.TacValueDomain;

/**
 * The wire form of the TAC, mapped into the IR.
 *
 * <h2>Why this is one class and not one per sidecar</h2>
 *
 * <p>The sidecars all speak the same wire protocol -- the same instruction kinds, the same operand
 * shapes, the same type shapes -- and the mapping from one to the other is pure mechanics. A copy
 * per sidecar would be a copy that drifts: a new instruction kind would land in one mapping and not
 * the other, and the sidecar that missed it would quietly demote the instruction to {@code HAVOC}.
 *
 * <p>A demoted instruction is the dangerous kind of failure: the engine looks the sink catalogue up
 * on {@code CALL} and {@code UNINTERPRETED} instructions only, so a call that became a {@code
 * HAVOC} is never matched against a sink family. The finding is absent, and the scan still reports
 * success.
 *
 * <p>So the mapping lives here once, and every sidecar frontend uses it.
 *
 * <p>It is PUBLIC, and its helpers are public, because the Go and C# frontends
 * used to keep their own copy of this mapping. Two copies of one mapping
 * drift: the copies missed the drop channel, the member kind, the try regions,
 * the annotation channels and the instruction guard, and they read an unknown
 * binary operator as ADD -- which turns a comparison into an addition, and
 * says so about the PROGRAM rather than about the analysis. Those frontends
 * delegate here now, so there is one mapping and one set of rules.
 */
public final class SidecarTacMapper {

  private SidecarTacMapper() {}

  /**
   * Map one wire function into the IR.
   *
   * <p>The structured problems the sidecar reported for this function are appended to {@code
   * problems}. A construct that could not be modelled has to be reported, never absent.
   *
   * @param wire the wire function
   * @param path the file the function came from
   * @param ctx the compile context, or null
   * @param language the language the function was written in
   * @param problems the sink for the structured problems
   * @return the function
   */
  public static TacFunction toFunction(
      Json.JsonObject wire,
      Path path,
      CompileContext ctx,
      SourceLanguage language,
      List<TacProgram.FunctionProblem> problems) {
    List<TacInstruction> instructions = new ArrayList<>();
    Json.JsonArray wireInstructions = wire.getArray("instructions");
    if (wireInstructions != null) {
      for (int i = 0; i < wireInstructions.size(); i++) {
        Json.JsonObject instruction = wireInstructions.getObject(i);
        if (instruction != null) {
          instructions.add(toInstruction(instruction));
        }
      }
    }

    List<TacOperand.TacVar> params = new ArrayList<>();
    Json.JsonArray wireParams = wire.getArray("params");
    if (wireParams != null) {
      for (int i = 0; i < wireParams.size(); i++) {
        TacOperand.TacVar variable = toVar(wireParams.getObject(i));
        if (variable != null) {
          params.add(variable);
        }
      }
    }

    List<TacOperand.TacVar> locals = new ArrayList<>();
    Json.JsonArray wireLocals = wire.getArray("locals");
    if (wireLocals != null) {
      for (int i = 0; i < wireLocals.size(); i++) {
        TacOperand.TacVar variable = toVar(wireLocals.getObject(i));
        if (variable != null) {
          locals.add(variable);
        }
      }
    }

    Json.JsonArray wireProblems = wire.getArray("problems");
    if (wireProblems != null) {
      for (int i = 0; i < wireProblems.size(); i++) {
        Json.JsonObject problem = wireProblems.getObject(i);
        if (problem == null) {
          continue;
        }
        problems.add(
            new TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                problem.getString("kind") == null ? "UNHANDLED_NODE" : problem.getString("kind"),
                problem.getString("message") == null ? "" : problem.getString("message"),
                problem.getString("nodeClass"),
                problem.getInt("line", -1),
                problem.getInt("column", -1)));
      }
    }

    // The DROPS the sidecar recorded: the constructs it could not lower faithfully.
    // The function's precision is DERIVED from them -- a sidecar that claims
    // SOURCE_FULL while recording a SEMANTICS drop would be hiding a real loss.
    List<TacDrop> drops = toDrops(wire.getArray("drops"));
    IrPrecision precision = combinePrecision(wire.getString("precision"), drops);

    // The member kind: what this function IS. A getter is a read of a field and
    // a constructor is not a call anybody makes, so a checker has to be able to
    // tell them apart.
    String memberKindName = wire.getString("memberKind");
    TacMemberKind memberKind = memberKindName == null ? null : TacMemberKind.fromWire(memberKindName);

    return new TacFunction(
        wire.getString("name") == null ? "<unknown>" : wire.getString("name"),
        wire.getString("signature") == null ? "" : wire.getString("signature"),
        toType(wire.getObject("returnType")),
        params,
        locals,
        instructions,
        toStringList(wire.getArray("classAnnotations")),
        toStringList(wire.getArray("methodAnnotations")),
        toAnnotationMap(wire.getObject("fieldAnnotations")),
        toAnnotationMap(wire.getObject("paramAnnotations")),
        wire.getString("enclosingClass"),
        org.plumb.tac.ir.SourceOrigin.SOURCE,
        toTryRegions(wire.getArray("tryRegions")),
        language,
        precision,
        wire.getBoolean("synthetic", false),
        toAnnotationMap(wire.getObject("annotationValues")),
        ctx == null || path == null ? null : ctx.moduleOf(path),
        drops,
        memberKind);
  }

  /**
   * The annotations a member carries.
   *
   * <p>An annotation is the one place where the SOURCE states something about
   * the program that the program itself does not: {@code @Transactional} says
   * how a method must be called, and {@code @NotNull} says what a caller may
   * assume. A rule that can see them is a rule that does not have to guess.
   *
   * <p>The wire carries them as a list of names; the IR keeps them as names
   * too, because a name is what a rule matches on.
   */
  public static List<String> toStringList(Json.JsonArray wire) {
    if (wire == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (int i = 0; i < wire.size(); i++) {
      Json.Value item = wire.get(i);
      if (item instanceof Json.JsonString text) {
        String value = text.value();
        if (value != null && !value.isEmpty()) {
          out.add(value);
        }
      }
    }
    return out;
  }

  /**
   * A map of member name to the annotations it carries.
   *
   * <p>The order of the entries is kept: a rule that reports "the first
   * annotation that means X" must not see a different one from run to run.
   */
  public static Map<String, List<String>> toAnnotationMap(Json.JsonObject wire) {
    if (wire == null || wire.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String key : wire.keys()) {
      Json.JsonArray values = wire.getArray(key);
      if (values != null) {
        List<String> list = toStringList(values);
        if (!list.isEmpty()) {
          out.put(key, list);
          continue;
        }
      }
      // The annotation VALUES channel carries ONE value per name, not a list.
      // Reading it as a list only dropped the value: the map came back empty
      // and the annotation said nothing.
      String single = wire.getString(key);
      if (single != null && !single.isEmpty()) {
        out.put(key, List.of(single));
      }
    }
    return out;
  }

  /**
   * The exception regions of a function.
   *
   * <p>Without them the CFG has no edge from a throwing instruction to the
   * handler that catches it, and every rule about "what happens when this
   * call fails" silently stops applying.
   */
  public static List<TacFunction.TryRegion> toTryRegions(Json.JsonArray wire) {
    if (wire == null) {
      return List.of();
    }
    List<TacFunction.TryRegion> out = new ArrayList<>();
    for (int i = 0; i < wire.size(); i++) {
      Json.JsonObject region = wire.getObject(i);
      if (region == null) {
        continue;
      }
      out.add(
          new TacFunction.TryRegion(
              region.getInt("tryStart", -1),
              region.getInt("tryEnd", -1),
              region.getInt("catchStart", -1),
              region.getInt("catchEnd", -1),
              toStringList(region.getArray("catchLabels")),
              region.getString("finallyLabel"),
              region.getInt("finallyStart", -1),
              region.getInt("finallyEnd", -1)));
    }
    return out;
  }

  /**
   * Map one wire drop into the IR.
   *
   * <p>A drop is the machine-readable receipt of a construct that could not be lowered
   * faithfully. Its impact decides the function's precision: SEMANTICS and STRUCTURE lower it,
   * VALUE and TYPE do not.
   */
  public static TacDrop toDrop(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String construct = wire.getString("construct");
    if (construct == null || construct.isEmpty()) {
      return null;
    }
    TacDrop.Scope scope =
        "PROGRAM".equals(wire.getString("scope"))
            ? TacDrop.Scope.PROGRAM
            : TacDrop.Scope.FUNCTION;
    return new TacDrop(
        construct,
        wire.getString("detail") == null ? "" : wire.getString("detail"),
        impactOf(wire.getString("impact")),
        wire.getInt("line", -1),
        wire.getInt("column", -1),
        scope);
  }

  /**
   * The impact a wire drop carries.
   *
   * <p>An impact this build does not know is read as SEMANTICS: that is the impact that lowers
   * the precision, and reading an unknown impact as harmless would hide a real loss.
   */
  private static TacDrop.Impact impactOf(String name) {
    if (name == null) {
      return TacDrop.Impact.VALUE;
    }
    try {
      return TacDrop.Impact.valueOf(name);
    } catch (IllegalArgumentException ex) {
      return TacDrop.Impact.SEMANTICS;
    }
  }

  /** Map one wire instruction into the IR. */
  public static TacInstruction toInstruction(Json.JsonObject wire) {
    String kindName = wire.getString("kind");
    TacInstruction.Kind kind;
    try {
      kind = TacInstruction.Kind.valueOf(kindName == null ? "NOP" : kindName);
    } catch (IllegalArgumentException ex) {
      // An instruction kind this build does not know. The sidecar is newer
      // than the frontend. The safest reading of an unknown instruction is one
      // whose effect is unknown.
      kind = TacInstruction.Kind.HAVOC;
    }

    // The binary operator is resolved BEFORE the instruction is built, because
    // an operator this build does not know makes the whole instruction
    // unreadable: it used to be read as ADD, which silently turned a
    // comparison into an addition. "The value is unknown" is a claim about the
    // ANALYSIS, and that is the honest reading.
    String binop = wire.getString("binop");
    org.plumb.tac.ir.BinaryOperator binaryOperator = null;
    if (binop != null) {
      try {
        binaryOperator = org.plumb.tac.ir.BinaryOperator.valueOf(binop);
      } catch (IllegalArgumentException ex) {
        binaryOperator = null;
        kind = TacInstruction.Kind.HAVOC;
      }
    }

    TacInstruction.Builder builder = TacInstruction.builder(kind);
    builder.dest(toOperand(wire.getObject("dest")));
    builder.lhs1(toOperand(wire.getObject("lhs1")));
    builder.lhs2(toOperand(wire.getObject("lhs2")));
    builder.calleeSig(wire.getString("calleeSig"));
    builder.ctorType(toType(wire.getObject("ctorType")));
    builder.fieldName(wire.getString("fieldName"));
    builder.trueLabel(wire.getString("trueLabel"));
    builder.falseLabel(wire.getString("falseLabel"));
    builder.label(wire.getString("label"));
    builder.ownerType(wire.getString("ownerType"));
    builder.guard(toGuard(wire.getObject("guard")));
    builder.labelRef(toLabelRef(wire.getObject("labelRef")));
    builder.branchRegion(wire.getString("branchRegion"));
    builder.line(wire.getInt("line", -1));
    builder.column(wire.getInt("column", -1));

    String effectName = wire.getString("effect");
    if (effectName != null) {
      try {
        builder.effect(TacInstruction.Effect.valueOf(effectName));
      } catch (IllegalArgumentException ex) {
        // An effect this build does not know. The instruction keeps the
        // default: it runs here, in this order. That under-claims the
        // execution rather than over-claiming it.
      }
    }

    if (binaryOperator != null) {
      builder.binop(binaryOperator);
    }
    String unop = wire.getString("unop");
    if (unop != null) {
      try {
        builder.unop(org.plumb.tac.ir.UnaryOperator.valueOf(unop));
      } catch (IllegalArgumentException ex) {
        builder.unop(org.plumb.tac.ir.UnaryOperator.CAST);
      }
    }
    TacType castType = toType(wire.getObject("castType"));
    if (castType != null) {
      builder.castType(castType);
    }
    Json.JsonArray args = wire.getArray("args");
    if (args != null && args.size() > 0) {
      List<TacOperand> list = new ArrayList<>();
      for (int i = 0; i < args.size(); i++) {
        TacOperand operand = toOperand(args.getObject(i));
        if (operand == null) {
          // The POSITION of an argument is the meaning, not the operand.
          //
          // The engine reads every argument index against the call
          // convention -- the sink check adds ONE to the catalogue's index,
          // and the callee summary binds formal i to args[i+1] -- so an
          // argument that vanished would shift EVERY argument after it one
          // position to the left. The value at args[1] would then be read
          // as args[0], and the finding that names it would be reported
          // against the wrong argument -- or not reported at all.
          //
          // A constant takes the place of the operand that could not be
          // mapped. A constant carries no taint, so the analysis stays on the
          // conservative side: an argument we know nothing about is not
          // reported, rather than an argument we know nothing about being
          // reported at the wrong index.
          operand = TacOperand.Const.ofString("");
        }
        list.add(operand);
      }
      builder.args(list);
    }
    return builder.build();
  }

  /**
   * Map one wire guard into the IR.
   *
   * <p>A guard names the condition a branch tests. It is a statement the
   * PROGRAM made about its own values, so a rule may use it to drop a finding,
   * and may never use it to add one.
   */
  public static TacGuard toGuard(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String kindName = wire.getString("kind");
    if (kindName == null) {
      kindName = wire.getString("guardKind");
    }
    TacOperand subject = toOperand(wire.getObject("subject"));
    if (subject == null) {
      subject = toOperand(wire.getObject("lhs"));
    }
    if (subject == null) {
      // The sidecars write the operand under "operand".
      //
      // WHY THIS MATTERS. A guard is a RECORD of what the source said about a value, and
      // a record without its subject says nothing: the kind survived the trip, the value
      // the kind is about did not. Every truthiness guard therefore arrived as
      // "some value is true", and the engine could not use it to suppress a finding.
      subject = toOperand(wire.getObject("operand"));
    }
    if (kindName == null) {
      // No kind: if the wire gave a comparison, it is a comparison.
      if (wire.getObject("rhs") != null || wire.getObject("value") != null) {
        kindName = "COMPARE";
      } else {
        kindName = "TRUTHINESS";
      }
    }
    switch (kindName.toUpperCase(Locale.ROOT)) {
      case "COMPARE" -> {
        TacOperand rhs = toOperand(wire.getObject("rhs"));
        if (rhs == null) {
          rhs = toOperand(wire.getObject("value"));
        }
        org.plumb.tac.ir.BinaryOperator op = null;
        String opName = wire.getString("op");
        if (opName != null) {
          try {
            op = org.plumb.tac.ir.BinaryOperator.valueOf(opName);
          } catch (IllegalArgumentException ex) {
            op = null;
          }
        }
        if (op == null) {
          op = org.plumb.tac.ir.BinaryOperator.EQ;
        }
        return TacGuard.compare(subject, op, rhs);
      }
      case "PROPERTY_PRESENCE" -> {
        String property = wire.getString("property");
        if (property == null) {
          property = wire.getString("propertyName");
        }
        if (property == null) {
          // PHP writes the property's name under "key".
          property = wire.getString("key");
        }
        if (property == null) {
          // And the receiver under "receiver" -- a property-presence guard is about a
          // property OF something, so without the receiver it is about nothing.
          subject = toOperand(wire.getObject("receiver"));
        }
        return TacGuard.propertyPresence(subject, property);
      }
      default -> {
        return TacGuard.truthiness(subject);
      }
    }
  }

  /**
   * Map one wire label reference into the IR.
   *
   * <p>An unrecognized kind reads as {@link TacLabelRef.Kind#OTHER}: the label
   * still exists and is still named, we simply do not know what it is.
   */
  public static TacLabelRef toLabelRef(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String name = wire.getString("name");
    String kindName = wire.getString("kind");
    TacLabelRef.Kind kind = TacLabelRef.Kind.OTHER;
    if (kindName != null) {
      for (TacLabelRef.Kind candidate : TacLabelRef.Kind.values()) {
        if (candidate.name().equalsIgnoreCase(kindName)) {
          kind = candidate;
          break;
        }
      }
    }
    // The scope id is a NUMBER on the wire, and reading it as a string
    // found nothing. The name is absent as well for every STRUCTURAL kind
    // -- a loop head has no name of its own -- so the whole label
    // reference was dropped, and the engine never learned which label was
    // a loop head: the one fact a label reference exists to carry.
    String scopeId = wire.getString("scopeId");
    if (scopeId == null) {
      int numeric = wire.getInt("scopeId", -1);
      if (numeric >= 0) {
        scopeId = String.valueOf(numeric);
      }
    }
    if (name == null && scopeId == null && kind == TacLabelRef.Kind.OTHER) {
      return null;
    }
    return new TacLabelRef(name, kind, scopeId);
  }

  /** Map one wire operand into the IR. */
  public static TacOperand toOperand(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String kind = wire.getString("kind");
    if ("CONST".equals(kind)) {
      return toConst(wire);
    }
    return toVar(wire);
  }

  /** Map one wire variable into the IR. */
  public static TacOperand.TacVar toVar(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String name = wire.getString("name");
    if (name == null) {
      return null;
    }
    return new TacOperand.TacVar(
        name, toType(wire.getObject("type")), wire.getBoolean("isTemp", false));
  }

  /** Map one wire constant into the IR. */
  public static TacOperand toConst(Json.JsonObject wire) {
    String kindName = wire.getString("constKind");
    if (kindName == null) {
      kindName = "STRING";
    }
    String value = wire.getString("value");
    switch (kindName) {
      case "INT" -> {
        Long parsed = parseIntegerLiteral(value);
        return parsed == null
            ? TacOperand.Const.ofString(value == null ? "" : value)
            : TacOperand.Const.ofInt(parsed.intValue());
      }
      case "LONG" -> {
        Long parsed = parseIntegerLiteral(value);
        return parsed == null
            ? TacOperand.Const.ofString(value == null ? "" : value)
            : TacOperand.Const.ofLong(parsed);
      }
      case "FLOAT" -> {
        Double parsed = parseDoubleLiteral(value);
        return parsed == null
            ? TacOperand.Const.ofString(value == null ? "" : value)
            : TacOperand.Const.ofFloat(parsed.floatValue());
      }
      case "DOUBLE" -> {
        Double parsed = parseDoubleLiteral(value);
        return parsed == null
            ? TacOperand.Const.ofString(value == null ? "" : value)
            : TacOperand.Const.ofDouble(parsed);
      }
      case "DECIMAL" -> {
        return TacOperand.Const.ofDecimal(value == null ? "0" : value);
      }
      case "BOOL" -> {
        return TacOperand.Const.ofBool(Boolean.parseBoolean(value));
      }
      case "CHAR" -> {
        return TacOperand.Const.ofChar(value == null || value.isEmpty() ? '\0' : value.charAt(0));
      }
      case "NULL" -> {
        return TacOperand.Const.ofNull();
      }
      // A regular expression literal is not a string literal, but keeping
      // its text is strictly more informative than dropping it, and the
      // engine has no separate notion of a pattern.
      case "REGEX" -> {
        return TacOperand.Const.ofString(value == null ? "" : value);
      }
      default -> {
        return TacOperand.Const.ofString(value == null ? "" : value);
      }
    }
  }

  /**
   * Parse an integer literal the way a source file writes one.
   *
   * <p>The side cars carry the literal's own text, and that text is not always a decimal number:
   * {@code 0xFF}, {@code 0o17}, {@code 0b1010} and {@code 1_000} are all integers, and every
   * language in the tree writes at least one of them.
   *
   * <p>{@link Integer#parseInt} reads a decimal number and nothing else, so a hex literal used to
   * arrive here, fail, and be carried on as a STRING. The constant changed its TYPE without
   * anybody being told, and every comparison against it was comparing a string.
   *
   * @param text the literal as it was written
   * @return the value, or {@code null} when the text is not an integer literal
   */
  private static Long parseIntegerLiteral(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    String digits = text.replace("_", "");
    long sign = 1;
    if (digits.startsWith("-")) {
      sign = -1;
      digits = digits.substring(1);
    } else if (digits.startsWith("+")) {
      digits = digits.substring(1);
    }
    int radix = 10;
    if (digits.length() > 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
      radix = 16;
      digits = digits.substring(2);
    } else if (digits.length() > 2 && (digits.startsWith("0o") || digits.startsWith("0O"))) {
      radix = 8;
      digits = digits.substring(2);
    } else if (digits.length() > 2 && (digits.startsWith("0b") || digits.startsWith("0B"))) {
      radix = 2;
      digits = digits.substring(2);
    }
    if (digits.isEmpty()) {
      return null;
    }
    try {
      return sign * Long.parseLong(digits, radix);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Parse a floating-point literal the way a source file writes one.
   *
   * <p>The decimal form is {@link Double#parseDouble}'s. The other forms are the ones an integer
   * literal may carry -- a prefix and a separator -- and they are read as integers, because that
   * is what they are.
   *
   * @param text the literal as it was written
   * @return the value, or {@code null} when the text is not a number
   */
  private static Double parseDoubleLiteral(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException ex) {
      // Not a decimal float; the integer forms are tried below.
    }
    Long asInteger = parseIntegerLiteral(text);
    return asInteger == null ? null : asInteger.doubleValue();
  }

  /**
   * The drops a wire function carries.
   *
   * <p>A drop is the machine-readable receipt of a loss. The engine derives
   * the function's precision from it, so a front end that drops it loses the
   * only record that the function was not fully understood.
   */
  public static List<TacDrop> toDrops(Json.JsonArray wire) {
    List<TacDrop> drops = new ArrayList<>();
    if (wire == null) {
      return drops;
    }
    for (int i = 0; i < wire.size(); i++) {
      TacDrop drop = toDrop(wire.getObject(i));
      if (drop != null) {
        drops.add(drop);
      }
    }
    return drops;
  }

  /**
   * The PROGRAM-LEVEL drops of a sidecar response.
   *
   * <p><b>Why this exists.</b> The wire protocol carries the program-level losses in the response's
   * TOP-LEVEL array, beside the functions rather than inside one of them: they belong to the whole
   * program, so no function owns them. The function-level channel is a different array -- inside each
   * function's own object -- and the two must not be mixed: a function-level drop lowers that
   * function's precision, while a program-level one lowers no function at all.
   *
   * <p>The protocol names that array two ways -- {@code programDrops} in the wire document,
   * {@code drops} in the task statement -- and the Go sidecar sends the SAME list under both names.
   * So the reader takes {@code programDrops} when it is there and falls back to {@code drops}, and
   * never reads both: reading both would count every program drop twice.
   *
   * <p>Whatever the wire says about the scope, a drop that arrives in this channel is a program-level
   * drop. A sidecar that left the scope field empty must not make the loss look like a function's.
   *
   * @param response the sidecar's response object
   * @return the program-level drops; empty when the response carries none
   */
  public static List<TacDrop> toProgramDrops(Json.JsonObject response) {
    if (response == null) {
      return List.of();
    }
    Json.JsonArray wire = response.getArray("programDrops");
    if (wire == null) {
      wire = response.getArray("drops");
    }
    if (wire == null) {
      return List.of();
    }
    List<TacDrop> drops = new ArrayList<>();
    for (int i = 0; i < wire.size(); i++) {
      TacDrop drop = toDrop(wire.getObject(i));
      if (drop != null) {
        drops.add(drop.withScope(TacDrop.Scope.PROGRAM));
      }
    }
    return drops;
  }

  /**
   * The precision a wire function's drops imply.
   *
   * <p>The sidecar's own claim and the drops are COMBINED, and the worse of
   * the two wins. A sidecar that claims SOURCE_FULL while recording a
   * SEMANTICS drop would be hiding a real loss, and the combination is what
   * stops it.
   *
   * @param statedName the precision the sidecar stated, or {@code null} when it
   *     stated none
   * @param drops the drops the sidecar recorded
   * @return the precision the function actually has
   */
  public static IrPrecision combinePrecision(String statedName, List<TacDrop> drops) {
    IrPrecision stated = IrPrecision.SOURCE_FULL;
    if (statedName != null) {
      try {
        stated = IrPrecision.valueOf(statedName);
      } catch (IllegalArgumentException ex) {
        // A precision this build does not know. The safest reading of an unknown
        // precision is the one that loses the most: claiming full precision would
        // hide the loss.
        stated = IrPrecision.SOURCE_DEGRADED;
      }
    }
    return TacFunction.combine(stated, drops);
  }

  /** Map one wire type into the IR. */
  public static TacType toType(Json.JsonObject wire) {
    if (wire == null) {
      return TacType.UNKNOWN;
    }
    String kindName = wire.getString("kind");
    if (kindName == null) {
      return TacType.UNKNOWN;
    }
    TacType base;
    try {
      base =
          switch (kindName) {
            case "BOOLEAN" -> TacType.BOOLEAN;
            case "BYTE" -> TacType.BYTE;
            case "SHORT" -> TacType.SHORT;
            case "CHAR" -> TacType.CHAR;
            case "INT" -> TacType.INT;
            case "LONG" -> TacType.LONG;
            case "FLOAT" -> TacType.FLOAT;
            case "DOUBLE" -> TacType.DOUBLE;
            case "DECIMAL" -> TacType.DECIMAL;
            case "STRING" -> TacType.STRING;
            case "NULL" -> TacType.NULL;
            case "VOID" -> TacType.VOID;
            case "CLASS" ->
                TacType.ofClass(wire.getString("name") == null ? "" : wire.getString("name"));
            case "ARRAY" -> TacType.arrayOf(toType(wire.getObject("component")));
            // An arbitrary-precision integer has no width, so the IR has no
            // kind for it. UNKNOWN plus an unbounded domain says exactly what
            // is true: "a number of unknown width, and not bounded".
            case "BIGINT" -> TacType.UNKNOWN.withDomain(TacValueDomain.unbounded());
            default -> TacType.UNKNOWN;
          };
    } catch (IllegalArgumentException ex) {
      return TacType.UNKNOWN;
    }

    // The union members. A union is kept as the SET of types it is made of:
    // splicing the member names into one string produces a type name that
    // matches nothing in the program.
    Json.JsonArray members = wire.getArray("unionMembers");
    if (members != null && members.size() > 0) {
      List<TacType> union = new ArrayList<>();
      for (int i = 0; i < members.size(); i++) {
        union.add(toType(members.getObject(i)));
      }
      base = base.withUnionMembers(union);
    }

    // Nullability. Only a reference can be null, so the flag is read for
    // references only -- otherwise a primitive would stop being equal to the
    // IR's own constant for it.
    Json.Value nullableValue = wire.get("nullable");
    if (nullableValue != null && base.isReference()) {
      base = wire.getBoolean("nullable", true) ? base.nullable() : base.nonNull();
    }

    // The value domain: what the solver may assume about the numbers.
    TacValueDomain domain = toDomain(wire.getObject("domain"));
    if (domain != null) {
      base = base.withDomain(domain);
    }
    return base;
  }

  /**
   * Map one wire value domain into the IR.
   *
   * <p>The domain is a statement about what the SOLVER may assume, never a
   * licence to assume more than it says: an integral or unbounded domain is
   * exact under the engine's integer model, a floating-point one is not.
   *
   * <p>Three spellings of the discriminator are accepted ({@code domain},
   * {@code kind}, and the case-insensitive form of both) because the sidecars
   * were written against the contract at different times.
   */
  public static TacValueDomain toDomain(Json.JsonObject wire) {
    if (wire == null) {
      return null;
    }
    String name = wire.getString("domain");
    if (name == null) {
      name = wire.getString("kind");
    }
    if (name == null) {
      return null;
    }
    int bits = wire.getInt("bits", -1);
    int exponentBits = wire.getInt("exponentBits", -1);
    int scale = wire.getInt("scale", -1);
    switch (name.toUpperCase(Locale.ROOT)) {
      case "INTEGRAL" -> {
        return TacValueDomain.integral(bits < 0 ? 32 : bits);
      }
      case "FLOATING" -> {
        // The wire gives the SIGNIFICAND width in `bits` and the exponent
        // width separately; the IR takes the exponent width first.
        return TacValueDomain.floating(
            exponentBits < 0 ? 11 : exponentBits, bits < 0 ? 53 : bits);
      }
      case "FIXED_POINT" -> {
        return TacValueDomain.fixedPoint(bits < 0 ? 128 : bits, scale < 0 ? 0 : scale);
      }
      case "UNBOUNDED" -> {
        return TacValueDomain.unbounded();
      }
      case "DYNAMIC" -> {
        return TacValueDomain.dynamic();
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * A file's path, as the key of the open project and as the name on the wire.
   *
   * @param path the path
   * @return the key
   */
  public static String pathKey(Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  /**
   * A file's text, or null when it cannot be read.
   *
   * @param file the file
   * @return the text, or null
   */
  public static String readTextQuietly(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }
}
