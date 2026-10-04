package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Under the minimum group size 5 no answer, and no difference of two answers, points at fewer than
 * five persons: the total is masked below 5, zero included, and the expired part is exact only
 * where the total is and neither it nor the connected rest lies between 1 and 4.
 */
class PersonNumbersTest {

  static Stream<Arguments> counts() {
    return Stream.of(
        // connected, expired, total shown, expired shown ("-" for not told)
        Arguments.of(0, 0, "<5", "<5"),
        Arguments.of(1, 0, "<5", "<5"),
        Arguments.of(4, 0, "<5", "<5"),
        Arguments.of(5, 0, "5", "0"),
        Arguments.of(5, 1, "6", "<5"),
        Arguments.of(1, 5, "6", "-"),
        Arguments.of(6, 5, "11", "5"),
        Arguments.of(0, 3, "<5", "<5"),
        Arguments.of(0, 7, "7", "7"),
        Arguments.of(2, 2, "<5", "<5"));
  }

  @ParameterizedTest(name = "{0} connected + {1} expired -> {2} / {3}")
  @MethodSource("counts")
  void neitherAnAnswerNorADifferenceOfTwoPointsAtAPerson(
      long connected, long expired, String total, String expiredShown) {
    ProfileCounts counts = TestPersonCounts.of(connected, expired);

    assertThat(counts.total().toString()).isEqualTo(total);
    assertThat(counts.expired() == null ? "-" : counts.expired().toString())
        .isEqualTo(expiredShown);
  }

  static Stream<Arguments> privateLibraries() {
    return Stream.of(
        // libraries, owners, owners in the organization, shown ("-" for not told)
        Arguments.of(0, 0, 0, "<5"),
        Arguments.of(1, 1, 1, "<5"),
        Arguments.of(9, 4, 4, "<5"),
        Arguments.of(5, 5, 5, "5"),
        Arguments.of(12, 5, 5, "12"),
        Arguments.of(3, 3, 9, "<5"),
        Arguments.of(5, 5, 10, "5"),
        Arguments.of(5, 5, 6, "-"),
        Arguments.of(8, 6, 9, "-"));
  }

  /**
   * A number of private libraries rests on their owners, not on the libraries themselves; a part of
   * the organization's is exact only where the owners outside it are none or at least five, so its
   * difference to the organization's number points at no person either.
   */
  @ParameterizedTest(name = "{0} libraries of {1} of {2} owners -> {3}")
  @MethodSource("privateLibraries")
  void aNumberOfPrivateLibrariesIsMaskedByItsOwners(
      long libraries, long owners, long organizationOwners, String shown) {
    PersonCount count =
        TestPersonCounts.numbers().privateLibraries(libraries, owners, organizationOwners);

    assertThat(count == null ? "-" : count.toString()).isEqualTo(shown);
  }
}
