package io.opaa.common;

import java.time.Duration;

/**
 * Normalization of configuration bounds in the compact constructors of {@code *Properties} records.
 * An absent value binds as {@code null} or {@code 0}, so for {@code positiveOr} and {@code
 * notNegativeOr} {@code 0} means "the default" rather than "none" - which {@code @DefaultValue}
 * cannot express, since it only applies to an absent key. {@code positiveOr} also replaces a
 * negative value, {@code notNegativeOr} refuses it. {@code requireNotNegative} leaves zero to the
 * caller, where it typically means "unbounded".
 */
public final class PropertyDefaults {

  private PropertyDefaults() {}

  /** {@code value} if positive, otherwise {@code fallback}. */
  public static int positiveOr(int value, int fallback) {
    return value > 0 ? value : fallback;
  }

  /** {@code value} if positive, otherwise {@code fallback}. */
  public static long positiveOr(long value, long fallback) {
    return value > 0 ? value : fallback;
  }

  /** {@code value} if present and positive, otherwise {@code fallback}. */
  public static Duration positiveOr(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  /** {@code value} if positive, {@code fallback} for zero; a negative value is refused. */
  public static int notNegativeOr(String name, int value, int fallback) {
    requireNotNegative(name, value);
    return value == 0 ? fallback : value;
  }

  /** {@code value} if positive, {@code fallback} for zero; a negative value is refused. */
  public static long notNegativeOr(String name, long value, long fallback) {
    requireNotNegative(name, value);
    return value == 0 ? fallback : value;
  }

  /** Refuses a negative {@code value}, naming the property {@code name}. */
  public static void requireNotNegative(String name, long value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative, got " + value);
    }
  }
}
