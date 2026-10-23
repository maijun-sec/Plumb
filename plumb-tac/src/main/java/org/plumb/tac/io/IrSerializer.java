package org.plumb.tac.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacProgram;

/**
 * Disk-cache contract for the TAC IR.
 *
 * <p>The IR is intrinsically a tree of small, immutable data classes ({@link TacProgram}, {@link
 * org.plumb.tac.ir.TacFunction}, {@link org.plumb.tac.ir.TacInstruction}, ...). All of them
 * implement {@link Serializable} with a stable {@code serialVersionUID = 1L}, so the standard Java
 * serialization format is sufficient to persist them to disk and read them back unchanged.
 *
 * <p>The writer uses {@link ObjectOutputStream} wrapped in a {@link BufferedOutputStream}; the
 * reader mirrors the layout. Both are tolerant of the optional GZIP {@code .gz} suffix ? see {@link
 * #isGzip(Path)}.
 *
 * <h2>Why this lives here</h2>
 *
 * <p>The pipeline is staged (<em>parse → style → tac → dfa</em>). For large inputs the TAC stage
 * produces hundreds of MB of in-memory state (programs + call graph) that the DFA stage then walks.
 * Persisting TAC between the two stages keeps the working set bounded ? the DFA stage can page in a
 * {@link MergedProgram} one chunk at a time and the GC can collect the style phase's ASTs
 * immediately.
 *
 * <h2>File naming</h2>
 *
 * <pre>
 *   output-dir/
 *     cache/
 *       programs/
 *         &lt;sha1-of-source-path&gt;.tac         one file per TacProgram
 *       merged.tac                          the full MergedProgram
 *       callgraph.tac                       the global call graph
 *       sources/                            original sources, by hash
 * </pre>
 *
 * <p>When the user re-runs the same scan the cache can short-circuit the parse + translate + style
 * phase if the source files have not changed.
 */
public final class IrSerializer {

  /** Magic bytes prefixing every IR file. Used to fail fast on corruption / version mismatch. */
  public static final int MAGIC = 0x504C_554D; // 'PLUM' (Plumb)

  /**
   * Current format version. Bump on incompatible schema changes (also bump every {@code
   * serialVersionUID} on IR classes).
   */
  public static final int VERSION = 1;

  private IrSerializer() {}

  // -------- file-level helpers --------

  public static boolean isGzip(Path p) {
    return p.getFileName().toString().toLowerCase().endsWith(".gz");
  }

  // -------- TacProgram --------

  /** Persist a single {@link TacProgram}. */
  public static void writeProgram(TacProgram program, Path file) throws IOException {
    try (OutputStream raw =
            Files.newOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        OutputStream out = new BufferedOutputStream(maybeGzip(raw, file))) {
      try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
        oos.writeInt(MAGIC);
        oos.writeInt(VERSION);
        oos.writeObject(program);
      }
    }
  }

  /** Read a single {@link TacProgram} previously written by {@link #writeProgram}. */
  public static TacProgram readProgram(Path file) throws IOException {
    try (InputStream raw = Files.newInputStream(file);
        InputStream in = new BufferedInputStream(maybeGunzip(raw, file))) {
      try (ObjectInputStream ois = new ObjectInputStream(in)) {
        int magic = ois.readInt();
        if (magic != MAGIC) {
          throw new IOException("Bad IR magic in " + file + ": 0x" + Integer.toHexString(magic));
        }
        int version = ois.readInt();
        if (version != VERSION) {
          throw new IOException(
              "Unsupported IR version " + version + " in " + file + " (expected " + VERSION + ")");
        }
        Object o = ois.readObject();
        if (!(o instanceof TacProgram p)) {
          throw new IOException(
              "Expected TacProgram in "
                  + file
                  + " but found "
                  + (o == null ? "null" : o.getClass()));
        }
        return p;
      } catch (ClassNotFoundException e) {
        throw new IOException("IR class missing on classpath: " + e.getMessage(), e);
      }
    }
  }

  // -------- MergedProgram --------

  public static void writeMerged(MergedProgram merged, Path file) throws IOException {
    try (OutputStream raw =
            Files.newOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        OutputStream out = new BufferedOutputStream(maybeGzip(raw, file))) {
      try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
        oos.writeInt(MAGIC);
        oos.writeInt(VERSION);
        oos.writeObject(merged);
      }
    }
  }

  public static MergedProgram readMerged(Path file) throws IOException {
    try (InputStream raw = Files.newInputStream(file);
        InputStream in = new BufferedInputStream(maybeGunzip(raw, file))) {
      try (ObjectInputStream ois = new ObjectInputStream(in)) {
        int magic = ois.readInt();
        if (magic != MAGIC) {
          throw new IOException("Bad IR magic in " + file);
        }
        int version = ois.readInt();
        if (version != VERSION) {
          throw new IOException("Unsupported IR version " + version);
        }
        Object o = ois.readObject();
        if (!(o instanceof MergedProgram m)) {
          throw new IOException("Expected MergedProgram in " + file);
        }
        return m;
      } catch (ClassNotFoundException e) {
        throw new IOException("IR class missing on classpath: " + e.getMessage(), e);
      }
    }
  }

  // -------- generic object (for call graphs, summaries, ...) --------

  public static void writeObject(String tag, Object value, Path file) throws IOException {
    try (OutputStream raw =
            Files.newOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        OutputStream out = new BufferedOutputStream(maybeGzip(raw, file))) {
      try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
        oos.writeInt(MAGIC);
        oos.writeInt(VERSION);
        oos.writeUTF(tag);
        oos.writeObject(value);
      }
    }
  }

  public static Object readObject(Path file) throws IOException {
    try (InputStream raw = Files.newInputStream(file);
        InputStream in = new BufferedInputStream(maybeGunzip(raw, file))) {
      try (ObjectInputStream ois = new ObjectInputStream(in)) {
        int magic = ois.readInt();
        if (magic != MAGIC) {
          throw new IOException("Bad IR magic in " + file);
        }
        int version = ois.readInt();
        if (version != VERSION) {
          throw new IOException("Unsupported IR version " + version);
        }
        ois.readUTF(); // tag, currently informational only
        try {
          return ois.readObject();
        } catch (ClassNotFoundException e) {
          throw new IOException("IR class missing on classpath: " + e.getMessage(), e);
        }
      }
    }
  }

  // -------- optional gzip wrapping --------

  private static OutputStream maybeGzip(OutputStream out, Path file) throws IOException {
    if (isGzip(file)) {
      return new java.util.zip.GZIPOutputStream(out);
    }
    return out;
  }

  private static InputStream maybeGunzip(InputStream in, Path file) throws IOException {
    if (isGzip(file)) {
      return new java.util.zip.GZIPInputStream(in);
    }
    return in;
  }
}
