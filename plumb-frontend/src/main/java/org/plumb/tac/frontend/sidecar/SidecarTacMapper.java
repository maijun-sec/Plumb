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
import java.util.List;
import java.util.Map;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

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

    IrPrecision precision =
        "SOURCE_LOWERED".equals(wire.getString("precision"))
            ? IrPrecision.SOURCE_LOWERED
            : IrPrecision.SOURCE_FULL;

    return new TacFunction(
        wire.getString("name") == null ? "<unknown>" : wire.getString("name"),
        wire.getString("signature") == null ? "" : wire.getString("signature"),
        toType(wire.getObject("returnType")),
        params,
        locals,
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        wire.getString("enclosingClass"),
        org.plumb.tac.ir.SourceOrigin.SOURCE,
        List.of(),
        language,
        precision,
        wire.getBoolean("synthetic", false),
        Map.of(),
        ctx == null || path == null ? null : ctx.moduleOf(path));
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

    String binop = wire.getString("binop");
    if (binop != null) {
      try {
        builder.binop(org.plumb.tac.ir.BinaryOperator.valueOf(binop));
      } catch (IllegalArgumentException ex) {
        builder.binop(org.plumb.tac.ir.BinaryOperator.ADD);
      }
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
        if (operand != null) {
          list.add(operand);
        }
      }
      builder.args(list);
    }
    return builder.build();
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
    try {
      return switch (kindName) {
        case "INT" -> TacOperand.Const.ofInt(Integer.parseInt(value == null ? "0" : value));
        case "LONG" -> TacOperand.Const.ofLong(Long.parseLong(value == null ? "0" : value));
        case "FLOAT" -> TacOperand.Const.ofFloat(Float.parseFloat(value == null ? "0" : value));
        case "DOUBLE" -> TacOperand.Const.ofDouble(Double.parseDouble(value == null ? "0" : value));
        case "DECIMAL" -> TacOperand.Const.ofDecimal(value == null ? "0" : value);
        case "BOOL" -> TacOperand.Const.ofBool(Boolean.parseBoolean(value));
        case "CHAR" ->
            TacOperand.Const.ofChar(value == null || value.isEmpty() ? '\0' : value.charAt(0));
        case "NULL" -> TacOperand.Const.ofNull();
        default -> TacOperand.Const.ofString(value == null ? "" : value);
      };
    } catch (NumberFormatException ex) {
      // A constant the wire carried in a form this build cannot parse. The
      // value is unknown, not silently zero.
      return TacOperand.Const.ofString(value == null ? "" : value);
    }
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
    try {
      return switch (kindName) {
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
        default -> TacType.UNKNOWN;
      };
    } catch (IllegalArgumentException ex) {
      return TacType.UNKNOWN;
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
