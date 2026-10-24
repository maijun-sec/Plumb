package org.plumb.rules;

import java.util.Objects;

/**
 * Configurable parameter for a {@link RuleDef}.
 *
 * <p>Each parameter is a typed value (string, integer, boolean) that a checker can read to
 * customize its behavior. The default value is used when no override is provided. The original
 * tool's parameter names (e.g. PMD's {@code maximumMessageLength}, Checkstyle's {@code max},
 * SonarQube's {@code threshold}) are preserved so that users familiar with those tools can
 * configure rules in a familiar way.
 *
 * <p>Parameters are <em>declared</em> on the rule (so a UI can show all available knobs) and
 * <em>read</em> by the checker. The runtime lookup is via {@link
 * org.plumb.rules.RuleRegistry#param(String, String)} which falls back to the default if no
 * override is set.
 */
public final class RuleParameter {

  /** Type of the parameter value. */
  public enum Type {
    STRING,
    INT,
    BOOLEAN
  }

  private final String name;
  private final String description;
  private final Type type;
  private final String defaultValue;

  public RuleParameter(String name, Type type, String defaultValue) {
    this(name, name, type, defaultValue);
  }

  public RuleParameter(String name, String description, Type type, String defaultValue) {
    this.name = Objects.requireNonNull(name, "name");
    this.description = Objects.requireNonNull(description, "description");
    this.type = Objects.requireNonNull(type, "type");
    this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public Type type() {
    return type;
  }

  public String defaultValue() {
    return defaultValue;
  }

  public int intDefault() {
    return Integer.parseInt(defaultValue);
  }

  public boolean boolDefault() {
    return Boolean.parseBoolean(defaultValue);
  }

  @Override
  public String toString() {
    return name + "(" + type + ", default=" + defaultValue + ")";
  }
}
