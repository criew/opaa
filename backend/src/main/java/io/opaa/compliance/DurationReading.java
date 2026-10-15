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
  private static final Pattern SHORT = Pattern.compile("(\\d+)(ms|s|m|h|d)");
  private static final Pattern NUMBER = Pattern.compile("\\d+");
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
    Matcher unit = KEY_UNIT.matcher(key.toLowerCase(Locale.ROOT));
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

  private static String words(long amount, String[] unit) {
    return amount + " " + (amount == 1 ? unit[0] : unit[1]);
  }
}
