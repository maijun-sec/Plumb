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

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacProgram;

/**
 * The TAC store, kept in an embedded database.
 *
 * <p><b>Why a database and not a directory of files.</b> A file-per-program store is simple, and it
 * is what the cache used to be. But a whole-project analysis opens thousands of files, and a
 * database turns that into one handle with one transaction. It also makes the store a single
 * artefact: copying it to another machine is one file copy, and it cannot be half-copied.
 *
 * <p><b>Why the IR is kept as a blob and not as tables.</b> The IR is a tree of objects with
 * polymorphic fields -- an operand's value can be an integer, a string, a character or null, and a
 * path's selector can be six different kinds of thing. Laying that out as columns means a schema
 * migration for every field the IR grows, and a reader that has to know the rules the writer used.
 * A blob has neither problem: the IR's own serialization is the contract, and the database only has
 * to move bytes.
 *
 * <p><b>The blob is not raw.</b> It carries the same magic and version the file form carries, so a
 * blob written by one tool can be read by the other and a version mismatch is a clear error rather
 * than a confusing class-loading failure.
 *
 * <p><b>The store is a CONTRACT, not a cache.</b> The IR it holds is enough to run the analysis
 * without the sources, without the classpath and without a toolchain. That is what makes the store
 * portable: copy the database to another machine, and the analysis runs there.
 */
public final class SqliteTacStore implements AutoCloseable {

  /** The JDBC URL for a file-backed database. */
  private static final String URL_PREFIX = "jdbc:sqlite:";

  /** The schema version. Bumped when the tables change. */
  private static final int SCHEMA_VERSION = 1;

  private final Connection connection;
  private final Path file;

  private SqliteTacStore(Connection connection, Path file) {
    this.connection = connection;
    this.file = file;
  }

  /**
   * Open a store, creating it when it does not exist.
   *
   * @param file the database file
   * @return the store
   * @throws IOException if the database cannot be opened
   */
  public static SqliteTacStore open(Path file) throws IOException {
    if (file == null) {
      throw new IOException("the TAC store needs a file");
    }
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      java.nio.file.Files.createDirectories(parent);
    }
    try {
      // The driver registers itself through a multi-release service file, which
      // the plain lookup does not find on a modern JDK. So it is registered
      // explicitly -- otherwise the connection fails with "no suitable
      // driver", which says nothing about what went wrong.
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new IOException("the SQLite driver is not on the classpath", e);
    }
    try {
      Connection connection = DriverManager.getConnection(URL_PREFIX + file.toAbsolutePath());
      SqliteTacStore store = new SqliteTacStore(connection, file);
      store.createSchema();
      return store;
    } catch (SQLException e) {
      throw new IOException("could not open the TAC store " + file + ": " + e.getMessage(), e);
    }
  }

  /** Create the tables, if they are not there yet. */
  private void createSchema() throws IOException {
    try (Statement statement = connection.createStatement()) {
      // WAL keeps a reader from blocking the writer, which matters when the
      // analysis reads while another process is still storing.
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute("PRAGMA synchronous=NORMAL");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS programs (
            cache_key  TEXT PRIMARY KEY,
            source_path TEXT,
            ir_version  INTEGER NOT NULL,
            payload     BLOB NOT NULL
          )
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS merged (
            cache_key  TEXT PRIMARY KEY,
            ir_version INTEGER NOT NULL,
            payload    BLOB NOT NULL
          )
          """);
      // The manifest is what makes the store self-describing: a reader can ask
      // the store what it holds without having to be told.
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS manifest (
            key   TEXT PRIMARY KEY,
            value TEXT
          )
          """);
      statement.execute(
          "INSERT OR REPLACE INTO manifest(key, value) VALUES('schema_version', '"
              + SCHEMA_VERSION
              + "')");
      statement.execute(
          "INSERT OR REPLACE INTO manifest(key, value) VALUES('ir_version', '"
              + IrSerializer.VERSION
              + "')");
    } catch (SQLException e) {
      throw new IOException("could not create the TAC store's schema: " + e.getMessage(), e);
    }
  }

  /**
   * Store one program.
   *
   * <p>The key is the caller's; the store does not decide what identifies a program, because the
   * caller may be keying on a source path or on something else entirely.
   *
   * @param key the key
   * @param program the program
   * @throws IOException if the program cannot be stored
   */
  public void put(String key, TacProgram program) throws IOException {
    if (key == null || program == null) {
      throw new IOException("a stored program needs a key and a program");
    }
    byte[] payload = IrSerializer.toBytes("program", program);
    String sourcePath = program.sourcePath() == null ? null : program.sourcePath().toString();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT OR REPLACE INTO programs(cache_key, source_path, ir_version, payload)"
                + " VALUES(?, ?, ?, ?)")) {
      statement.setString(1, key);
      statement.setString(2, sourcePath);
      statement.setInt(3, IrSerializer.VERSION);
      statement.setBytes(4, payload);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IOException("could not store the program " + key + ": " + e.getMessage(), e);
    }
  }

  /**
   * Read one program.
   *
   * @param key the key
   * @return the program, or {@code null} when the store does not hold it
   * @throws IOException if the program cannot be read
   */
  public TacProgram get(String key) throws IOException {
    if (key == null) {
      return null;
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT payload FROM programs WHERE cache_key = ?")) {
      statement.setString(1, key);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        return IrSerializer.fromBytes(rows.getBytes(1), TacProgram.class);
      }
    } catch (SQLException e) {
      throw new IOException("could not read the program " + key + ": " + e.getMessage(), e);
    }
  }

  /**
   * Whether the store holds a program.
   *
   * @param key the key
   * @return true when it does
   * @throws IOException if the store cannot be read
   */
  public boolean contains(String key) throws IOException {
    if (key == null) {
      return false;
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM programs WHERE cache_key = ?")) {
      statement.setString(1, key);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException e) {
      throw new IOException("could not query the program " + key + ": " + e.getMessage(), e);
    }
  }

  /**
   * Every program in the store, keyed and ordered by key.
   *
   * <p>The order is by key, not by insertion: a store read twice must give the same order, because
   * the analysis's cross-file resolution depends on seeing the same files in the same sequence.
   *
   * @return the programs
   * @throws IOException if the store cannot be read
   */
  public List<TacProgram> programs() throws IOException {
    List<TacProgram> out = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT payload FROM programs ORDER BY cache_key");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        out.add(IrSerializer.fromBytes(rows.getBytes(1), TacProgram.class));
      }
    } catch (SQLException e) {
      throw new IOException("could not read the store's programs: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * The keys in the store, ordered.
   *
   * @return the keys
   * @throws IOException if the store cannot be read
   */
  public List<String> keys() throws IOException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT cache_key FROM programs ORDER BY cache_key");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        out.add(rows.getString(1));
      }
    } catch (SQLException e) {
      throw new IOException("could not read the store's keys: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * Store the merged program.
   *
   * @param merged the merged program
   * @throws IOException if it cannot be stored
   */
  public void putMerged(MergedProgram merged) throws IOException {
    if (merged == null) {
      throw new IOException("there is no merged program to store");
    }
    byte[] payload = IrSerializer.toBytes("merged", merged);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT OR REPLACE INTO merged(cache_key, ir_version, payload) VALUES(?, ?, ?)")) {
      statement.setString(1, "merged");
      statement.setInt(2, IrSerializer.VERSION);
      statement.setBytes(3, payload);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IOException("could not store the merged program: " + e.getMessage(), e);
    }
  }

  /**
   * Read the merged program.
   *
   * @return the merged program, or {@code null} when the store does not hold one
   * @throws IOException if it cannot be read
   */
  public MergedProgram getMerged() throws IOException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT payload FROM merged WHERE cache_key = 'merged'");
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return null;
      }
      return IrSerializer.fromBytes(rows.getBytes(1), MergedProgram.class);
    } catch (SQLException e) {
      throw new IOException("could not read the merged program: " + e.getMessage(), e);
    }
  }

  /**
   * The manifest: what the store says about itself.
   *
   * @return the manifest entries
   * @throws IOException if the store cannot be read
   */
  public Map<String, String> manifest() throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT key, value FROM manifest ORDER BY key");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        out.put(rows.getString(1), rows.getString(2));
      }
    } catch (SQLException e) {
      throw new IOException("could not read the store's manifest: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * Record a manifest entry.
   *
   * @param key the entry's name
   * @param value the entry's value
   * @throws IOException if the entry cannot be written
   */
  public void putManifest(String key, String value) throws IOException {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT OR REPLACE INTO manifest(key, value) VALUES(?, ?)")) {
      statement.setString(1, key);
      statement.setString(2, value);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IOException("could not write the manifest entry " + key + ": " + e.getMessage(), e);
    }
  }

  /**
   * How many programs the store holds.
   *
   * @return the count
   * @throws IOException if the store cannot be read
   */
  public int size() throws IOException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT COUNT(*) FROM programs");
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getInt(1) : 0;
    } catch (SQLException e) {
      throw new IOException("could not count the store's programs: " + e.getMessage(), e);
    }
  }

  /** The database file this store lives in. */
  public Path file() {
    return file;
  }

  @Override
  public void close() {
    try {
      if (connection != null && !connection.isClosed()) {
        connection.close();
      }
    } catch (SQLException e) {
      // Closing a store that is already closed is not worth reporting.
    }
  }
}
