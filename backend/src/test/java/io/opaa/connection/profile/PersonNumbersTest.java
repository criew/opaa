package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.connection.profile.PersonNumbers.OwnerSplit;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.permission.GroupSizeProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Under the minimum group size 5 no answer, and no difference of two answers, points at fewer than
 * five persons: the total is masked below 5, zero included, and the expired part is exact only
 * where it and the connected rest are each at least 5 - a rest of none answers like a rest of a
 * few.
 */
class PersonNumbersTest {

  static Stream<Arguments> counts() {
    return Stream.of(
        // connected, expired, total shown, expired shown ("-" for not told)
        Arguments.of(0, 0, "<5", "<5"),
        Arguments.of(0, 1, "<5", "<5"),
        Arguments.of(1, 0, "<5", "<5"),
        Arguments.of(1, 1, "<5", "<5"),
        Arguments.of(4, 0, "<5", "<5"),
        Arguments.of(2, 2, "<5", "<5"),
        Arguments.of(0, 3, "<5", "<5"),
        // below 2N-1 a few connected and a few expired overlap: the part is never told
        Arguments.of(0, 5, "5", "-"),
        Arguments.of(5, 0, "5", "-"),
        Arguments.of(1, 5, "6", "-"),
        Arguments.of(5, 1, "6", "-"),
        Arguments.of(0, 7, "7", "-"),
        Arguments.of(4, 5, "9", "-"),
        Arguments.of(0, 9, "9", "-"),
        Arguments.of(5, 4, "9", "<5"),
        Arguments.of(9, 0, "9", "<5"),
        Arguments.of(5, 5, "10", "5"),
        Arguments.of(6, 5, "11", "5"),
        Arguments.of(0, 10, "10", "-"),
        Arguments.of(10, 0, "10", "<5"));
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

  /**
   * Regression guard for #2270: on the same total, none connected answers like one to four
   * connected, and none expired like one to four expired - the part, the total and the warning.
   */
  @Test
  void noneAnswersLikeAFewOnEitherSide() {
    for (long total = 0; total <= 30; total++) {
      for (long few = 1; few <= Math.min(4, total); few++) {
        assertThat(answer(few, total - few))
            .as("%d in all, %d connected", total, few)
            .isEqualTo(answer(0, total));
        assertThat(answer(total - few, few))
            .as("%d in all, %d expired", total, few)
            .isEqualTo(answer(total, 0));
      }
    }
  }

  private static String answer(long connected, long expired) {
    ProfileCounts counts = TestPersonCounts.of(connected, expired);
    return counts.total() + "/" + counts.expired() + "/" + counts.expiredWarning();
  }

  static Stream<Arguments> warnings() {
    return Stream.of(
        // connected, expired, warning at the threshold 10
        Arguments.of(6, 9, false),
        Arguments.of(6, 10, true),
        // untold expired: 12 in all with 0 to 4 connected admits up to 12 expired
        Arguments.of(0, 12, true),
        Arguments.of(4, 8, true),
        Arguments.of(1, 12, true),
        // untold on a total below 2N-1: any split of 8 admits at most 8
        Arguments.of(0, 8, false),
        // untold at exactly the threshold: 10 in all admits 10 expired
        Arguments.of(4, 6, true),
        Arguments.of(0, 9, false),
        // "fewer than 5" expired admits at most 4
        Arguments.of(12, 3, false),
        Arguments.of(0, 4, false));
  }

  @ParameterizedTest(name = "{0} connected + {1} expired -> warning {2}")
  @MethodSource("warnings")
  void theWarningFollowsTheGreatestNumberOfExpiredTheAnswerAdmits(
      long connected, long expired, boolean warning) {
    assertThat(TestPersonCounts.of(connected, expired).expiredWarning()).isEqualTo(warning);
  }

  /** Below the minimum group size the answer admits N-1 expired, so a threshold up to it warns. */
  @Test
  void aThresholdBelowTheMinimumWarnsOnEveryAnswerThatAdmitsIt() {
    PersonNumbers lowThreshold =
        new PersonNumbers(
            TestPersonCounts.NO_PERSONS,
            new GroupSizeProperties(5),
            new ExpiredConnectionWarningProperties(4));

    assertThat(lowThreshold.mask(new PersonConnections.StateCounts(0, 0)).expiredWarning())
        .isTrue();
    assertThat(lowThreshold.mask(new PersonConnections.StateCounts(4, 0)).expiredWarning())
        .isTrue();
    assertThat(lowThreshold.mask(new PersonConnections.StateCounts(10, 0)).expiredWarning())
        .isTrue();
    assertThat(lowThreshold.mask(new PersonConnections.StateCounts(5, 3)).expiredWarning())
        .as("8 in all, untold, admits 8")
        .isTrue();
  }

  /**
   * A warning is exactly whether the greatest admitted number reaches the threshold - below N, at
   * N, at 2N-1 and at the default.
   */
  @ParameterizedTest(name = "threshold {0}")
  @ValueSource(ints = {4, 5, 9, 10})
  void theWarningSaysNothingTheAnswerDoesNot(int warningThreshold) {
    PersonNumbers numbers =
        new PersonNumbers(
            TestPersonCounts.NO_PERSONS,
            new GroupSizeProperties(5),
            new ExpiredConnectionWarningProperties(warningThreshold));
    for (long connected = 0; connected <= 30; connected++) {
      for (long expired = 0; expired <= 30; expired++) {
        ProfileCounts counts = numbers.mask(new PersonConnections.StateCounts(connected, expired));
        long admitted = mostExpiredAdmittedBy(counts);
        assertThat(counts.expiredWarning())
            .as("%d connected and %d expired, at most %d admitted", connected, expired, admitted)
            .isEqualTo(admitted >= warningThreshold);
      }
    }
  }

  /** A threshold below N is kept, but said at start: every profile for persons then warns. */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void aThresholdBelowTheMinimumIsLoggedAtStart(CapturedOutput output) {
    new PersonNumbers(
        TestPersonCounts.NO_PERSONS,
        new GroupSizeProperties(5),
        new ExpiredConnectionWarningProperties(4));
    assertThat(output.getAll()).contains("WARN").contains("below the minimum group size");

    int before = output.getAll().length();
    TestPersonCounts.numbersOver(TestPersonCounts.NO_PERSONS);
    assertThat(output.getAll().substring(before)).doesNotContain("below the minimum group size");
  }

  /** What the shown numbers alone admit: an exact part, else N-1 below N, else the total. */
  private static long mostExpiredAdmittedBy(ProfileCounts counts) {
    if (counts.expired() != null && counts.expired().count() != null) {
      return counts.expired().count();
    }
    if (counts.expired() != null) {
      return counts.expired().fewerThan() - 1;
    }
    return counts.total().count();
  }

  @Test
  void twoStatesWithTheSameAnswerCarryTheSameWarning() {
    PersonNumbers lowThreshold =
        new PersonNumbers(
            TestPersonCounts.NO_PERSONS,
            new GroupSizeProperties(5),
            new ExpiredConnectionWarningProperties(3));
    for (PersonNumbers numbers : List.of(TestPersonCounts.numbers(), lowThreshold)) {
      Map<String, Boolean> warningByAnswer = new HashMap<>();
      for (long connected = 0; connected <= 30; connected++) {
        for (long expired = 0; expired <= 30; expired++) {
          ProfileCounts counts =
              numbers.mask(new PersonConnections.StateCounts(connected, expired));
          String answer = counts.total() + "/" + counts.expired();
          Boolean before = warningByAnswer.putIfAbsent(answer, counts.expiredWarning());
          assertThat(before == null || before == counts.expiredWarning())
              .as("answer %s for %d connected and %d expired", answer, connected, expired)
              .isTrue();
        }
      }
    }
  }

  static Stream<Arguments> privateLibraries() {
    return Stream.of(
        // libraries, owners, owners of every other private library, shown ("-" for not told)
        Arguments.of(0, 0, 0, "<5"),
        Arguments.of(1, 1, 0, "<5"),
        Arguments.of(9, 4, 0, "<5"),
        Arguments.of(3, 3, 9, "<5"),
        Arguments.of(5, 5, 0, "-"),
        Arguments.of(5, 5, 1, "-"),
        Arguments.of(8, 6, 4, "-"),
        Arguments.of(5, 5, 5, "5"),
        Arguments.of(12, 5, 7, "12"));
  }

  /**
   * A number of private libraries rests on their owners, not on the libraries themselves; a part of
   * the organization's is exact only where its owners and the owners of the rest are each at least
   * five - a rest of none answers like a rest of one.
   */
  @ParameterizedTest(name = "{0} libraries of {1} owners, {2} other owners -> {3}")
  @MethodSource("privateLibraries")
  void aNumberOfPrivateLibrariesIsMaskedByItsOwners(
      long libraries, long owners, long otherOwners, String shown) {
    PersonCount count =
        TestPersonCounts.numbers()
            .privateLibraries(libraries, List.of(new OwnerSplit(owners, otherOwners)));

    assertThat(count == null ? "-" : count.toString()).isEqualTo(shown);
  }
}
