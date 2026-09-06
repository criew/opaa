package io.opaa.indexing.pipeline;

import java.util.regex.Pattern;

/**
 * Whitespace normalization across the formats: every run of whitespace collapses to a single space,
 * with the non-breaking (U+00A0) and the narrow no-break space (U+202F) counted as whitespace,
 * which {@code \s} does not do.
 */
public final class Whitespace {

  public static final Pattern PATTERN = Pattern.compile("[\\s\\u00A0\\u202F]+");

  private Whitespace() {}

  /**
   * {@code text} with every whitespace run collapsed to a single space; {@code null} stays null.
   */
  public static String normalize(String text) {
    return text == null ? null : PATTERN.matcher(text).replaceAll(" ");
  }
}
