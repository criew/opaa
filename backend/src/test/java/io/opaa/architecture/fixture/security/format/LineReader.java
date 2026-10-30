package io.opaa.architecture.fixture.security.format;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Code units that compile a pattern per call or per object - by call, by method reference, in a
 * constructor - and one that uses a compiled one.
 */
public final class LineReader {

  private static final Pattern BLANK = Pattern.compile("\\s+");

  private final Pattern separator;

  public LineReader(String separator) {
    this.separator = Pattern.compile(Pattern.quote(separator));
  }

  public String[] fields(String line) {
    return separator.split(line);
  }

  public static Function<String, Pattern> compiler() {
    return Pattern::compile;
  }

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
