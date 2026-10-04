package io.opaa.connection.profile;

import java.util.Objects;

/**
 * A number of persons' connections as the administration may see it: exactly one of {@link
 * #count()}, {@link #fewerThan()} and {@link #atLeast()} is set. Only {@link PersonNumbers} creates
 * one, so no number about persons reaches the administration unmasked.
 */
public final class PersonCount {

  private final Long count;
  private final Integer fewerThan;
  private final Integer atLeast;

  private PersonCount(Long count, Integer fewerThan, Integer atLeast) {
    this.count = count;
    this.fewerThan = fewerThan;
    this.atLeast = atLeast;
  }

  static PersonCount exact(long count) {
    return new PersonCount(count, null, null);
  }

  static PersonCount fewerThan(int size) {
    return new PersonCount(null, size, null);
  }

  static PersonCount atLeast(int size) {
    return new PersonCount(null, null, size);
  }

  /** The exact number, {@code null} where only a bound may be told. */
  public Long count() {
    return count;
  }

  /** The bound the number lies below, {@code null} unless that is all that may be told. */
  public Integer fewerThan() {
    return fewerThan;
  }

  /** The bound the number reaches, {@code null} unless that is all that may be told. */
  public Integer atLeast() {
    return atLeast;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PersonCount that
        && Objects.equals(count, that.count)
        && Objects.equals(fewerThan, that.fewerThan)
        && Objects.equals(atLeast, that.atLeast);
  }

  @Override
  public int hashCode() {
    return Objects.hash(count, fewerThan, atLeast);
  }

  @Override
  public String toString() {
    if (count != null) {
      return count.toString();
    }
    return fewerThan != null ? "<" + fewerThan : ">=" + atLeast;
  }
}
