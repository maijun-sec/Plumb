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
package org.plumb.tac.frontend.arkts;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import org.plumb.tac.frontend.json.Json;

/**
 * A stand-in for the ArkTS sidecar: a peer on the other end of the wire, in Java.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The real sidecar needs Node and the ArkTS compiler itself, so a test that drives it is a test
 * that only runs where Node is installed. That is the wrong place to test the two halves of the
 * <b>protocol</b>: the framing, the handshake and the wire-to-IR mapping are the Java side's own
 * responsibility, and they must hold wherever the tests run.
 *
 * <p>So this program speaks the same framing and the same handshake and answers with a canned
 * function. The test then drives the <b>real</b> frontend against it.
 *
 * <p>What this cannot prove is that the real sidecar produces that shape. The Node-side smoke test
 * covers that, and the two together are what close the loop.
 */
public final class FakeArktsSidecar {

  /** The protocol version this fake speaks. */
  private static final int PROTOCOL_VERSION = 1;

  /** The IR version this fake was built against. */
  private static final int IR_VERSION = 1;

  private FakeArktsSidecar() {}

  /**
   * Run the sidecar loop: one frame in, one frame out, until the pipe closes.
   *
   * @param args ignored
   * @throws Exception when the stream breaks
   */
  public static void main(String[] args) throws Exception {
    DataInputStream in = new DataInputStream(System.in);
    DataOutputStream out = new DataOutputStream(System.out);
    while (true) {
      int length;
      try {
        length = in.readInt();
      } catch (EOFException ex) {
        return;
      }
      if (length <= 0) {
        return;
      }
      byte[] payload = new byte[length];
      in.readFully(payload);
      String text = new String(payload, StandardCharsets.UTF_8);
      Json.JsonObject request;
      try {
        request = Json.parseObject(text);
      } catch (RuntimeException ex) {
        write(out, failure("ping", "the request was not JSON"));
        continue;
      }
      write(out, handle(request));
    }
  }

  /** Answer one request. */
  private static Json.JsonObject handle(Json.JsonObject request) {
    String op = request.getString("op");
    // The handshake is symmetric: a request that names no version is refused.
    if (request.getInt("protocolVersion", -1) != PROTOCOL_VERSION) {
      return failure(
          op == null ? "ping" : op,
          "this sidecar speaks protocol version "
              + PROTOCOL_VERSION
              + ", and the request said "
              + request.getInt("protocolVersion", -1));
    }
    String operation = op == null ? "" : op;
    return switch (operation) {
      case "ping" -> success("ping", null, null);
      case "openProject" -> success("openProject", null, null);
      case "closeProject" -> success("closeProject", null, null);
      case "translate" -> success("translate", functions(), fileProblems());
      default -> failure(op, "unknown operation: " + op);
    };
  }

  /** A successful response. */
  private static Json.JsonObject success(
      String op, Json.JsonArray functions, Json.JsonArray problems) {
    Json.JsonObject response =
        new Json.JsonObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("irVersion", IR_VERSION)
            .put("op", op)
            .put("ok", true);
    if (functions != null) {
      response.put("functions", functions);
    }
    if (problems != null) {
      response.put("problems", problems);
    }
    return response;
  }

  /** A failed response. */
  private static Json.JsonObject failure(String op, String message) {
    return new Json.JsonObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("irVersion", IR_VERSION)
        .put("op", op)
        .put("ok", false)
        .put("error", message);
  }

  /**
   * The problems that belong to the FILE rather than to one function.
   *
   * <p>A file that did not parse is a partial file, and the partial file is still analysed -- so
   * the parse error is reported.
   */
  private static Json.JsonArray fileProblems() {
    Json.JsonArray problems = new Json.JsonArray();
    problems.add(
        new Json.JsonObject()
            .put("kind", "PARSE_PROBLEM")
            .put("message", "the file did not parse: ';' expected.")
            .put("nodeClass", "SourceFile")
            .put("line", -1)
            .put("column", -1));
    return problems;
  }

  /** The canned function the fake answers with. */
  private static Json.JsonArray functions() {
    Json.JsonArray functions = new Json.JsonArray();
    functions.add(function());
    return functions;
  }

  /**
   * One function: a struct's method.
   *
   * <p>The property read is a container access with a string key, because in this language an
   * object is a namespace and {@code this.message} and {@code this["message"]} are the same
   * location. The property WRITE is a store with the same key, because a write that is dropped is a
   * taint that stops halfway. The call keeps the receiver slot, because a call that put the value
   * at {@code args[0]} would look right and match nothing.
   */
  private static Json.JsonObject function() {
    Json.JsonObject function =
        new Json.JsonObject()
            .put("name", "render")
            .put("signature", "Page.render(string):string")
            .put("enclosingClass", "Page")
            .put("precision", "SOURCE_FULL")
            .put("synthetic", false);

    Json.JsonArray params = new Json.JsonArray();
    params.add(variable("input", "STRING", null, false));
    function.put("params", params);

    Json.JsonArray locals = new Json.JsonArray();
    locals.add(variable("this", "UNKNOWN", null, false));
    function.put("locals", locals);

    Json.JsonArray instructions = new Json.JsonArray();
    instructions.add(
        new Json.JsonObject()
            .put("kind", "ARRAYSTORE")
            .put("lhs1", variable("this", null, null, false))
            .put("lhs2", constant("STRING", "message"))
            .put("dest", variable("input", "STRING", null, false))
            .put("line", 12)
            .put("column", 5));
    instructions.add(
        new Json.JsonObject()
            .put("kind", "ARRAYLOAD")
            .put("dest", variable("t1", "STRING", null, true))
            .put("lhs1", variable("this", null, null, false))
            .put("lhs2", constant("STRING", "message"))
            .put("line", 13)
            .put("column", 12));
    Json.JsonArray callArgs = new Json.JsonArray();
    callArgs.add(constant("STRING", "Page"));
    callArgs.add(variable("t1", "STRING", null, true));
    instructions.add(
        new Json.JsonObject()
            .put("kind", "CALL")
            .put("calleeSig", "Page.Text/1")
            .put("dest", variable("t2", "UNKNOWN", null, true))
            .put("args", callArgs)
            .put("line", 13)
            .put("column", 5));
    function.put("instructions", instructions);

    Json.JsonArray problems = new Json.JsonArray();
    problems.add(
        new Json.JsonObject()
            .put("kind", "SOURCE_LOWERED")
            .put("message", "a break has no TAC lowering")
            .put("nodeClass", "BreakStatement")
            .put("line", 20)
            .put("column", 5));
    function.put("problems", problems);
    return function;
  }

  /** A variable operand. */
  private static Json.JsonObject variable(
      String name, String typeKind, String typeName, boolean temp) {
    Json.JsonObject operand = new Json.JsonObject().put("kind", "VAR").put("name", name);
    if (typeKind != null) {
      Json.JsonObject type = new Json.JsonObject().put("kind", typeKind);
      if (typeName != null) {
        type.put("name", typeName);
      }
      operand.put("type", type);
    }
    if (temp) {
      operand.put("isTemp", true);
    }
    return operand;
  }

  /** A constant operand. */
  private static Json.JsonObject constant(String kind, String value) {
    return new Json.JsonObject().put("kind", "CONST").put("constKind", kind).put("value", value);
  }

  /** Write one frame. */
  private static void write(DataOutputStream out, Json.JsonObject response) throws Exception {
    byte[] payload = Json.write(response).getBytes(StandardCharsets.UTF_8);
    out.writeInt(payload.length);
    out.write(payload);
    out.flush();
  }
}
