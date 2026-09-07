package io.opaa.common;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The app-wide human-readable size form, identical to {@code formatFileSize} in the frontend:
 * 1024-based units up to TB, at most one decimal, German decimal and grouping separators.
 */
public final class ByteSizes {

  private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

  private ByteSizes() {}

  /** {@code 512 B}, {@code 2 KB}, {@code 1,5 MB}, {@code 700 MB}, {@code 1.024 TB}. */
  public static String format(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double value = bytes / 1024.0;
    int unit = 0;
    while (value >= 1024 && unit < UNITS.length - 1) {
      value /= 1024;
      unit++;
    }
    return new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.GERMANY))
            .format(value)
        + " "
        + UNITS[unit];
  }
}
