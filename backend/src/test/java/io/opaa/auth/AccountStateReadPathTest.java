package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The acceptance criterion of #1818 that no assertion about a feature can hold: <b>the account
 * state history has no second read path</b> (ADR-0036, Entscheidung 8; Personalrat, zweite
 * Sichtung). A "Verlauf" beside the Stichtag reconstruction would turn the evidence of a block into
 * a per-person timeline anybody with the user list could page through, which is exactly what the
 * pseudonymised audit trail is built to avoid.
 *
 * <p>Both halves are read off the production wiring: which beans can reach the repository at all,
 * and what the one service that holds it hands out. Neither is a list this test keeps in step by
 * hand.
 *
 * <p><b>The limit, stated rather than hidden:</b> a bean that fetched the repository from the
 * context by name instead of declaring it as a dependency would escape the first half, and a caller
 * that reconstructed a chain from repeated {@code isBlocked} calls would escape the second. Neither
 * is reachable without writing code that looks exactly like what this test forbids.
 */
@OpaaIntegrationTest
class AccountStateReadPathTest {

  /**
   * The two beans the repository legitimately belongs to: the one service that owns the state, and
   * the retention deletion that sweeps every history table through {@code
   * List<PermissionHistorySweeper>}.
   */
  private static final Set<String> EXPECTED_HOLDERS =
      Set.of("accountStateService", "permissionHistoryRetentionDeletionService");

  @Autowired private ConfigurableApplicationContext context;

  @Test
  void onlyTheAccountStateServiceAndTheRetentionSweepReachTheHistoryRepository() {
    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
    String[] repositoryBeans = beanFactory.getBeanNamesForType(AccountStateHistoryRepository.class);

    assertThat(repositoryBeans)
        .as("the repository bean is what the dependency graph below is read from")
        .hasSize(1);
    assertThat(beanFactory.getDependentBeans(repositoryBeans[0]))
        .as(
            "a further bean holding AccountStateHistoryRepository is a second read path on the"
                + " account state history - ADR-0036, Entscheidung 8 allows exactly one")
        .containsExactlyInAnyOrderElementsOf(EXPECTED_HOLDERS);
  }

  /**
   * The second half: the one service that legitimately holds the repository must not hand a history
   * row out either. Its answers are the current state ({@code boolean}, {@code Set<UUID>}) and
   * whether a change happened - never an interval a caller could render as a timeline.
   */
  @Test
  void theAccountStateServiceHandsOutNoHistoryRow() {
    List<String> leaking =
        Arrays.stream(AccountStateService.class.getMethods())
            .filter(method -> method.getDeclaringClass() == AccountStateService.class)
            .filter(AccountStateReadPathTest::mentionsAHistoryRow)
            .map(Method::getName)
            .sorted()
            .toList();

    assertThat(leaking)
        .as(
            "these methods of AccountStateService pass AccountStateHistory out of the service -"
                + " the Stichtag reconstruction of #1822 is the one reader ADR-0036, Entscheidung"
                + " 8 foresees, with its own authorisation and its own retrieval event")
        .isEmpty();
  }

  private static boolean mentionsAHistoryRow(Method method) {
    return Stream.concat(
            Stream.of(method.getGenericReturnType()),
            Arrays.stream(method.getGenericParameterTypes()))
        .anyMatch(AccountStateReadPathTest::isOrCarriesAHistoryRow);
  }

  /**
   * Walks the type rather than matching its name: {@code AccountStateHistoryCause} starts with the
   * row type's name, so a substring test would call every {@code apply} a leak.
   */
  private static boolean isOrCarriesAHistoryRow(Type type) {
    if (type instanceof Class<?> clazz) {
      return clazz.isArray()
          ? isOrCarriesAHistoryRow(clazz.getComponentType())
          : clazz.equals(AccountStateHistory.class);
    }
    if (type instanceof ParameterizedType parameterized) {
      return Stream.concat(
              Stream.of(parameterized.getRawType()),
              Arrays.stream(parameterized.getActualTypeArguments()))
          .anyMatch(AccountStateReadPathTest::isOrCarriesAHistoryRow);
    }
    if (type instanceof WildcardType wildcard) {
      return Stream.concat(
              Arrays.stream(wildcard.getUpperBounds()), Arrays.stream(wildcard.getLowerBounds()))
          .anyMatch(AccountStateReadPathTest::isOrCarriesAHistoryRow);
    }
    if (type instanceof GenericArrayType array) {
      return isOrCarriesAHistoryRow(array.getGenericComponentType());
    }
    return false;
  }
}
