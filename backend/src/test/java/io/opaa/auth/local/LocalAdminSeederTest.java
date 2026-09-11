package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalAdminSeeder.Outcome;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.auth.oidc.OidcProviderSeedMarkerRepository;
import io.opaa.auth.oidc.OidcProvidersChangedEvent;
import io.opaa.organization.Organization;
import io.opaa.security.PasswordGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link LocalAdminSeeder} (ADR-0033, Entscheidung 5) against mocked repositories: the two cases of
 * the first start (fresh installation with a live password, existing installation as {@code
 * INVITED}), the rejected default address, the marker that stops every later start, the {@code
 * oidc}-only rule, and the restart with {@code OPAA_LOCAL_ADMIN_RESET=force}. The one-time password
 * appears in exactly one log event of the seeder and nowhere else - not in another line and not in
 * the audit payload.
 */
class LocalAdminSeederTest {

  private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");
  private static final String EMAIL = "it-postfach@stadt.example";
  private static final String GENERATED = "Hx7kQm2pWv9Rt4Zn6bCd";

  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalRefreshTokenRepository refreshTokens = mock(LocalRefreshTokenRepository.class);
  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final OidcProviderSeedMarkerRepository oidcMarker =
      mock(OidcProviderSeedMarkerRepository.class);
  private final LocalAdminSeedMarkerRepository marker = mock(LocalAdminSeedMarkerRepository.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final PasswordGenerator generator = mock(PasswordGenerator.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(credentials.save(any(LocalCredentials.class))).thenAnswer(inv -> inv.getArgument(0));
    when(providers.save(any(OidcProvider.class))).thenAnswer(inv -> inv.getArgument(0));
    when(providers.findLocalRow()).thenReturn(Optional.empty());
    when(users.findByIssuerAndEmailIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(credentials.findByBootstrapTrue()).thenReturn(Optional.empty());
    when(encoder.encode(anyString())).thenAnswer(inv -> "{bcrypt}hash-of-" + inv.getArgument(0));
    when(generator.generate()).thenReturn(GENERATED);
    when(audit.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(LocalAdminSeeder.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(LocalAdminSeeder.class)).detachAppender(logs);
  }

  private LocalAdminSeeder seeder(String mode, String email, String envPassword, String reset) {
    AuthProperties auth = new AuthProperties(mode, null, null, email);
    LocalAuthProperties local =
        new LocalAuthProperties(
            "seeder-test-secret-0123456789-abcdefghij",
            null,
            null,
            null,
            null,
            null,
            null,
            envPassword,
            reset,
            null);
    return new LocalAdminSeeder(
        auth,
        local,
        users,
        credentials,
        refreshTokens,
        providers,
        oidcMarker,
        marker,
        encoder,
        generator,
        audit,
        events,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private LocalAdminSeeder freshInstallationSeeder() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    when(oidcMarker.seedAlreadyAttempted()).thenReturn(false);
    when(users.count()).thenReturn(0L);
    return seeder("oidc", EMAIL, "", "");
  }

  private List<String> loggedMessages() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private AuditEvent recordedAudit() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordSystemProcessAction(captor.capture());
    return captor.getValue();
  }

  @Test
  void aFreshInstallationGetsTheLocalRowAndALiveBootstrapAdminWithAGeneratedPassword() {
    Outcome outcome = freshInstallationSeeder().seedIfNeeded();

    assertThat(outcome).isEqualTo(Outcome.SEEDED_ACTIVE);
    ArgumentCaptor<OidcProvider> row = ArgumentCaptor.forClass(OidcProvider.class);
    verify(providers).save(row.capture());
    assertThat(row.getValue().isLocal()).isTrue();
    assertThat(row.getValue().isEnabled()).isFalse();
    assertThat(row.getValue().isDefaultProvider()).isFalse();
    assertThat(row.getValue().getIssuerUri()).isEqualTo(LocalIssuer.URN);
    assertThat(row.getValue().getDisplayName())
        .isEqualTo(LocalAdminSeeder.LOCAL_PROVIDER_DISPLAY_NAME);

    ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
    verify(users).saveAndFlush(user.capture());
    assertThat(user.getValue().getIssuer()).isEqualTo(LocalIssuer.URN);
    assertThat(user.getValue().getSubject()).isEqualTo(user.getValue().getId().toString());
    assertThat(user.getValue().getEmail()).isEqualTo(EMAIL);
    assertThat(user.getValue().getDisplayName()).isEqualTo(LocalAdminSeeder.DISPLAY_NAME);
    assertThat(user.getValue().getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);

    ArgumentCaptor<LocalCredentials> creds = ArgumentCaptor.forClass(LocalCredentials.class);
    verify(credentials).save(creds.capture());
    LocalCredentials saved = creds.getValue();
    assertThat(saved.isBootstrap()).isTrue();
    assertThat(saved.getCreatedReason()).isEqualTo(LocalAdminSeeder.CREATED_REASON);
    assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}hash-of-" + GENERATED);
    assertThat(saved.isPasswordChangeRequired()).isTrue();
    assertThat(saved.getPasswordChangeReason()).isEqualTo(PasswordChangeReason.INITIAL);
    assertThat(saved.getEmailVerifiedAt()).isEqualTo(NOW);
    assertThat(saved.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);

    verify(marker).save(any(LocalAdminSeedMarker.class));
    verify(events).publishEvent(any(OidcProvidersChangedEvent.class));
    AuditEvent event = recordedAudit();
    assertThat(event.eventType()).isEqualTo(AuditEventType.LOCAL_ADMIN_SEEDED);
    assertThat(event.actorRef()).isEqualTo(LocalRefreshTokenService.SYSTEM_ACTOR);
    assertThat(event.after()).containsEntry("state", "ACTIVE");
  }

  @Test
  void theGeneratedPasswordStandsInExactlyOneLogEventAndNowhereElse() {
    freshInstallationSeeder().seedIfNeeded();

    List<String> withPassword =
        loggedMessages().stream().filter(message -> message.contains(GENERATED)).toList();
    assertThat(withPassword).hasSize(1);
    assertThat(withPassword.getFirst()).contains(EMAIL).contains("einmalig");
    assertThat(loggedMessages().stream().filter(m -> !m.contains(GENERATED)))
        .noneMatch(message -> message.contains(GENERATED));
    AuditEvent event = recordedAudit();
    assertThat(event.after().toString()).doesNotContain(GENERATED);
    assertThat(String.valueOf(event.before())).doesNotContain(GENERATED);
    assertThat(String.valueOf(event.reason())).doesNotContain(GENERATED);
  }

  @Test
  void anEnvironmentPasswordIsUsedWithoutAForcedChangeAndWithoutTheLogBlock() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    when(oidcMarker.seedAlreadyAttempted()).thenReturn(false);
    when(users.count()).thenReturn(0L);

    Outcome outcome = seeder("oidc", EMAIL, "ci-passwort-aus-der-umgebung", "").seedIfNeeded();

    assertThat(outcome).isEqualTo(Outcome.SEEDED_ACTIVE);
    ArgumentCaptor<LocalCredentials> creds = ArgumentCaptor.forClass(LocalCredentials.class);
    verify(credentials).save(creds.capture());
    assertThat(creds.getValue().getPasswordHash())
        .isEqualTo("{bcrypt}hash-of-ci-passwort-aus-der-umgebung");
    assertThat(creds.getValue().isPasswordChangeRequired()).isFalse();
    verify(generator, never()).generate();
    assertThat(loggedMessages()).noneMatch(m -> m.contains("ci-passwort-aus-der-umgebung"));
  }

  @Test
  void anExistingInstallationGetsAnInvitedBootstrapAdminWithoutAPasswordOrALogBlock() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    when(oidcMarker.seedAlreadyAttempted()).thenReturn(true);
    when(users.count()).thenReturn(0L);

    Outcome outcome = seeder("oidc", EMAIL, "", "").seedIfNeeded();

    assertThat(outcome).isEqualTo(Outcome.SEEDED_INVITED);
    ArgumentCaptor<LocalCredentials> creds = ArgumentCaptor.forClass(LocalCredentials.class);
    verify(credentials).save(creds.capture());
    assertThat(creds.getValue().getPasswordHash()).isNull();
    assertThat(creds.getValue().isBootstrap()).isTrue();
    assertThat(creds.getValue().state(NOW)).isEqualTo(LocalAccountState.INVITED);
    verify(generator, never()).generate();
    verify(marker).save(any(LocalAdminSeedMarker.class));
    assertThat(loggedMessages()).noneMatch(m -> m.contains(GENERATED));
    assertThat(recordedAudit().after()).containsEntry("state", "INVITED");
  }

  @Test
  void anInstallationWithAccountsCountsAsExistingEvenWithoutTheOidcMarker() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    when(oidcMarker.seedAlreadyAttempted()).thenReturn(false);
    when(users.count()).thenReturn(3L);

    assertThat(seeder("oidc", EMAIL, "", "").seedIfNeeded()).isEqualTo(Outcome.SEEDED_INVITED);
  }

  @Test
  void theShippedDefaultAddressIsRejectedWithoutAMarkerSoTheNextStartTriesAgain() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    when(oidcMarker.seedAlreadyAttempted()).thenReturn(false);
    when(users.count()).thenReturn(0L);

    Outcome outcome = seeder("oidc", "admin@opaa.local", "", "").seedIfNeeded();

    assertThat(outcome).isEqualTo(Outcome.REJECTED);
    verify(users, never()).saveAndFlush(any());
    verify(providers, never()).save(any());
    verify(marker, never()).save(any());
    assertThat(loggedMessages())
        .anyMatch(m -> m.contains("OPAA_INITIAL_ADMIN_EMAIL") && m.contains("admin@opaa.local"));
  }

  @Test
  void aBlankAddressIsRejectedTheSameWay() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);

    assertThat(seeder("oidc", "  ", "", "").seedIfNeeded()).isEqualTo(Outcome.REJECTED);
    assertThat(seeder("oidc", "kein-postfach", "", "").seedIfNeeded()).isEqualTo(Outcome.REJECTED);
    verify(marker, never()).save(any());
    assertThat(loggedMessages()).anyMatch(m -> m.contains("OPAA_INITIAL_ADMIN_EMAIL"));
  }

  @Test
  void aSecondStartSeedsNothingOnceTheMarkerExists() {
    when(marker.seedAlreadyAttempted()).thenReturn(true);

    assertThat(seeder("oidc", EMAIL, "", "").seedIfNeeded()).isEqualTo(Outcome.SKIPPED);
    verify(users, never()).saveAndFlush(any());
    verify(credentials, never()).save(any());
    verify(marker, never()).save(any());
  }

  @Test
  void neverSeedsInTheDevModeAndLeavesNoMarker() {
    assertThat(seeder("dev", EMAIL, "", "").seedIfNeeded()).isEqualTo(Outcome.SKIPPED);
    verify(marker, never()).save(any());
    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void anExistingBootstrapRowWithoutAMarkerOnlyGetsTheMarker() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    LocalCredentials existing = new LocalCredentials(UUID.randomUUID(), "vorhanden", NOW);
    existing.markBootstrap();
    when(credentials.findByBootstrapTrue()).thenReturn(Optional.of(existing));

    assertThat(seeder("oidc", EMAIL, "", "").seedIfNeeded()).isEqualTo(Outcome.SKIPPED);
    verify(marker).save(any(LocalAdminSeedMarker.class));
    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void theForcedResetRestoresALockedExpiredAndDemotedBootstrapAccountAndEndsItsSessions() {
    when(marker.seedAlreadyAttempted()).thenReturn(true);
    User user = User.localAccount(EMAIL, LocalAdminSeeder.DISPLAY_NAME);
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(SystemRole.USER);
    LocalCredentials row = new LocalCredentials(user.getId(), "alt", NOW.minusSeconds(3600));
    row.markBootstrap();
    row.setPasswordHash("{bcrypt}alt", NOW.minusSeconds(3600));
    row.lock(LockReason.ADMIN, NOW.minusSeconds(60), null);
    row.setExpiresAt(NOW.minusSeconds(1), NOW.minusSeconds(60));
    when(credentials.findByBootstrapTrue()).thenReturn(Optional.of(row));
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(providers.findLocalRow())
        .thenReturn(Optional.of(OidcProvider.localProvider("Lokale Konten")));

    Outcome outcome = seeder("oidc", EMAIL, "", "force").seedIfNeeded();

    assertThat(outcome).isEqualTo(Outcome.RESET);
    assertThat(row.getLockedAt()).isNull();
    assertThat(row.getExpiresAt()).isNull();
    assertThat(row.getPasswordHash()).isEqualTo("{bcrypt}hash-of-" + GENERATED);
    assertThat(row.isPasswordChangeRequired()).isTrue();
    assertThat(row.getPasswordChangeReason()).isEqualTo(PasswordChangeReason.INITIAL);
    assertThat(row.getPasswordInvalidatedBefore()).isEqualTo(NOW);
    assertThat(row.getEmailVerifiedAt()).isNotNull();
    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(user.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    verify(refreshTokens).revokeAllForUser(user.getId(), RevocationReason.ADMIN, NOW);
    verify(providers, never()).save(any());
    AuditEvent event = recordedAudit();
    assertThat(event.eventType()).isEqualTo(AuditEventType.LOCAL_ADMIN_RESET);
    assertThat(loggedMessages().stream().filter(m -> m.contains(GENERATED))).hasSize(1);
    assertThat(loggedMessages()).anyMatch(m -> m.contains("OPAA_LOCAL_ADMIN_RESET"));
  }

  @Test
  void theForcedResetRecreatesADeletedBootstrapAccountWithTheConfiguredAddress() {
    when(marker.seedAlreadyAttempted()).thenReturn(true);
    when(credentials.findByBootstrapTrue()).thenReturn(Optional.empty());

    Outcome outcome = seeder("oidc", EMAIL, "", "FORCE").seedIfNeeded();

    assertThat(outcome).isEqualTo(Outcome.RESET);
    ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
    verify(users).saveAndFlush(user.capture());
    assertThat(user.getValue().getEmail()).isEqualTo(EMAIL);
    assertThat(user.getValue().getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    ArgumentCaptor<LocalCredentials> creds = ArgumentCaptor.forClass(LocalCredentials.class);
    verify(credentials).save(creds.capture());
    assertThat(creds.getValue().isBootstrap()).isTrue();
    assertThat(creds.getValue().state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    verify(providers).save(any(OidcProvider.class));
    verify(marker, never()).save(any());
  }

  @Test
  void theForcedResetWithAnEnvironmentPasswordForcesNoChange() {
    when(marker.seedAlreadyAttempted()).thenReturn(true);
    when(credentials.findByBootstrapTrue()).thenReturn(Optional.empty());

    seeder("oidc", EMAIL, "ci-passwort-aus-der-umgebung", "force").seedIfNeeded();

    ArgumentCaptor<LocalCredentials> creds = ArgumentCaptor.forClass(LocalCredentials.class);
    verify(credentials).save(creds.capture());
    assertThat(creds.getValue().isPasswordChangeRequired()).isFalse();
    assertThat(loggedMessages()).noneMatch(m -> m.contains("ci-passwort-aus-der-umgebung"));
  }

  @Test
  void theForcedResetOfADeletedAccountStillRejectsTheDefaultAddress() {
    when(marker.seedAlreadyAttempted()).thenReturn(true);
    when(credentials.findByBootstrapTrue()).thenReturn(Optional.empty());

    assertThat(seeder("oidc", "admin@opaa.local", "", "force").seedIfNeeded())
        .isEqualTo(Outcome.REJECTED);
    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void aLocalAccountAlreadyHoldingTheAddressIsAConflictNotASecondBootstrap() {
    when(marker.seedAlreadyAttempted()).thenReturn(false);
    when(oidcMarker.seedAlreadyAttempted()).thenReturn(false);
    when(users.count()).thenReturn(1L);
    when(users.findByIssuerAndEmailIgnoreCase(eq(LocalIssuer.URN), anyString()))
        .thenReturn(Optional.of(User.localAccount(EMAIL, "Jemand")));

    assertThat(seeder("oidc", EMAIL, "", "").seedIfNeeded()).isEqualTo(Outcome.REJECTED);
    verify(marker, never()).save(any());
    verify(users, never()).saveAndFlush(any());
  }
}
