package io.opaa.auth.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link OidcProviderSeeder#seedIfNeeded()} once at startup, before any request is served,
 * and then loads {@link OidcProviderRegistry} so the first bearer token never pays for the build -
 * the same thin shape as {@code LlmModelSeedRunner}: the transactional work lives in the seeder
 * bean, because {@code @Transactional} only applies through that bean's own proxy.
 *
 * <p>A {@link DataIntegrityViolationException} is the one benign outcome (a second process seeded
 * first - {@code chk_oidc_provider_seed_marker_singleton}); anything else is left to propagate.
 */
@Component
public class OidcProviderSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(OidcProviderSeedRunner.class);

  private final OidcProviderSeeder seeder;
  private final OidcProviderRegistry registry;

  public OidcProviderSeedRunner(OidcProviderSeeder seeder, OidcProviderRegistry registry) {
    this.seeder = seeder;
    this.registry = registry;
  }

  @Override
  public void run(ApplicationArguments args) {
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
