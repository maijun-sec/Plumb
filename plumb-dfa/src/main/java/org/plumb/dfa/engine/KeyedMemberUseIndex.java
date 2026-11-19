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
package org.plumb.dfa.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.taint.FieldOpClassifier;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The program-level view of a NAMESPACE member: was it assigned, and was it ever read?
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The unused-private-field rule asks two questions about the WHOLE program: "is this member
 * assigned?" and "is this member ever read?". In a language whose values live in FIELDS, both
 * questions have their own instruction ({@code FIELDSTORE} / {@code FIELDLOAD}) and their own
 * tracker ({@link FieldDefUseTracker}).
 *
 * <p>In a language whose objects are NAMESPACES, neither exists. A member is an entry of a
 * namespace, so its declaration is a keyed store and its read is a keyed load -- and those are the
 * same two instructions an ordinary property access uses. A rule that watches field writes sees
 * nothing at all there, which is why the rule used to report every member of a keyed language as
 * "assigned but never read" (it saw the FIELDSTORE the front end used to emit) and then, once the
 * front end stopped emitting it, reported nothing (the rule had nothing to watch).
 *
 * <h2>What the three facts are</h2>
 *
 * <ul>
 *   <li><b>The declaration</b> -- "this class declares a member called {@code m}, and it is
 *       private" -- comes from the TAC's own member annotations ({@link
 *       TacFunction#fieldAnnotations()}). It is a declaration fact, not an inference from how the
 *       value is written: a keyed language has no fields, so the way a value is stored says nothing
 *       about what was declared.
 *   <li><b>The assignment</b> is a keyed store ({@code ARRAYSTORE}) whose key is a literal name.
 *   <li><b>The read</b> is a keyed load ({@code ARRAYLOAD}) whose key is a literal name.
 * </ul>
 *
 * <h2>How a member is told apart from another one</h2>
 *
 * <p>A member belongs to a CLASS, and the class is what the access is made on: {@code this.m} is a
 * member of the class the code is written in, {@code other.m} is a member of {@code other}'s class,
 * and {@code Shapes.count} is a member of the class {@code Shapes}. The receiver carries that fact
 * twice -- as its type, and as its name for a static access -- so the class is known without asking
 * the front end for anything new.
 *
 * <p>When the receiver says neither, the access is NOT attributed to a class. It is then a read of
 * that NAME somewhere in the program, and it suppresses every class's member of that name: the
 * alternative -- assuming it was somebody's member -- is how a member that IS read gets reported as
 * unread.
 *
 * <h2>What this class refuses to conclude</h2>
 *
 * <p>A computed access -- {@code this[name]} -- has no name to look for, so ANY member of the
 * receiver could be the one that was accessed. The members of that class are therefore not
 * reported. When even the receiver's class is unknown, the access could be any object's, and
 * nothing is reported at all.
 *
 * <p>The bias is deliberate and it is the same one the field tracker has: this rule's value is in
 * the members it finds, and a member that IS read but reported as unread is the failure a reader
 * cannot forgive. Silence is recoverable; a false accusation is not.
 */
public final class KeyedMemberUseIndex {

  /** The annotation that marks a member as private. */
  private static final String PRIVATE = "private";

  private KeyedMemberUseIndex() {}

  /**
   * A private member of a namespace that is assigned and never read.
   *
   * @param ownerClass the class that declares the member
   * @param memberName the member's name
   * @param owner the function the finding belongs to: the one that holds the assignment
   * @param line the line of the assignment, or -1 when the front end did not say
   * @param column the column of the assignment, or -1 when the front end did not say
   */
  public record UnusedMember(
      String ownerClass, String memberName, TacFunction owner, int line, int column) {}

  /**
   * The private members of the program that are assigned and never read.
   *
   * @param program the whole program, merged across the translation units
   * @return the members, in the order the program declares them
   */
  public static List<UnusedMember> unusedPrivateMembers(TacProgram program) {
    if (program == null) {
      return List.of();
    }
    // The classes the program declares: the qualified name of each class, and the SIMPLE
    // name it is also known by.
    //
    // The qualified name is the class's IDENTITY. Two classes that share a simple name --
    // "Holder" in two modules -- are two classes, and the members they declare are two
    // members. The simple name is only a SPELLING, and it is what a receiver's type says.
    // The simple name -> every class that goes by it.
    Map<String, List<String>> classesBySimpleName = new LinkedHashMap<>();
    // The declarations: class -> the private members it declares.
    Map<String, Set<String>> declaredPrivate = new LinkedHashMap<>();

    // ---- the first pass: what classes are there, and what do they declare ---------------
    for (TacFunction fn : program.functions()) {
      // A language whose values live in fields has its own tracker and its own path. This
      // index is about namespaces, and the two must not both answer for the same program.
      if (!fn.sourceLanguage().hasKeyedContainers()) {
        continue;
      }
      String enclosing = fn.enclosingClass();
      if (enclosing == null || enclosing.isBlank()) {
        continue;
      }
      String simple = simpleName(enclosing);
      List<String> sameName = classesBySimpleName.computeIfAbsent(simple, k -> new ArrayList<>());
      if (!sameName.contains(enclosing)) {
        sameName.add(enclosing);
      }

      // The declaration fact: the class's own function carries the class's member
      // annotations. A front end that puts them on every function of the class produces
      // the same map twice, and the member is still one member.
      for (Map.Entry<String, List<String>> entry : fn.fieldAnnotations().entrySet()) {
        List<String> annotations = entry.getValue();
        if (annotations == null || !annotations.contains(PRIVATE)) {
          continue;
        }
        declaredPrivate.computeIfAbsent(enclosing, k -> new LinkedHashSet<>()).add(entry.getKey());
      }
    }

    // The assignments: "class.member" -> where the FIRST one is.
    Map<String, Assignment> assigned = new LinkedHashMap<>();
    // The reads that name a class: "class.member".
    Set<String> readMembers = new HashSet<>();
    // The reads whose class could not be told: the member NAME alone.
    Set<String> readNames = new HashSet<>();
    // The classes whose members a computed access could have reached.
    Set<String> opaqueClasses = new HashSet<>();

    // ---- the second pass: the accesses --------------------------------------------------
    for (TacFunction fn : program.functions()) {
      if (!fn.sourceLanguage().hasKeyedContainers()) {
        continue;
      }
      for (TacInstruction ins : fn.instructions()) {
        boolean store = ins.kind == TacInstruction.Kind.ARRAYSTORE;
        boolean load = ins.kind == TacInstruction.Kind.ARRAYLOAD;
        if (!store && !load) {
          continue;
        }
        // The classes the access could be made on. One class when the receiver says which
        // one; every class of that SIMPLE name when the receiver only says the spelling.
        List<String> owners = ownerClasses(ins, fn, classesBySimpleName);
        String key = stringKey(ins.lhs2);
        if (key == null) {
          // A computed access: `this[name]`. It names no member, so any member of the
          // receiver could be the one that was touched.
          if (owners.isEmpty()) {
            // The receiver's class is unknown too, so the access could be any object's:
            // nothing at all can be concluded about any member.
            return List.of();
          }
          opaqueClasses.addAll(owners);
          continue;
        }
        if (store) {
          for (String owner : owners) {
            // An assignment that cannot be placed is not a member of any class we can name.
            assigned.putIfAbsent(owner + "." + key, new Assignment(fn, ins.line, ins.column));
          }
        } else {
          if (owners.isEmpty()) {
            readNames.add(key);
          } else {
            for (String owner : owners) {
              readMembers.add(owner + "." + key);
            }
          }
        }
      }
    }

    List<UnusedMember> out = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : declaredPrivate.entrySet()) {
      String ownerClass = entry.getKey();
      if (opaqueClasses.contains(ownerClass)) {
        continue;
      }
      // A read that named the class only by its SPELLING reaches every class of that name.
      // The member it read is therefore read in every class that goes by the name.
      for (String member : entry.getValue()) {
        String full = ownerClass + "." + member;
        Assignment assignment = assigned.get(full);
        if (assignment == null) {
          // CWE-563 is about an ASSIGNMENT that is never used. A member that is declared
          // and never assigned is a different defect, and this rule does not report it.
          continue;
        }
        if (readMembers.contains(full) || readNames.contains(member)) {
          continue;
        }
        out.add(
            new UnusedMember(
                ownerClass, member, assignment.function(), assignment.line(), assignment.column()));
      }
    }
    return out;
  }

  /**
   * The simple name of a class: its last dot-separated segment.
   *
   * <p>The last DOT, not the last {@code '$'}: a nested class is {@code Outer$Inner}, and the
   * engine's own keys spell it that way.
   *
   * @param qualified the class name, qualified or not
   * @return the simple name
   */
  private static String simpleName(String qualified) {
    if (qualified == null || qualified.isEmpty()) {
      return "";
    }
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? qualified : qualified.substring(dot + 1);
  }

  /**
   * Where an assignment is: the function that holds it, and the place in the source.
   *
   * <p>The place matters: the reporter tells two findings apart by (file, line, rule, cwe), so a
   * finding without a line cannot be told from another finding in the same file. Two classes that
   * each declare an unread private member are two findings, and without the line they are one.
   *
   * @param function the function the assignment is in
   * @param line the line of the assignment, or -1
   * @param column the column of the assignment, or -1
   */
  private record Assignment(TacFunction function, int line, int column) {}

  /**
   * The classes whose namespace an access could belong to.
   *
   * <p>The receiver is the thing the member hangs on, so its class is the class the member belongs
   * to. The receiver says that twice: as its type ({@code other:Config} names {@code Config}), and
   * -- for a static access -- as its own name ({@code Shapes.count} is reached through the class
   * itself).
   *
   * <p>{@code this} is the object the code is written for, so its member belongs to the class the
   * code is written in -- and that class is known by its QUALIFIED name, so the access is placed on
   * exactly one class.
   *
   * <p>A receiver whose TYPE names a class names it by its SPELLING: a type says "Config", not
   * "which module's Config". So the access is placed on EVERY class that goes by that name -- the
   * conservative reading, because the other one is how a member that IS read gets reported as
   * unread.
   *
   * @param ins the access
   * @param fn the function the access is written in
   * @param classesBySimpleName every class of the program, by its simple name
   * @return the classes the access could be made on; empty when it names none
   */
  private static List<String> ownerClasses(
      TacInstruction ins, TacFunction fn, Map<String, List<String>> classesBySimpleName) {
    if (ins == null || fn == null) {
      return List.of();
    }
    if (!(ins.lhs1 instanceof TacOperand.TacVar receiver)) {
      return List.of();
    }
    String enclosing = fn.enclosingClass();
    // `this` is checked FIRST: the receiver named `this` is the object the code is written
    // for, so its class is the class the code is written in -- and that is known exactly.
    // A front end that types `this` with the class says the same thing in a spelling that
    // does not say WHICH module's class it is.
    if ("this".equals(receiver.name())) {
      if (enclosing == null || enclosing.isBlank()) {
        return List.of();
      }
      return List.of(enclosing);
    }
    String byType = FieldOpClassifier.classNameOfType(receiver.type());
    if (byType != null && !byType.isEmpty()) {
      List<String> candidates = classesBySimpleName.get(simpleName(byType));
      return candidates == null ? List.of() : candidates;
    }
    // A static member: the receiver IS the class. The receiver is named the way the class
    // is named -- by its qualified name, or by the simple name the source spells.
    if (enclosing != null && !enclosing.isBlank()) {
      if (enclosing.equals(receiver.name()) || simpleName(enclosing).equals(receiver.name())) {
        return List.of(enclosing);
      }
    }
    return List.of();
  }

  /**
   * The literal name a keyed access uses, or null when the key is not a name.
   *
   * @param op the key operand
   * @return the name, or null
   */
  private static String stringKey(TacOperand op) {
    if (op instanceof TacOperand.Const c
        && c.kind() == TacOperand.Const.Kind.STRING
        && c.value() instanceof String s
        && !s.isEmpty()) {
      return s;
    }
    return null;
  }
}
