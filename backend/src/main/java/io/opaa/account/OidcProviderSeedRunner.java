package io.opaa.account;

import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.OidcProviderSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Triggers the two bootstrap seeds once at start-up - {@link LocalAdminSeeder#seedIfNeeded()}
 * first, then {@link OidcProviderSeeder#seedIfNeeded()} - and then loads {@link
 * OidcProviderRegistry} so the first bearer token never pays for the build. Called by {@code
 * io.opaa.installation.InstallationStartup} before the web server starts, the seeds only when the
 * installation is completed (ADR-0060); the transactional work lives in the seeder beans, because
 * {@code @Transactional} only applies through a bean's own proxy.
 *
 * <p>The order is a contract (ADR-0033, Entscheidung 5): the provider takeover writes {@code
 * OidcProviderSeedMarker}, and that marker is what tells the local seed an existing installation
 * from a fresh one. Run the other way round, every fresh installation with {@code OPAA_OIDC_*} set
 * would get an {@code INVITED} bootstrap administrator instead of a live one. Before the server
 * starts, because a first sign-in before the takeover would provision the initial administrator's
 * account as a regular user, for good (ADR-0025, Entscheidung 3).
 *
 * <p>A {@link DataIntegrityViolationException} is the one benign outcome (a second process seeded
 * first - {@code chk_oidc_provider_seed_marker_singleton}); anything else is left to propagate.
 */
@Component
public class OidcProviderSeedRunner {

  private static final Logger log = LoggerFactory.getLogger(OidcProviderSeedRunner.class);

  private final LocalAdminSeeder localAdminSeeder;
  private final OidcProviderSeeder seeder;
  private final OidcProviderRegistry registry;

  public OidcProviderSeedRunner(
      LocalAdminSeeder localAdminSeeder, OidcProviderSeeder seeder, OidcProviderRegistry registry) {
    this.localAdminSeeder = localAdminSeeder;
    this.seeder = seeder;
    this.registry = registry;
  }

  public void runStartupSeeds() {
    try {
      localAdminSeeder.seedIfNeeded();
    } catch (DataIntegrityViolationException e) {
      log.warn(
          "Notanker-Konto der Systemverwaltung konnte nicht angelegt werden - vermutlich hat eine"
              + " andere Instanz die Anlage bereits durchgeführt",
          e);
    }
    try {
      seeder.seedIfNeeded();
    } catch (DataIntegrityViolationException e) {
      log.warn(
          "Identitätsanbieter konnte nicht aus der Umgebung übernommen werden - vermutlich hat"
              + " eine andere Instanz die Übernahme bereits durchgeführt",
          e);
    }
    registry.refresh();
  }
}
