package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.permission.GroupSizeProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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

  static Stream<Arguments> warnings() {
    return Stream.of(
        // connected, expired, warning at the threshold 10
        Arguments.of(6, 9, false),
        Arguments.of(6, 10, true),
        Arguments.of(0, 12, true),
        // untold expired: 13 in all with 1 to 4 connected admits 9 expired
        Arguments.of(1, 12, false),
        // 15 in all with 1 to 4 connected admits no fewer than 11
        Arguments.of(1, 14, true),
        Arguments.of(0, 4, false));
  }

  @ParameterizedTest(name = "{0} connected + {1} expired -> warning {2}")
  @MethodSource("warnings")
  void theWarningFollowsTheLeastNumberOfExpiredTheAnswerAdmits(
      long connected, long expired, boolean warning) {
    assertThat(TestPersonCounts.of(connected, expired).expiredWarning()).isEqualTo(warning);
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
}
