package io.opaa.auth;

import io.opaa.api.types.ProviderType;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
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
 * still be able to take the environment over) and an {@code oidc} start whose environment is
 * incomplete or malformed - no issuer, an issuer that is no http(s) address, or no client id (the
 * bootstrap of a fresh installation depends on this seed, and a row seeded from such values would
 * be an undeletable default provider nobody can sign in through; so the operator is told what to
 * set and the next start tries again). Existing rows with no marker get the marker without any
 * seeding, mirroring {@code LlmModelSeeder}.
 *
 * <p>How loud an unseeded start is depends on whether anyone can still sign in: with a
 * login-capable local system administrator ({@link LocalAdminAvailabilityGuard}) no issuer is INFO
 * - operation with local accounts only - and an incomplete or malformed one is WARN; without one it
 * is ERROR.
 *
 * <p><b>{@code OPAA_OIDC_BOOTSTRAP=force}</b> restores the environment provider once despite the
 * marker - a row with this issuer is overwritten with the environment values, enabled and made the
 * default; otherwise it is created. Since ADR-0033 (Entscheidung 5) the way back from a mistyped
 * provider is the sign-in as local system administrator ({@code OPAA_LOCAL_ADMIN_RESET=force}
 * restores that account); this variable keeps working until {@value #BOOTSTRAP_FORCE_REMOVAL_DATE}
 * and warns about its replacement on every use. The LOCAL row of the local account management is no
 * identity provider: only OIDC rows count as "already there".
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, Kandidat zur Entfernung ab
 * v1.0; {@code OPAA_OIDC_BOOTSTRAP=force} entfällt am {@value #BOOTSTRAP_FORCE_REMOVAL_DATE}.
 */
@Component
public class OidcProviderSeeder {

  private static final Logger log = LoggerFactory.getLogger(OidcProviderSeeder.class);

  static final String SEEDED_DISPLAY_NAME = "Verzeichnisdienst";
  static final String BOOTSTRAP_FORCE_REMOVAL_DATE = "31.03.2027";
  private static final String OIDC_MODE = "oidc";
  private static final String NO_SIGN_IN_MESSAGE =
      "Kein Identitätsanbieter übernommen ({}): Bis ein Anbieter existiert, ist keine Anmeldung"
          + " möglich. OPAA_OIDC_ISSUER_URI und OPAA_OIDC_CLIENT_ID (und bei Bedarf"
          + " OPAA_OIDC_JWK_SET_URI) setzen und neu starten - die Übernahme wird dann"
          + " nachgeholt. Siehe docs/handbuch/deployment.md.";

  private final OidcProviderRepository repository;
  private final OidcProviderSeedMarkerRepository markerRepository;
  private final AuthProperties authProperties;
  private final LocalAdminAvailabilityGuard adminAvailability;

  OidcProviderSeeder(
      OidcProviderRepository repository,
      OidcProviderSeedMarkerRepository markerRepository,
      AuthProperties authProperties,
      LocalAdminAvailabilityGuard adminAvailability) {
    this.repository = repository;
    this.markerRepository = markerRepository;
    this.authProperties = authProperties;
    this.adminAvailability = adminAvailability;
  }

  @Transactional
  public void seedIfNeeded() {
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
    if (repository.countByProviderType(ProviderType.OIDC) > 0) {
      log.info(
          "Übernahme der OPAA_OIDC_*-Konfiguration entfällt: Es sind bereits Identitätsanbieter"
              + " hinterlegt. Seed-Marker wird nachträglich gesetzt.");
      markerRepository.save(new OidcProviderSeedMarker(Instant.now()));
      return;
    }
    String problem = bootstrapProblem(oidc);
    if (problem != null) {
      reportUnseededStart(oidc, problem);
      return;
    }
    String issuer = oidc.issuerUri().trim();
    OidcProvider provider = environmentProvider(oidc, issuer);
    provider.markDefault();
    // The bootstrap provider is this installation's own directory, not another house's - the
    // constructor's default is the safe one for every provider the administration adds later
    // (ADR-0036, Entscheidung 2).
    provider.setExternal(false);
    repository.save(provider);
    markerRepository.save(new OidcProviderSeedMarker(Instant.now()));
    log.info(
        "Identitätsanbieter „{}“ ({}) aus der OPAA_OIDC_*-Konfiguration übernommen; ab jetzt führt"
            + " die Anbieterverwaltung, die Umgebungsvariablen werden nicht mehr ausgewertet.",
        SEEDED_DISPLAY_NAME,
        issuer);
  }

  private void forceBootstrap(AuthProperties.OidcAuth oidc) {
    log.warn(
        "OPAA_OIDC_BOOTSTRAP=force ist veraltet und entfällt am {}: Der Weg zurück aus einer"
            + " Anbieter-Fehlkonfiguration ist die Anmeldung als lokaler Systemverwalter;"
            + " OPAA_LOCAL_ADMIN_RESET=force stellt dessen Konto wieder her (ADR-0033).",
        BOOTSTRAP_FORCE_REMOVAL_DATE);
    String problem = bootstrapProblem(oidc);
    if (problem != null) {
      log.error(NO_SIGN_IN_MESSAGE, problem);
      return;
    }
    String issuer = oidc.issuerUri().trim();
    String key = OidcIssuerUris.normalize(issuer);
    repository
        .findByDefaultProviderTrue()
        .filter(current -> !OidcIssuerUris.normalize(current.getIssuerUri()).equals(key))
        .ifPresent(
            current -> {
              current.clearDefault();
              repository.saveAndFlush(current);
            });
    Optional<OidcProvider> existing = repository.findByNormalizedIssuerUri(key);
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
    provider.setExternal(false);
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
    if (authority != null
        && !authority.isBlank()
        && !authority.equals(OidcIssuerUris.normalize(issuer))) {
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

  /**
   * Why the environment cannot seed a provider anyone can sign in through, or {@code null} when it
   * can - then the trimmed {@code OPAA_OIDC_ISSUER_URI} is the issuer exactly as configured. An
   * unset variable binds to the empty string, so blank means unset.
   */
  private static String bootstrapProblem(AuthProperties.OidcAuth oidc) {
    String issuer = trimmed(oidc.issuerUri());
    if (issuer.isEmpty()) {
      return "OPAA_OIDC_ISSUER_URI ist nicht gesetzt";
    }
    if (trimmed(oidc.clientId()).isEmpty()) {
      return "OPAA_OIDC_CLIENT_ID ist nicht gesetzt";
    }
    try {
      OidcIssuerUris.requireHttpUri(issuer, "OPAA_OIDC_ISSUER_URI");
      return null;
    } catch (ValidationException e) {
      return e.getMessage();
    }
  }

  /**
   * Tells the operator about a start that seeded nothing. ERROR only when no one can sign in at
   * all; with a login-capable local administrator the installation runs on local accounts - no
   * issuer is a deliberate choice (INFO), a half-set one a misconfiguration (WARN).
   */
  private void reportUnseededStart(AuthProperties.OidcAuth oidc, String problem) {
    if (adminAvailability.countLoginCapableSystemAdmins(Organization.DEFAULT_ID) == 0) {
      log.error(NO_SIGN_IN_MESSAGE, problem);
    } else if (trimmed(oidc.issuerUri()).isEmpty()) {
      log.info(
          "Kein Identitätsanbieter konfiguriert ({}): Anmeldung nur mit lokalen Konten. Einen"
              + " Anbieter in der Anbieterverwaltung anlegen oder OPAA_OIDC_ISSUER_URI und"
              + " OPAA_OIDC_CLIENT_ID setzen und neu starten - die Übernahme wird dann nachgeholt.",
          problem);
    } else {
      log.warn(
          "Kein Identitätsanbieter übernommen ({}): Anmeldung bis dahin nur mit lokalen Konten."
              + " OPAA_OIDC_ISSUER_URI und OPAA_OIDC_CLIENT_ID (und bei Bedarf"
              + " OPAA_OIDC_JWK_SET_URI) korrigieren und neu starten - die Übernahme wird dann"
              + " nachgeholt. Siehe docs/handbuch/deployment.md.",
          problem);
    }
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }
}
