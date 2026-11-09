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
package org.plumb.dfa.taint;

import java.util.Locale;

/**
 * The catalogue of mutating calls whose receiver accumulates the taint of their arguments, and of
 * the calls that read such an accumulated state back out.
 *
 * <p><b>Why this exists.</b> A taint analysis that only tracks "value flows into result" misses the
 * classic accumulator shape:
 *
 * <pre>
 *   StringBuilder sb = new StringBuilder();
 *   sb.append(userInput);          // the tainted value is now INSIDE sb
 *   Runtime.getRuntime().exec(sb.toString());
 * </pre>
 *
 * <p>The taint does not travel from {@code userInput} to {@code sb.toString()} along an expression
 * edge: it travels through the <em>state</em> of {@code sb}. {@code append} returns the builder
 * itself, so the call result carries the taint, but the builder object stays clean, and the later
 * {@code toString()} sees a clean receiver and produces a clean result. The analysis then reports a
 * clean program for code that hands attacker input to the operating system.
 *
 * <p>The shape is general, not StringBuilder-specific: {@code list.add(x)}, {@code map.put(k, x)},
 * {@code out.write(x)} and {@code writer.append(x)} all accumulate their argument into the
 * receiver's state, and every later read of that state carries the taint.
 *
 * <p><b>Go has the same shapes, and they are modelled here too.</b> A Go map write, a channel send
 * and a slice append put a value into a container, and a Go builder or buffer accumulates what is
 * written into it. The Go front end lowers the three statement forms to calls with a synthetic
 * owner, so they arrive here as ordinary callee signatures.
 *
 * <p><b>Why the Go shapes are NOT in receiver-passthrough.yaml.</b> That file models the OTHER
 * direction -- a tainted receiver handing its state back to the result -- and it does so as a
 * fallback for callees the engine has no summary for. A Go map write is the opposite: the VALUE
 * taints the CONTAINER. That is what the mutator direction is for, and putting it in the
 * passthrough file would both invert the direction and leave the read side uncovered.
 *
 * <p>Two directions are therefore modelled:
 *
 * <ul>
 *   <li><b>mutator</b> -- a call that writes a tainted argument into the receiver's state taints
 *       the receiver. The receiver is then tainted for the rest of the function.
 *   <li><b>accumulator read</b> -- a call that returns the receiver's accumulated state propagates
 *       the receiver's taint to the result.
 * </ul>
 *
 * <p>Both directions are deliberately conservative in the same direction: they can only ever
 * <em>add</em> taint. A mutator never clears the receiver's taint, and an accumulator read never
 * removes it. That keeps the model free of false negatives without inventing false positives from a
 * clean write.
 *
 * <p><b>Scope.</b> The catalogue is keyed by the fully-qualified callee signature in the TAC's
 * {@code owner.method/arity} form. The receiver of an instance call is {@code args[0]}; the
 * arguments follow at {@code args[1]}.
 */
public final class AccumulatorCatalog {

  /**
   * The owner of the calls the Go front end synthesises.
   *
   * <p>A Go map write, a channel send and a slice append are not calls to anything the user wrote:
   * the front end lowers them to a call with a synthetic owner so that they can be told apart from
   * a real package. The owner is the same string the front end emits, so the catalogue and the
   * front end cannot drift apart without the tests noticing.
   */
  private static final String GO_SYNTHETIC_OWNER = "<go>";

  /**
   * The Go types that accumulate what is written into them.
   *
   * <p>They are named by their full path because that is how the front end names them: the owner of
   * {@code strings.Builder.WriteString} is {@code strings.builder}, the package path plus the type.
   */
  private static final java.util.List<String> GO_ACCUMULATOR_OWNERS =
      java.util.List.of("strings.builder", "bytes.buffer", "bufio.writer");

  private AccumulatorCatalog() {}

  /**
   * The declaring type of a callee, lower-cased.
   *
   * <p><b>Why the owner is extracted rather than matched as a substring.</b> The catalogue used to
   * ask whether the whole signature "contains" a dot-prefixed package fragment, such as {@code
   * ".java.util.list."}. That test is wrong for every type that sits directly under a root package:
   * the signature of {@code java.util.List.add/1} has no dot before {@code java}, so the fragment
   * {@code ".java.util.list."} is not in it and the check fails. The bug was invisible for {@code
   * java.lang.StringBuilder} -- there the fragment {@code ".stringbuilder."} does occur, because a
   * dot precedes {@code java} in {@code ".lang."} -- so the builder worked and the containers never
   * did. A rule whose right answer depends on how deep the type's package happens to be is not a
   * rule; extracting the owner and matching it as a whole is.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return the lower-cased owner, or an empty string when the signature has no owner
   */
  private static String ownerOf(String calleeSig) {
    int slash = calleeSig.indexOf('/');
    if (slash >= 0) calleeSig = calleeSig.substring(0, slash);
    int dot = calleeSig.lastIndexOf('.');
    if (dot < 0) return calleeSig.toLowerCase(Locale.ROOT);
    return calleeSig.substring(0, dot).toLowerCase(Locale.ROOT);
  }

  /**
   * The method name of a callee, lower-cased.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return the lower-cased method name, or an empty string when the signature has no method
   */
  private static String methodOf(String calleeSig) {
    int slash = calleeSig.indexOf('/');
    if (slash >= 0) calleeSig = calleeSig.substring(0, slash);
    int dot = calleeSig.lastIndexOf('.');
    if (dot < 0) return "";
    return calleeSig.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * Is the owner one of the given types, compared by its trailing package segments?
   *
   * <p>Comparing the trailing segments is what lets one rule cover a family: {@code
   * java.util.List}, {@code kotlin.collections.MutableList} and {@code
   * System.Collections.Generic.List} all end in {@code list}. Comparing the whole owner would need
   * one entry per spelling.
   *
   * <p><b>Why the match is a SUFFIX and not an equality.</b> A type's implementation is in the same
   * family as the interface it implements: {@code ArrayList} is a list, {@code HashSet} is a set,
   * {@code LinkedHashMap} is a map. An equality test would match {@code java.util.List} and miss
   * {@code java.util.ArrayList} -- and the IMPLEMENTATION is what a call site's receiver usually
   * is, because the receiver's declared type is the implementation when the value was created by a
   * constructor.
   *
   * <p>A suffix test is the right shape here because the family names are distinctive enough that a
   * false positive would need a type whose name ends in {@code list} and is not a list -- a
   * hypothetical called {@code MyPlaylist} would match, and that is the price of covering the
   * implementations. The alternative -- an equality test -- misses every implementation, which is
   * the more common case.
   *
   * @param owner the lower-cased owner
   * @param typeNames the lower-cased type names to accept
   * @return true when the owner's last segment ends in one of the given names
   */
  private static boolean ownerIs(String owner, String... typeNames) {
    int dot = owner.lastIndexOf('.');
    String simple = dot < 0 ? owner : owner.substring(dot + 1);
    for (String t : typeNames) {
      if (simple.equals(t) || simple.endsWith(t)) return true;
    }
    return false;
  }

  /**
   * Is this call one that writes its arguments into the receiver's own state?
   *
   * <p>The match is on the receiver's declaring type plus the method name, because {@code append}
   * means different things on different types: on a {@code StringBuilder} it mutates, on an
   * immutable {@code String} it returns a new value. Matching the type keeps the two apart.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return true when a tainted argument taints the receiver
   */
  public static boolean isMutator(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    String owner = ownerOf(calleeSig);
    String method = methodOf(calleeSig);
    if (method.isEmpty()) return false;
    // StringBuilder / StringBuffer: the canonical accumulator.
    if (ownerIs(owner, "stringbuilder", "stringbuffer")) {
      return method.equals("append")
          || method.equals("insert")
          || method.equals("appendcodepoint")
          || method.equals("replace")
          || method.equals("reverse")
          || method.equals("setcharat");
    }
    // Collections and maps: an element added to a container is part of the container's state.
    if (ownerIs(owner, "list", "set", "collection", "queue", "deque")) {
      return method.equals("add")
          || method.equals("addall")
          || method.equals("set")
          || method.equals("offer")
          || method.equals("push");
    }
    if (ownerIs(owner, "map")) {
      return method.equals("put") || method.equals("putall");
    }
    // Character / byte streams and writers: written bytes are part of the stream's state.
    if (ownerIs(
        owner,
        "outputstream",
        "writer",
        "printwriter",
        "stringwriter",
        "bufferedwriter",
        "filteroutputstream",
        "objectoutputstream",
        "bytearrayoutputstream",
        "chararraywriter")) {
      return method.equals("write")
          || method.equals("writebytes")
          || method.equals("print")
          || method.equals("println")
          || method.equals("append")
          || method.equals("writeint");
    }
    // Go: the runtime operations the front end models, and Go's own
    // accumulating types.
    //
    // A Go map write, a channel send and a slice append all put a value
    // INTO a container, so the container's state -- and every later read
    // of that state -- carries the value. A Go builder and a Go buffer
    // do the same thing with a method call.
    if (GO_SYNTHETIC_OWNER.equals(owner)) {
      return method.equals("mapstore")
          || method.equals("chansend")
          || method.equals("append")
          || method.equals("copy");
    }
    if (GO_ACCUMULATOR_OWNERS.contains(owner)) {
      return method.equals("write")
          || method.equals("writestring")
          || method.equals("writebyte")
          || method.equals("writebytes")
          || method.equals("writerune");
    }
    return false;
  }

  /**
   * Is this call one that returns the receiver's accumulated state?
   *
   * <p>The counterpart of {@link #isMutator(String)}: the value it returns is derived from what was
   * written into the receiver earlier.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return true when the result carries the receiver's accumulated taint
   */
  public static boolean isAccumulatorRead(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    String owner = ownerOf(calleeSig);
    String method = methodOf(calleeSig);
    if (method.isEmpty()) return false;
    if (ownerIs(owner, "stringbuilder", "stringbuffer")) {
      return method.equals("tostring");
    }
    if (ownerIs(owner, "stringwriter")) {
      return method.equals("tostring") || method.equals("getbuffer");
    }
    if (ownerIs(owner, "bytearrayoutputstream", "chararraywriter", "stringwriter")) {
      return method.equals("tostring");
    }
    // Containers: an element read back out of a collection is part of the
    // collection's state, exactly as an element written into it is. The
    // counterpart of the `add` / `put` branch in isMutator: without it the
    // mutator direction taints the container and the read direction drops
    // the taint on the floor, so a list that collects attacker input and
    // reads it back reads clean.
    //
    // The whole container's taint flows to the read result, not just the
    // requested element's: the analysis tracks a per-variable taint label,
    // not a per-element one, so it cannot tell element 0 from element 1.
    // That makes the read conservative -- it can report a taint that was in
    // a different element -- which is the safe direction: a missed finding
    // is worse than an extra one.
    if (ownerIs(owner, "list", "set", "collection", "queue", "deque", "map")) {
      return method.equals("get") || method.equals("remove") || method.equals("poll");
    }
    // Go: the reads that hand a container's contents back out.
    //
    // A map read, a channel receive and the key/value of a range over a
    // map all return what the container holds, so they carry the
    // container's taint to the result. The range is a chain: the map
    // taints the iterator, and the iterator taints the key and the
    // value, so all three links are listed.
    if (GO_SYNTHETIC_OWNER.equals(owner)) {
      return method.equals("mapindex")
          || method.equals("chanrecv")
          || method.equals("mapiter")
          || method.equals("mapkey")
          || method.equals("mapvalue");
    }
    if (GO_ACCUMULATOR_OWNERS.contains(owner)) {
      return method.equals("string") || method.equals("bytes");
    }
    return false;
  }

  /**
   * The argument indices of a mutator call whose taint flows into the receiver.
   *
   * <p>The receiver itself is {@code args[0]} on an instance call; the value arguments follow. The
   * receiver is deliberately NOT included: a builder that already holds tainted content stays
   * tainted on its own, and listing the receiver would turn every append into a self-referential
   * label.
   *
   * @param ins the CALL instruction
   * @return the argument indices to inspect
   */
  public static java.util.List<Integer> mutatorArgIndices(org.plumb.tac.ir.TacInstruction ins) {
    java.util.List<Integer> out = new java.util.ArrayList<>();
    if (ins == null || ins.args == null) return out;
    // A Go map write is `m[key] = value`: the KEY indexes the map and the VALUE is what the map
    // holds. Only the value is part of the map's state, so only the value taints the map. A tainted
    // KEY would make every later read of the map look tainted -- including the read of a different
    // key, whose value was never written from tainted data. The other mutators take their values
    // from the first argument onwards, which is what the loop below already does.
    if (isGoMapStore(ins.calleeSig)) {
      out.add(2);
      return out;
    }
    for (int i = 1; i < ins.args.size(); i++) {
      out.add(i);
    }
    return out;
  }

  /**
   * Whether the call is a Go map write.
   *
   * <p>The synthetic signature the Go front end emits for {@code m[k] = v} carries the map at
   * {@code args[0]}, the key at {@code args[1]} and the value at {@code args[2]}.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return true when the call writes into a Go map
   */
  private static boolean isGoMapStore(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    return GO_SYNTHETIC_OWNER.equals(ownerOf(calleeSig)) && "mapstore".equals(methodOf(calleeSig));
  }

  /**
   * Is this call a string builder's {@code append}?
   *
   * <p><b>Why this is a separate question from {@link #isMutator(String)}.</b> A builder's {@code
   * append} does two things: it writes into the receiver's state (the mutator direction), and it
   * RETURNS the receiver, so the call's result carries whatever the builder already held. The
   * second half is what makes {@code sb.append(x).toString()} work, and it is a property of the
   * RESULT rather than of the receiver.
   *
   * <p><b>Why the match is on the NAME and not on the package.</b> The builder is spelled
   * differently in every language: {@code java.lang.StringBuilder}, {@code
   * kotlin.text.StringBuilder} (an alias of the Java one), {@code System.Text.StringBuilder}. The
   * package is exactly what differs; the type's name is what they agree on. Matching on a
   * capitalised {@code .StringBuilder.} finds the Java spelling and misses the other two, and the
   * symptom is a chain that loses its taint half-way -- tainted in Java, clean in C#.
   *
   * <p>So the owner is matched case-insensitively on the type name, the same way {@link
   * #isMutator(String)} matches it. Two rules that disagree would taint one path and not the other.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return true when the call appends to a string builder
   */
  public static boolean isStringBuilderAppend(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    if (!"append".equals(methodOf(calleeSig))) return false;
    return ownerIs(ownerOf(calleeSig), "stringbuilder", "stringbuffer");
  }
}
