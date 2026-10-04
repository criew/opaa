package io.opaa.library;

import java.util.List;
import java.util.UUID;

/**
 * The private libraries of an organization as the administration sees them: every number rests on
 * persons and is masked by the minimum group size; nothing names a person or a library.
 *
 * @param quotaBytes the limit in force, {@code 0} for unlimited
 * @param runWindowDays how far back {@code runEnds} counts
 */
public record PrivateStorageSummary(
    long quotaBytes,
    MaskedNumber owners,
    MaskedNumber usedBytes,
    List<ProfileSum> profiles,
    int runWindowDays,
    List<RunEnds> runEnds) {

  /**
   * A number as the administration may see it: exact ({@code value}), "rests on fewer than {@code
   * fewerThanPersons} persons", or neither where it is not told.
   */
  public record MaskedNumber(Long value, Integer fewerThanPersons) {

    static MaskedNumber exact(long value) {
      return new MaskedNumber(value, null);
    }

    static MaskedNumber fewerThan(int persons) {
      return new MaskedNumber(null, persons);
    }

    static MaskedNumber untold() {
      return new MaskedNumber(null, null);
    }
  }

  /** The storage the private libraries on one profile admitting persons occupy. */
  public record ProfileSum(UUID profileId, String name, MaskedNumber usedBytes) {}

  /** How many runs of private libraries ended with one run category. */
  public record RunEnds(String category, MaskedNumber runs) {}
}
