package io.opaa.auth.oidc;

import io.opaa.auth.AuthProperties;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time takeover of the {@code OPAA_OIDC_*} configuration into the first provider row
 * (#1329, ADR-0025 Entscheidung 3): on the first start in the {@code oidc} mode, the configured
 * issuer becomes the enabled default provider named {@value #SEEDED_DISPLAY_NAME}, with the JWK set
 * override and client id taken over unchanged. Afterwards the database leads; the environment
 * variables are not read again.
 *
 * <p><b>Guarded by {@link OidcProviderSeedMarker}, never by "is the table empty?"</b> - the marker
 * is written in the same transaction as the seeded row. Two deliberate cases leave <em>no</em>
 * marker: the {@code dev} mode (which knows no providers - a later switch to {@code oidc} must
 * still be able to take the environment over) and an {@code oidc} start whose environment names no
 * issuer at all (the bootstrap of a fresh installation depends on this seed, so the operator is
 * told what to set and the next start tries again). Existing rows with no marker get the marker
 * without any seeding, mirroring {@code LlmModelSeeder}.
 *
 * <p><b>{@code OPAA_OIDC_BOOTSTRAP=force} is the documented way back</b> from a mistyped issuer of
 * the only provider: the marker is ignored once, the environment provider is restored - a row with
 * this issuer is overwritten with the environment values, enabled and made the default; otherwise
 * it is created - and the operator removes the variable again.
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, Kandidat zur Entfernung ab
 * v1.0 (der Wiederanlauf bleibt).
 */
@Component
class OidcProviderSeeder {

  private static final Logger log = LoggerFactory.getLogger(OidcProviderSeeder.class);

  static final String SEEDED_DISPLAY_NAME = "Verzeichnisdienst";
  private static final String OIDC_MODE = "oidc";

  private final OidcProviderRepository repository;
  private final OidcProviderSeedMarkerRepository markerRepository;
  private final AuthProperties authProperties;

  OidcProviderSeeder(
      OidcProviderRepository repository,
      OidcProviderSeedMarkerRepository markerRepository,
      AuthProperties authProperties) {
    this.repository = repository;
    this.markerRepository = markerRepository;
    this.authProperties = authProperties;
  }

  @Transactional
  void seedIfNeeded() {
    if (!OIDC_MODE.equals(authProperties.mode())) {
      return;
    }
    AuthProperties.OidcAuth oidc = authProperties.oidc();
    if (oidc.isBootstrapForced()) {
      forceBootstrap(oidc);
      return;
    }
    if (markerRepository.seedAlreadyAttempted()) {
      return;
    }
    if (repository.count() > 0) {
      log.info(
          "Übernahme der OPAA_OIDC_*-Konfiguration entfällt: Es sind bereits Identitätsanbieter"
              + " hinterlegt. Seed-Marker wird nachträglich gesetzt.");
      markerRepository.save(new OidcProviderSeedMarker(Instant.now()));
      return;
    }
    String issuer = requireIssuer(oidc);
    if (issuer == null) {
      return;
    }
    OidcProvider provider = environmentProvider(oidc, issuer);
    provider.markDefault();
    repository.save(provider);
    markerRepository.save(new OidcProviderSeedMarker(Instant.now()));
    log.info(
        "Identitätsanbieter „{}“ ({}) aus der OPAA_OIDC_*-Konfiguration übernommen; ab jetzt führt"
            + " die Anbieterverwaltung, die Umgebungsvariablen werden nicht mehr ausgewertet.",
        SEEDED_DISPLAY_NAME,
        issuer);
  }

  private void forceBootstrap(AuthProperties.OidcAuth oidc) {
    String issuer = requireIssuer(oidc);
    if (issuer == null) {
      return;
    }
    repository
        .findByDefaultProviderTrue()
        .filter(current -> !current.getIssuerUri().equals(issuer))
        .ifPresent(
            current -> {
              current.clearDefault();
              repository.saveAndFlush(current);
            });
    Optional<OidcProvider> existing = repository.findByIssuerUri(issuer);
    OidcProvider provider;
    if (existing.isPresent()) {
      provider = existing.get();
      provider.replaceDetails(
          provider.getDisplayName(),
          issuer,
          oidc.clientId(),
          oidc.jwkSetUri(),
          provider.getClaimMapping());
      provider.enable();
    } else {
      provider = environmentProvider(oidc, issuer);
    }
    provider.markDefault();
    repository.save(provider);
    if (!markerRepository.seedAlreadyAttempted()) {
      markerRepository.save(new OidcProviderSeedMarker(Instant.now()));
    }
    log.warn(
        "OPAA_OIDC_BOOTSTRAP=force: Identitätsanbieter „{}“ ({}) aus der Umgebung wiederhergestellt,"
            + " aktiviert und zum Standard gemacht. Die Variable jetzt wieder entfernen - jeder"
            + " weitere Start würde die Anbieterverwaltung erneut überschreiben.",
        provider.getDisplayName(),
        issuer);
  }

  private static OidcProvider environmentProvider(AuthProperties.OidcAuth oidc, String issuer) {
    String authority = OidcIssuerUris.normalize(oidc.authority());
    if (authority != null && !authority.isBlank() && !authority.equals(issuer)) {
      log.warn(
          "OPAA_OIDC_AUTHORITY ({}) weicht von OPAA_OIDC_ISSUER_URI ({}) ab; der Issuer ist"
              + " zugleich die Authority des Anmeldeflusses und wird übernommen, die Authority"
              + " verworfen.",
          authority,
          issuer);
    }
    return new OidcProvider(
        SEEDED_DISPLAY_NAME,
        issuer,
        oidc.clientId(),
        oidc.jwkSetUri(),
        OidcClaimMapping.keycloakDefaults());
  }

  private static String requireIssuer(AuthProperties.OidcAuth oidc) {
    String issuer = OidcIssuerUris.normalize(oidc.issuerUri());
    if (issuer == null || issuer.isBlank()) {
      log.error(
          "Kein Identitätsanbieter hinterlegt und OPAA_OIDC_ISSUER_URI nicht gesetzt: Bis ein"
              + " Anbieter existiert, ist keine Anmeldung möglich. OPAA_OIDC_ISSUER_URI,"
              + " OPAA_OIDC_CLIENT_ID (und bei Bedarf OPAA_OIDC_JWK_SET_URI) setzen und neu"
              + " starten - die Übernahme wird dann nachgeholt. Siehe"
              + " docs/handbuch/deployment.md.");
      return null;
    }
    return issuer;
  }
}
