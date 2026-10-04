package io.opaa.permission;

import org.springframework.stereotype.Component;

/**
 * The one counting basis for numbers about persons the administration sees (ADR-0036,
 * Mindestgruppengröße N): a number resting on fewer than N persons - zero included - is told only
 * as "fewer than N", never exactly. A number about private libraries rests on their owners, not on
 * the libraries.
 */
@Component
public class PersonThreshold {

  private final int minimum;

  public PersonThreshold(GroupSizeProperties groupSize) {
    this.minimum = groupSize.minimumGroupSize();
  }

  /** The minimum group size N. */
  public int minimum() {
    return minimum;
  }

  /** Whether a number resting on {@code persons} persons may be told exactly. */
  public boolean discloses(long persons) {
    return persons >= minimum;
  }

  /**
   * Whether a part of a number may be told exactly: it rests on {@code part} of the {@code whole}
   * persons, and both the part and the persons outside it are none or at least N, so its difference
   * to the whole points at no person either.
   */
  public boolean disclosesPart(long part, long whole) {
    long rest = whole - part;
    return discloses(part) && (rest == 0 || discloses(rest));
  }
}
