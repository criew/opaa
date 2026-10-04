package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.connection.profile.PersonNumbers.PersonsCounts;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Under the minimum group size 5 no answer, and no difference of two answers, points at fewer than
 * five persons: the total is masked below 5, zero included, and the expired part is exact only
 * where the total is and neither it nor the connected rest lies between 1 and 4. Numbers per group
 * of persons (per sign-in provider) split the same totals and are therefore only ever "at least 5".
 */
class PersonNumbersTest {

  private static final int N = 5;
  private static final UUID PROFILE = UUID.randomUUID();
  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

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

  static Stream<Arguments> groups() {
    return Stream.of(
        // persons' connections of provider A, of provider B, of everyone else -> A shown, B shown
        Arguments.of(6, 2, 0, "-", "-"),
        Arguments.of(4, 4, 0, "-", "-"),
        Arguments.of(20, 2, 0, ">=5", "-"),
        Arguments.of(5, 5, 0, "-", "-"),
        Arguments.of(5, 5, 5, "-", "-"),
        Arguments.of(5, 5, 10, ">=5", ">=5"),
        Arguments.of(9, 0, 0, "-", "-"),
        Arguments.of(10, 0, 0, ">=5", "-"),
        Arguments.of(0, 0, 0, "-", "-"));
  }

  @ParameterizedTest(name = "{0} + {1} + {2} others -> {3} / {4}")
  @MethodSource("groups")
  void aGroupIsAtMostAtLeastFiveAndOnlyWhileFiveRemainForTheRest(
      long a, long b, long others, String aShown, String bShown) {
    World world = new World();
    world.set(a, b, others);

    Map<UUID, PersonsCounts> shown = world.groups();

    assertThat(shown(shown.get(A))).isEqualTo(aShown);
    assertThat(shown(shown.get(B))).isEqualTo(bShown);
  }

  /**
   * Acceptance criterion of #2251 with two providers and one profile, all persons' connections on
   * it: over every distribution of the connections, the answers of the profile and of both
   * providers together never single out a number between 0 and 4 for either provider - whichever
   * such value is possible for one observation, all of them are.
   */
  @Test
  void noCombinationOfProfileAndProviderAnswersIsolatesANumberBelowFive() {
    World world = new World();
    Map<String, Set<Long>> possibleA = new HashMap<>();
    Map<String, Set<Long>> possibleB = new HashMap<>();
    int bound = 3 * N;
    for (long a = 0; a <= 2 * bound; a++) {
      for (long b = 0; b <= 2 * bound; b++) {
        for (long others = 0; others <= 2 * bound; others++) {
          if (a + b + others > bound) {
            // every alternative of an observation with this total lies within the enumeration
            continue;
          }
          world.set(a, b, others);
          ProfileCounts profile = world.profile();
          Map<UUID, PersonsCounts> providers = world.groups();
          String observation =
              profile.total()
                  + "|"
                  + profile.expired()
                  + "|"
                  + shown(providers.get(A))
                  + "|"
                  + shown(providers.get(B));
          possibleA.computeIfAbsent(observation, key -> new TreeSet<>()).add(a);
          possibleB.computeIfAbsent(observation, key -> new TreeSet<>()).add(b);
        }
      }
    }

    assertThat(possibleA).hasSizeGreaterThan(10);
    possibleA.forEach(
        (observation, values) ->
            assertThat(belowN(values)).as("A after %s", observation).isIn(Set.of(), allBelowN()));
    possibleB.forEach(
        (observation, values) ->
            assertThat(belowN(values)).as("B after %s", observation).isIn(Set.of(), allBelowN()));
  }

  private static Set<Long> belowN(Set<Long> values) {
    Set<Long> below = new TreeSet<>();
    values.stream().filter(value -> value < N).forEach(below::add);
    return below;
  }

  private static Set<Long> allBelowN() {
    Set<Long> all = new TreeSet<>();
    for (long value = 0; value < N; value++) {
      all.add(value);
    }
    return all;
  }

  private static String shown(PersonsCounts counts) {
    return Objects.toString(counts.connections(), "-");
  }

  /** One distribution of persons' connections: one stand-in person per group, all on PROFILE. */
  private static final class World implements PersonConnections {
    private final Map<UUID, Long> connections = new HashMap<>();
    private final PersonNumbers numbers = TestPersonCounts.numbersOver(this);

    void set(long a, long b, long others) {
      connections.put(A, a);
      connections.put(B, b);
      connections.put(OTHER, others);
    }

    ProfileCounts profile() {
      return numbers.countsOf(List.of(PROFILE)).get(PROFILE);
    }

    Map<UUID, PersonsCounts> groups() {
      return numbers.ofGroups(Map.of(A, List.of(A), B, List.of(B)), List.of(OTHER));
    }

    @Override
    public Map<UUID, StateCounts> countsAmong(Collection<UUID> profileIds) {
      long total = connections.values().stream().mapToLong(Long::longValue).sum();
      return Map.of(PROFILE, new StateCounts(total, 0));
    }

    @Override
    public PersonTotals totalsOf(Collection<UUID> userIds) {
      return new PersonTotals(
          userIds.stream().mapToLong(id -> connections.getOrDefault(id, 0L)).sum(), 0);
    }

    @Override
    public void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId) {}
  }
}
