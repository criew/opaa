package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link OidcProviderSeedRunner}: seeds, then loads the registry - and does so as a {@link
 * SmartInitializingSingleton}, i.e. before the web server accepts its first request, so the initial
 * administrator cannot sign in before the default provider exists.
 */
class OidcProviderSeedRunnerTest {

  private final OidcProviderSeeder seeder = mock(OidcProviderSeeder.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private final OidcProviderSeedRunner runner = new OidcProviderSeedRunner(seeder, registry);

  @Test
  void seedsBeforeTheRegistryIsLoadedAndBeforeAnyRequestCanArrive() {
    assertThat(runner).isInstanceOf(SmartInitializingSingleton.class);

    runner.afterSingletonsInstantiated();

    InOrder order = inOrder(seeder, registry);
    order.verify(seeder).seedIfNeeded();
    order.verify(registry).refresh();
  }

  @Test
  void aSeedAnotherInstanceWonIsLoggedAndTheRegistryIsStillLoaded() {
    doThrow(new DataIntegrityViolationException("chk_oidc_provider_seed_marker_singleton"))
        .when(seeder)
        .seedIfNeeded();

    runner.afterSingletonsInstantiated();

    org.mockito.Mockito.verify(registry).refresh();
  }
}
