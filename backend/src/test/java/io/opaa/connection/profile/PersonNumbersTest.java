package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.connection.profile.PersonNumbers.PersonsCounts;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import java.util.ArrayList;
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
 * of persons (per sign-in provider) split the same totals and are therefore only ever "at least 5",
 * and only where every group has five.
 */
class PersonNumbersTest {

  private static final int N = 5;

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
        Arguments.of(20, 2, 0, "-", "-"),
        Arguments.of(20, 20, 4, "-", "-"),
        Arguments.of(5, 5, 5, ">=5", ">=5"),
        Arguments.of(10, 0, 0, "-", "-"),
        Arguments.of(0, 0, 0, "-", "-"));
  }

  @ParameterizedTest(name = "{0} + {1} + {2} others -> {3} / {4}")
  @MethodSource("groups")
  void groupsAreToldAtLeastFiveOnlyWhileEachOfThemHasFive(
      long a, long b, long others, String aShown, String bShown) {
    World world = new World(2, 1, true);
    world.cells[0] = a;
    world.cells[1] = b;
    world.cells[2] = others;

    List<String> shown = world.providersShown();

    assertThat(shown).containsExactly(aShown, bShown);
  }

  /**
   * Acceptance criterion of #2251: two providers and one profile, the persons outside any provider
   * unknown to the administration.
   */
  @Test
  void twoProvidersAndAProfileIsolateNoNumberBelowFive() {
    assertNothingBelowFiveIsIsolated(new World(2, 1, true), 3 * N + N - 1);
  }

  /** Three providers and two profiles, with every person known to belong to a provider. */
  @Test
  void threeProvidersAndTwoProfilesIsolateNoNumberBelowFive() {
    assertNothingBelowFiveIsIsolated(new World(3, 2, false), 3 * N + 2 * (N - 1));
  }

  /**
   * Six providers and one profile, every person known to belong to a provider: neither the total 25
   * nor any other singles out a zero.
   */
  @Test
  void sixProvidersAndAProfileIsolateNoNumberBelowFive() {
    assertNothingBelowFiveIsIsolated(new World(6, 1, false), 6 * N + 1);
  }

  /**
   * Enumerates every distribution of at most {@code bound} connections over the cells of {@code
   * world} and groups them by what the administration sees: each profile's answer and each
   * provider's. For every observation whose alternatives all lie within the bound, and every
   * provider, the possible numbers below N are none or all of them - no answer, and no combination
   * of answers, tells 0 from 1 or any other number below N.
   */
  private static void assertNothingBelowFiveIsIsolated(World world, int bound) {
    Map<String, List<Set<Long>>> possible = new HashMap<>();
    Map<String, Long> budget = new HashMap<>();
    enumerate(world, 0, bound, possible, budget);

    assertThat(possible).hasSizeGreaterThan(10);
    possible.forEach(
        (observation, perProvider) -> {
          if (budget.get(observation) > bound) {
            return;
          }
          for (int provider = 0; provider < perProvider.size(); provider++) {
            assertThat(belowN(perProvider.get(provider)))
                .as("provider %d after %s", provider, observation)
                .isIn(Set.of(), allBelowN());
          }
        });
  }

  private static void enumerate(
      World world,
      int cell,
      long left,
      Map<String, List<Set<Long>>> possible,
      Map<String, Long> budget) {
    if (cell == world.cells.length) {
      String observation = world.observation();
      List<Set<Long>> perProvider =
          possible.computeIfAbsent(
              observation,
              key -> {
                List<Set<Long>> sets = new ArrayList<>();
                for (int provider = 0; provider < world.providers; provider++) {
                  sets.add(new TreeSet<>());
                }
                return sets;
              });
      for (int provider = 0; provider < world.providers; provider++) {
        perProvider.get(provider).add(world.providerTotal(provider));
      }
      budget.putIfAbsent(observation, world.largestTotal());
      return;
    }
    for (long value = 0; value <= left; value++) {
      world.cells[cell] = value;
      enumerate(world, cell + 1, left - value, possible, budget);
    }
    world.cells[cell] = 0;
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

  /**
   * One distribution of persons' connections: per profile one cell for each provider's stand-in
   * person and, with {@code rest}, one for a person outside any provider.
   */
  private static final class World implements PersonConnections {
    private final int providers;
    private final int groups;
    private final boolean rest;
    private final List<UUID> profiles = new ArrayList<>();
    private final List<UUID> persons = new ArrayList<>();
    private final long[] cells;
    private final PersonNumbers numbers = TestPersonCounts.numbersOver(this);

    World(int providers, int profileCount, boolean rest) {
      this.providers = providers;
      this.rest = rest;
      this.groups = providers + (rest ? 1 : 0);
      for (int profile = 0; profile < profileCount; profile++) {
        profiles.add(UUID.randomUUID());
      }
      for (int group = 0; group < groups; group++) {
        persons.add(UUID.randomUUID());
      }
      this.cells = new long[groups * profileCount];
    }

    long providerTotal(int provider) {
      return groupTotal(provider);
    }

    private long groupTotal(int group) {
      long total = 0;
      for (int profile = 0; profile < profiles.size(); profile++) {
        total += cells[profile * groups + group];
      }
      return total;
    }

    private long profileTotal(int profile) {
      long total = 0;
      for (int group = 0; group < groups; group++) {
        total += cells[profile * groups + group];
      }
      return total;
    }

    /** The most connections any distribution with the same profile answers may hold. */
    long largestTotal() {
      long largest = 0;
      for (int profile = 0; profile < profiles.size(); profile++) {
        long total = profileTotal(profile);
        largest += total < N ? N - 1 : total;
      }
      return largest;
    }

    List<String> providersShown() {
      Map<Integer, List<UUID>> byProvider = new HashMap<>();
      for (int provider = 0; provider < providers; provider++) {
        byProvider.put(provider, List.of(persons.get(provider)));
      }
      List<UUID> others = rest ? List.of(persons.get(providers)) : List.of();
      Map<Integer, PersonsCounts> told = numbers.ofGroups(byProvider, others);
      List<String> shown = new ArrayList<>();
      for (int provider = 0; provider < providers; provider++) {
        shown.add(Objects.toString(told.get(provider).connections(), "-"));
      }
      return shown;
    }

    String observation() {
      StringBuilder observation = new StringBuilder();
      Map<UUID, ProfileCounts> answers = numbers.countsOf(profiles);
      for (UUID profile : profiles) {
        ProfileCounts counts = answers.get(profile);
        observation.append(counts.total()).append('/').append(counts.expired()).append('|');
      }
      return observation.append(providersShown()).toString();
    }

    @Override
    public Map<UUID, StateCounts> countsAmong(Collection<UUID> profileIds) {
      Map<UUID, StateCounts> counts = new HashMap<>();
      for (int profile = 0; profile < profiles.size(); profile++) {
        counts.put(profiles.get(profile), new StateCounts(profileTotal(profile), 0));
      }
      return counts;
    }

    @Override
    public PersonTotals totalsOf(Collection<UUID> userIds) {
      long total = 0;
      for (UUID userId : userIds) {
        total += groupTotal(persons.indexOf(userId));
      }
      return new PersonTotals(total, 0);
    }

    @Override
    public void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId) {}
  }
}
