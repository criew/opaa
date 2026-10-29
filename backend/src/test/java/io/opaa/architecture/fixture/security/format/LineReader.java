package io.opaa.architecture.fixture.security.format;

import java.util.regex.Pattern;

/** Two code units that compile a pattern per call, one that uses a compiled one. */
public final class LineReader {

  private static final Pattern BLANK = Pattern.compile("\\s+");

  private LineReader() {}

  public static String[] lines(String text) {
    return text.split("\\R");
  }

  public static boolean isKey(String text) {
    return Pattern.compile("[a-z]+").matcher(text).matches();
  }

  public static String collapsed(String text) {
    return BLANK.matcher(text).replaceAll(" ");
  }
}
