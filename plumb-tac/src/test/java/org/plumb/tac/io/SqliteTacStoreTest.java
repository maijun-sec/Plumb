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
package org.plumb.tac.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The TAC store, kept in an embedded database.
 *
 * <p><b>What has to hold.</b> A program that goes in comes out; the store survives being closed and
 * reopened; the order the programs come out in is stable; and a blob written by a different version
 * is refused rather than misread.
 */
@DisplayName("TAC: the store in an embedded database")
class SqliteTacStoreTest {

  @TempDir Path tmp;

  /**
   * A small program.
   *
   * @param name the function's name
   * @return the program
   */
  private static TacProgram program(String name) {
    TacOperand.TacVar temp = new TacOperand.TacVar("t0", null, true);
    TacFunction fn =
        new TacFunction(
            name,
            name + "():void",
            null,
            List.of(),
            List.of(temp),
            List.of(
                TacInstruction.builder(TacInstruction.Kind.MOVE)
                    .dest(temp)
                    .lhs1(TacOperand.Const.ofString("payload"))
                    .line(1)
                    .column(1)
                    .build()));
    TacProgram program = new TacProgram(null, "Holder");
    program.addFunction(fn);
    return program;
  }

  @Test
  @DisplayName("a program goes in and comes out")
  void aProgramGoesInAndComesOut() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      store.put("a", program("alpha"));

      TacProgram read = store.get("a");
      assertThat(read).as("the program is there").isNotNull();
      assertThat(read.functions()).as("with its function").hasSize(1);
      assertThat(read.functions().get(0).name()).isEqualTo("alpha");
    }
  }

  @Test
  @DisplayName("the store survives being closed and reopened")
  void theStoreSurvivesBeingClosedAndReopened() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      store.put("a", program("alpha"));
      store.put("b", program("beta"));
    }

    // This is the property that makes the store portable: the artefact on
    // disk is the whole thing, and it can be reopened anywhere.
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      assertThat(store.size()).as("both programs are still there").isEqualTo(2);
      assertThat(store.get("a")).isNotNull();
      assertThat(store.get("b")).isNotNull();
    }
  }

  @Test
  @DisplayName("the programs come out in a stable order")
  void theProgramsComeOutInAStableOrder() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      store.put("zebra", program("z"));
      store.put("alpha", program("a"));
      store.put("middle", program("m"));

      List<String> keys = store.keys();
      assertThat(keys)
          .as("the order is by key, so two reads of the same store agree")
          .containsExactly("alpha", "middle", "zebra");
      assertThat(store.programs())
          .as("and the programs come out in that same order")
          .extracting(p -> p.functions().get(0).name())
          .containsExactly("a", "m", "z");
    }
  }

  @Test
  @DisplayName("a key that is not there reads back as nothing")
  void aKeyThatIsNotThereReadsBackAsNothing() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      assertThat(store.get("missing")).as("an absent program is absent, not an error").isNull();
      assertThat(store.contains("missing")).isFalse();
      assertThat(store.contains("present")).isFalse();
    }
  }

  @Test
  @DisplayName("storing the same key twice replaces the program")
  void storingTheSameKeyTwiceReplacesTheProgram() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      store.put("a", program("alpha"));
      store.put("a", program("beta"));

      assertThat(store.size()).as("the store holds one row for the key").isEqualTo(1);
      assertThat(store.get("a").functions().get(0).name()).isEqualTo("beta");
    }
  }

  @Test
  @DisplayName("the merged program goes in and comes out")
  void theMergedProgramGoesInAndComesOut() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      assertThat(store.getMerged()).as("an empty store holds no merged program").isNull();

      store.putMerged(new MergedProgram(List.of(program("alpha"))));

      MergedProgram read = store.getMerged();
      assertThat(read).as("the merged program is there").isNotNull();
      assertThat(read.sources()).as("with its sources").hasSize(1);
    }
  }

  @Test
  @DisplayName("the manifest says what the store holds")
  void theManifestSaysWhatTheStoreHolds() throws Exception {
    Path db = tmp.resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      store.putManifest("producer_root", "D:/proj");

      assertThat(store.manifest())
          .as("the store describes itself")
          .containsEntry("producer_root", "D:/proj")
          .containsEntry("ir_version", String.valueOf(IrSerializer.VERSION));
    }
  }

  @Test
  @DisplayName("a blob that is not a current IR is refused")
  void aBlobThatIsNotACurrentIrIsRefused() throws Exception {
    // The version check is what turns "the consumer is too old for this
    // store" into a message instead of a class-loading failure. The blob here
    // is a well-formed serialization stream whose magic is wrong -- the case
    // that a store written by a different tool actually looks like.
    byte[] wrongMagic = streamWithHeader(0x0BADBAD, IrSerializer.VERSION);
    assertThatThrownBy(() -> IrSerializer.fromBytes(wrongMagic, TacProgram.class))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("magic");

    // A stream with the right magic but an unsupported version is refused
    // too, and it says which version it found.
    byte[] wrongVersion = streamWithHeader(IrSerializer.MAGIC, IrSerializer.VERSION + 1);
    assertThatThrownBy(() -> IrSerializer.fromBytes(wrongVersion, TacProgram.class))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("version");

    // And a blob that is not a serialization stream at all is refused too --
    // with a message about the stream, because that is what is actually
    // wrong with it.
    assertThatThrownBy(() -> IrSerializer.fromBytes(new byte[] {0, 0, 0, 0}, TacProgram.class))
        .isInstanceOf(IOException.class);
  }

  /**
   * A serialization stream that carries a header the reader will reject.
   *
   * @param magic the magic to write
   * @param version the version to write
   * @return the bytes
   */
  private static byte[] streamWithHeader(int magic, int version) throws IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
      out.writeInt(magic);
      out.writeInt(version);
      out.writeUTF("program");
      out.writeObject("payload");
    }
    return bytes.toByteArray();
  }

  @Test
  @DisplayName("a blob holding the wrong kind of object is refused")
  void aBlobHoldingTheWrongKindOfObjectIsRefused() throws Exception {
    // A store whose merged row holds a program would otherwise deserialize
    // into a program and fail somewhere far away from the mistake.
    byte[] payload = IrSerializer.toBytes("program", program("alpha"));
    assertThatThrownBy(() -> IrSerializer.fromBytes(payload, MergedProgram.class))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not a");
  }

  @Test
  @DisplayName("the byte form and the file form are interchangeable")
  void theByteFormAndTheFileFormAreInterchangeable() throws Exception {
    // The store keeps a blob; the cache keeps a file. A blob that can be
    // dumped to a file and read back means the two forms share one contract,
    // which is what makes the migration between them mechanical.
    Path file = tmp.resolve("program.tac");
    IrSerializer.writeObject("program", program("alpha"), file);

    byte[] bytes = Files.readAllBytes(file);
    TacProgram read = IrSerializer.fromBytes(bytes, TacProgram.class);
    assertThat(read.functions().get(0).name()).isEqualTo("alpha");

    Path other = tmp.resolve("roundtrip.tac");
    Files.write(other, IrSerializer.toBytes("program", program("beta")));
    assertThat(IrSerializer.readObject(other)).isInstanceOf(TacProgram.class);
  }

  @Test
  @DisplayName("the store creates its own directory")
  void theStoreCreatesItsOwnDirectory() throws Exception {
    Path db = tmp.resolve("nested").resolve("deeper").resolve("tac.db");
    try (SqliteTacStore store = SqliteTacStore.open(db)) {
      store.put("a", program("alpha"));
      assertThat(db).as("the file is there").exists();
    }
  }
}
