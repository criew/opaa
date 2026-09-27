package io.opaa.knowledge;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The open key of a library's quellentyp (ADR-0038, Entscheidung 1): upper-case letters, digits and
 * underscores, at most 20 characters. Which keys exist only the connector registry knows; the one
 * the holdings name themselves is {@link #UPLOAD}, the type whose documents are uploaded one by one
 * instead of filled by a run.
 */
public record SourceType(String key) implements Comparable<SourceType> {

  public static final int MAX_LENGTH = 20;

  private static final Pattern FORM = Pattern.compile("[A-Z][A-Z0-9_]*");

  public static final SourceType UPLOAD = new SourceType("UPLOAD");

  public SourceType {
    Objects.requireNonNull(key, "key");
    if (key.length() > MAX_LENGTH || !FORM.matcher(key).matches()) {
      throw new IllegalArgumentException("no source type key: " + key);
    }
  }

  public static SourceType of(String key) {
    return new SourceType(key);
  }

  /** Whether {@code key} has the form of a source type key. */
  public static boolean isKey(String key) {
    return key != null && key.length() <= MAX_LENGTH && FORM.matcher(key).matches();
  }

  @Override
  public int compareTo(SourceType other) {
    return key.compareTo(other.key);
  }

  @Override
  public String toString() {
    return key;
  }
}
