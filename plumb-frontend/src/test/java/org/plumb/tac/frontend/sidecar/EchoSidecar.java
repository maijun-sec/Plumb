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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in sidecar that echoes back what it was asked.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A sidecar answers with its own words, and a test that drives a real one needs a toolchain that
 * may not be installed. This peer speaks the framing and answers with the <b>marker</b> it was
 * sent, so a caller can tell whether it got its own answer back or somebody else's.
 *
 * <p>That is the whole point: the framing carries no request id, so a pipe that carries two
 * conversations at once hands each of them the other's answer -- or hangs, when the interleaved
 * frames leave the peer waiting for bytes that never arrive. A peer that echoes a marker makes both
 * failures visible.
 *
 * <p>It reads the marker out of the request by hand rather than with the JSON library, so the child
 * process needs nothing on its classpath but this test's own classes.
 */
public final class EchoSidecar {

  /** The protocol version this peer speaks. */
  private static final int PROTOCOL_VERSION = 1;

  /** The IR version this peer was built against. */
  private static final int IR_VERSION = 1;

  private EchoSidecar() {}

  /**
   * Run the frame loop: one frame in, one frame out, until the pipe closes.
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
      String request = new String(payload, StandardCharsets.UTF_8);
      write(out, answer(markerOf(request)));
    }
  }

  /**
   * The marker in a request.
   *
   * @param request the request text
   * @return the marker, or an empty string when the request carries none
   */
  private static String markerOf(String request) {
    String key = "\"marker\":\"";
    int start = request.indexOf(key);
    if (start < 0) {
      return "";
    }
    start += key.length();
    int end = request.indexOf('"', start);
    return end < 0 ? "" : request.substring(start, end);
  }

  /** The answer to one request: the marker, unchanged. */
  private static String answer(String marker) {
    return "{\"protocolVersion\":"
        + PROTOCOL_VERSION
        + ",\"irVersion\":"
        + IR_VERSION
        + ",\"op\":\"echo\",\"ok\":true,\"echo\":\""
        + marker
        + "\"}";
  }

  /** Write one frame. */
  private static void write(DataOutputStream out, String payload) throws Exception {
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
    out.flush();
  }
}
