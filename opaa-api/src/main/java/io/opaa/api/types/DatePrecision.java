package io.opaa.api.types;

/**
 * How much of a stored document date is actually known: a full day, only the month, or only the
 * year (a "Fassung 2024"). The stored date is padded to the first day of the unknown part.
 */
public enum DatePrecision {
  DAY,
  MONTH,
  YEAR
}
