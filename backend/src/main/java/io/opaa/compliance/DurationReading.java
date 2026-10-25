package io.opaa.compliance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a delivered setting value as a period in German words for the fact sheets: an ISO-8601
 * duration ({@code P7D}, {@code PT15M}), a Spring short form ({@code 15m}, {@code 7d}) or a bare
 * number whose key names its unit ({@code ..._days}, {@code ...-hours}). Anything else has no
 * reading.
 */
final class DurationReading {

  private static final Pattern ISO =
      Pattern.compile(
          "P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?");
  private static final String[][] ISO_UNITS = {
    {"Jahr", "Jahre"},
    {"Monat", "Monate"},
    {"Woche", "Wochen"},
    {"Tag", "Tage"},
    {"Stunde", "Stunden"},
    {"Minute", "Minuten"},
    {"Sekunde", "Sekunden"}
  };

  /**
   * Seconds per unit of {@link #ISO_UNITS}: a year as 365 days and a month as a twelfth of it, so
   * that {@code P1Y} and {@code P12M} are equally long.
   */
  private static final long[] ISO_SECONDS = {
    365L * 86_400, 365L * 86_400 / 12, 7L * 86_400, 86_400, 3_600, 60, 1
  };

  private static final Pattern SHORT = Pattern.compile("(\\d+)(ms|s|m|h|d)");
  private static final Pattern NUMBER = Pattern.compile("\\d+");

  /** The row a {@code DATABASE} key names in brackets, {@code table.column[ROW]}. */
  private static final Pattern ROW = Pattern.compile("\\[[^\\]]*\\]$");

  private static final Pattern KEY_UNIT =
      Pattern.compile(".*[._#-](seconds|minutes|hours|days|weeks|months|years)");

  private DurationReading() {}

  /** The reading of {@code value} of the setting {@code key}, or {@code null} if it has none. */
  static String of(String value, String key) {
    String trimmed = value.strip();
    Matcher iso = ISO.matcher(trimmed.toUpperCase(Locale.ROOT));
    if (trimmed.length() > 1 && iso.matches()) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < ISO_UNITS.length; i++) {
        if (iso.group(i + 1) != null) {
          parts.add(words(Long.parseLong(iso.group(i + 1)), ISO_UNITS[i]));
        }
      }
      return parts.isEmpty() ? null : String.join(" ", parts);
    }
    Matcher shortForm = SHORT.matcher(trimmed);
    if (shortForm.matches()) {
      long amount = Long.parseLong(shortForm.group(1));
      return switch (shortForm.group(2)) {
        case "ms" -> words(amount, new String[] {"Millisekunde", "Millisekunden"});
        case "s" -> words(amount, ISO_UNITS[6]);
        case "m" -> words(amount, ISO_UNITS[5]);
        case "h" -> words(amount, ISO_UNITS[4]);
        default -> words(amount, ISO_UNITS[3]);
      };
    }
    Matcher unit = KEY_UNIT.matcher(ROW.matcher(key).replaceFirst("").toLowerCase(Locale.ROOT));
    if (NUMBER.matcher(trimmed).matches() && unit.matches()) {
      int index =
          switch (unit.group(1)) {
            case "years" -> 0;
            case "months" -> 1;
            case "weeks" -> 2;
            case "days" -> 3;
            case "hours" -> 4;
            case "minutes" -> 5;
            default -> 6;
          };
      return words(Long.parseLong(trimmed), ISO_UNITS[index]);
    }
    return null;
  }

  /**
   * The approximate length of {@code value} of the setting {@code key} in seconds, for comparing
   * two values of one setting (a year counts 365 days, a month a twelfth of it), or {@code null} if
   * it has no reading or is not positive, as zero often stands for "no limit".
   */
  static Long approximateSeconds(String value, String key) {
    String trimmed = value.strip();
    long seconds = -1;
    Matcher iso = ISO.matcher(trimmed.toUpperCase(Locale.ROOT));
    Matcher shortForm = SHORT.matcher(trimmed);
    Matcher unit = KEY_UNIT.matcher(ROW.matcher(key).replaceFirst("").toLowerCase(Locale.ROOT));
    if (trimmed.length() > 1 && iso.matches()) {
      seconds = 0;
      for (int i = 0; i < ISO_SECONDS.length; i++) {
        if (iso.group(i + 1) != null) {
          seconds += Long.parseLong(iso.group(i + 1)) * ISO_SECONDS[i];
        }
      }
    } else if (shortForm.matches()) {
      long amount = Long.parseLong(shortForm.group(1));
      seconds =
          switch (shortForm.group(2)) {
            case "ms" -> amount / 1000;
            case "s" -> amount;
            case "m" -> amount * 60;
            case "h" -> amount * 3_600;
            default -> amount * 86_400;
          };
    } else if (NUMBER.matcher(trimmed).matches() && unit.matches()) {
      long amount = Long.parseLong(trimmed);
      seconds =
          amount
              * switch (unit.group(1)) {
                case "years" -> ISO_SECONDS[0];
                case "months" -> ISO_SECONDS[1];
                case "weeks" -> ISO_SECONDS[2];
                case "days" -> ISO_SECONDS[3];
                case "hours" -> ISO_SECONDS[4];
                case "minutes" -> ISO_SECONDS[5];
                default -> ISO_SECONDS[6];
              };
    }
    return seconds > 0 ? seconds : null;
  }

  private static String words(long amount, String[] unit) {
    return amount + " " + (amount == 1 ? unit[0] : unit[1]);
  }
}
