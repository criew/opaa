package io.opaa.connection.profile;

/**
 * A number of persons' connections as the administration may see it: exactly one of {@link
 * #count()} and {@link #fewerThan()} is set. Only {@link PersonNumbers} creates one, so no number
 * about persons reaches the administration unmasked.
 */
public final class PersonCount {

  private final Long count;
  private final Integer fewerThan;

  private PersonCount(Long count, Integer fewerThan) {
    this.count = count;
    this.fewerThan = fewerThan;
  }

  static PersonCount exact(long count) {
    return new PersonCount(count, null);
  }

  static PersonCount fewerThan(int size) {
    return new PersonCount(null, size);
  }

  /** The exact number, {@code null} where only {@link #fewerThan()} may be told. */
  public Long count() {
    return count;
  }

  /** The bound the number lies below, {@code null} for an exact one. */
  public Integer fewerThan() {
    return fewerThan;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PersonCount that
        && java.util.Objects.equals(count, that.count)
        && java.util.Objects.equals(fewerThan, that.fewerThan);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(count, fewerThan);
  }

  @Override
  public String toString() {
    return count != null ? count.toString() : "<" + fewerThan;
  }
}
