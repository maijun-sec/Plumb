package org.plumb.reporter;


import java.util.Locale;

/** Convert a {@link Finding} into a human-readable line for stdout. */
public final class FindingFormatter {

  private FindingFormatter() {}

  public static String formatConsole(Finding f) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "%s:%d:%d  ", f.file(), f.line(), f.column()));
    sb.append("[").append(f.severity()).append("] ");
    if (f.rule() != null) {
      sb.append(f.rule().ruleId().name()).append("  ");
    }
    sb.append(f.message());
    if (f.dataflowHead() != null) {
      sb.append("  [").append(f.dataflowHead()).append("]");
    }
    return sb.toString();
  }
}
