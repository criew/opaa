package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import io.opaa.auth.local.LocalAdminSeeder;
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

  private final LocalAdminSeeder localAdminSeeder = mock(LocalAdminSeeder.class);
  private final OidcProviderSeeder seeder = mock(OidcProviderSeeder.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private final OidcProviderSeedRunner runner =
      new OidcProviderSeedRunner(localAdminSeeder, seeder, registry);

  /**
   * The bootstrap administrator is seeded <em>before</em> the provider takeover (ADR-0033,
   * Entscheidung 5): the takeover writes its marker, and that marker is what tells the local seed
   * an existing installation from a fresh one - in the wrong order every fresh installation with
   * {@code OPAA_OIDC_*} set would get an {@code INVITED} account instead of a live one.
   */
  @Test
  void seedsTheLocalAdministratorThenTheProviderThenLoadsTheRegistryBeforeAnyRequest() {
    assertThat(runner).isInstanceOf(SmartInitializingSingleton.class);

    runner.afterSingletonsInstantiated();

    InOrder order = inOrder(localAdminSeeder, seeder, registry);
    order.verify(localAdminSeeder).seedIfNeeded();
    order.verify(seeder).seedIfNeeded();
    order.verify(registry).refresh();
  }

  @Test
  void aLocalSeedAnotherInstanceWonStillLetsTheProviderTakeoverAndTheRegistryRun() {
    doThrow(new DataIntegrityViolationException("chk_local_admin_seed_marker_singleton"))
        .when(localAdminSeeder)
        .seedIfNeeded();

    runner.afterSingletonsInstantiated();

    org.mockito.Mockito.verify(seeder).seedIfNeeded();
    org.mockito.Mockito.verify(registry).refresh();
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
