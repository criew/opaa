package io.opaa.connection.account;

/**
 * A number of persons' connections as the administration sees it: exact at zero and from {@code
 * minimumGroupSize} on, below it only "fewer than" that size, so a count never points at a person
 * (ADR-0036, Mindestgruppengröße). Exactly one of the two is set.
 */
public record PersonCount(Long count, Integer fewerThan) {

  public PersonCount {
    if ((count == null) == (fewerThan == null)) {
      throw new IllegalArgumentException("a person count is exact or below a size, never both");
    }
  }

  public static PersonCount of(long count, int minimumGroupSize) {
    return count == 0 || count >= minimumGroupSize
        ? new PersonCount(count, null)
        : new PersonCount(null, minimumGroupSize);
  }
}
