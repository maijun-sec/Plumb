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

import org.plumb.tac.frontend.json.Json;

/**
 * The version handshake between a frontend and its sidecar.
 *
 * <h2>Why the handshake exists</h2>
 *
 * <p>The on-disk IR already has one: the serialiser writes a magic number and a version, and a
 * mismatch is refused loudly. The wire protocol had none, and the absence was invisible: an
 * instruction kind this build does not know became {@code HAVOC}, and an operator it does not know
 * became {@code ADD}. Neither raised a problem.
 *
 * <p>That is the dangerous direction. The engine looks the sink catalogue up on {@code CALL} (and
 * {@code UNINTERPRETED}) instructions only, so a call that was demoted to {@code HAVOC} is never
 * matched against a sink family. The finding is absent, and the scan still reports success.
 *
 * <p>With one sidecar that is a maintenance risk. With two -- and there are two, and there will be
 * more -- it is a source of findings that vanish without a trace, because the two halves drift at
 * different times.
 *
 * <p>So the protocol carries its version in every response, and a frontend that gets a version it
 * does not understand <b>refuses the sidecar</b> the way the cache refuses an incompatible IR.
 */
public final class SidecarProtocol {

  private SidecarProtocol() {}

  /**
   * The protocol version this build speaks.
   *
   * <p>Bump it when the request or response shape changes in a way an older frontend would
   * misinterpret. The rule is the same as the IR's: a change that an older frontend can read
   * correctly is <b>not</b> a version bump, because a field it does not know is ignored.
   */
  public static final int VERSION = 1;

  /**
   * The IR version this build speaks.
   *
   * <p>It is the serialiser's version, and it travels with the wire so that a sidecar built
   * against a different IR is refused rather than half-understood.
   */
  public static final int IR_VERSION = org.plumb.tac.io.IrSerializer.VERSION;

  /**
   * Whether a response is one this build can read.
   *
   * <p>A response that carries no version at all is <b>not</b> accepted. A sidecar older than the
   * handshake is exactly the case the handshake exists to catch, and treating a missing version
   * as "probably fine" would defeat it.
   *
   * @param response the sidecar's response
   * @return null when the response is compatible, or the reason it is not
   */
  public static String incompatibility(Json.JsonObject response) {
    if (response == null) {
      return "the sidecar sent no response";
    }
    Integer protocol = integerOf(response, "protocolVersion");
    if (protocol == null) {
      return "the sidecar did not report a protocol version, so it cannot be checked against this "
          + "build's version " + VERSION;
    }
    if (protocol != VERSION) {
      return "the sidecar speaks protocol version " + protocol + ", and this build speaks version "
          + VERSION;
    }
    Integer ir = integerOf(response, "irVersion");
    if (ir != null && ir != IR_VERSION) {
      return "the sidecar was built against IR version " + ir + ", and this build speaks version "
          + IR_VERSION;
    }
    return null;
  }

  /**
   * Put the version fields into a request.
   *
   * @param request the request
   * @return the request
   */
  public static Json.JsonObject stamp(Json.JsonObject request) {
    request.put("protocolVersion", VERSION);
    request.put("irVersion", IR_VERSION);
    return request;
  }

  private static Integer integerOf(Json.JsonObject response, String key) {
    Json.Value value = response.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Json.JsonNumber number) {
      try {
        return Integer.valueOf(number.text());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }
}
