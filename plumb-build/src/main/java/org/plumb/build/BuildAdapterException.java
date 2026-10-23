package org.plumb.build;

/**
 * R-833 ? Adapter errors (parse failures, missing required files, broken build state). The CLI
 * surfaces {@link #getMessage()} verbatim and exits non-zero; the full stack trace goes to stderr
 * in verbose mode.
 */
public class BuildAdapterException extends RuntimeException {

  public BuildAdapterException(String message) {
    super(message);
  }

  public BuildAdapterException(String message, Throwable cause) {
    super(message, cause);
  }
}
