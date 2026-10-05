package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountUsability.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The states of ADR-0041, Entscheidung 4, one per row of its table. */
class AccountUsabilityTest {

  private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
  private static final String ENABLED_ISSUER = "https://idp.example/realms/enabled";
  private static final String DISABLED_ISSUER = "https://idp.example/realms/disabled";

  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final List<OidcProvider> providerRows = new ArrayList<>();
  private OidcProvider enabledProvider;
  private OidcProvider localRow;
  private AccountUsability usability;

  @BeforeEach
  void setUp() {
    enabledProvider = provider("Enabled", ENABLED_ISSUER, true);
    providerRows.add(enabledProvider);
    providerRows.add(provider("Disabled", DISABLED_ISSUER, false));
    localRow = OidcProvider.localProvider("Lokale Konten");
    localRow.enable();
    providerRows.add(localRow);
    when(providers.findAllByOrderBySortOrderAscDisplayNameAsc()).thenReturn(providerRows);
    when(credentials.findById(any())).thenReturn(Optional.empty());
    usability = usability("oidc");
  }

  @Test
  void anAccountOfAnEnabledProviderIsUsable() {
    assertThat(usability.stateOf(oidcUser(ENABLED_ISSUER + "/"))).isEqualTo(State.USABLE);
  }

  @Test
  void anAccountOfADisabledProviderIsDormant() {
    State state = usability.stateOf(oidcUser(DISABLED_ISSUER));

    assertThat(state).isEqualTo(State.DORMANT_PROVIDER_DISABLED);
    assertThat(state.isDormant()).isTrue();
    assertThat(state.isDeactivated()).isFalse();
  }

  @Test
  void anAccountWithoutProviderIsDeactivated() {
    State state = usability.stateOf(oidcUser("https://idp.example/realms/deleted"));

    assertThat(state).isEqualTo(State.DEACTIVATED);
    assertThat(state.isDeactivated()).isTrue();
  }

  @Test
  void theDevIssuerCountsOnlyInTheDevMode() {
    User devUser = oidcUser("opaa-dev");

    assertThat(usability("dev").stateOf(devUser)).isEqualTo(State.USABLE);
    assertThat(usability.stateOf(devUser)).isEqualTo(State.DEACTIVATED);
  }

  @Test
  void aDirectoryLockOutranksAnEnabledProvider() {
    User user = oidcUser(ENABLED_ISSUER);
    user.lockFromDirectory(NOW.minusSeconds(60));

    assertThat(usability.stateOf(user)).isEqualTo(State.DEACTIVATED);
  }

  @Test
  void anActiveLocalAccountIsUsable() {
    User user = User.localAccount("aktiv@example.com", "Aktiv");
    localRow(user);

    assertThat(usability.stateOf(user)).isEqualTo(State.USABLE);
  }

  @Test
  void aLocalAccountLockedByTheAdministrationIsDeactivated() {
    User user = User.localAccount("gesperrt@example.com", "Gesperrt");
    localRow(user).lock(LockReason.ADMIN, NOW.minusSeconds(60), null);

    assertThat(usability.stateOf(user)).isEqualTo(State.DEACTIVATED);
  }

  /** Absence is no deactivation (#2260): the lock rests, also without a threshold asked. */
  @Test
  void aLocalAccountLockedForInactivityRests() {
    User user = User.localAccount("abwesend@example.com", "Abwesend");
    LocalCredentials row = localRow(user);
    row.lock(LockReason.INACTIVITY, NOW.minusSeconds(60), null);
    when(credentials.findAllById(any())).thenReturn(List.of(row));

    State state = usability.stateOf(user);

    assertThat(state).isEqualTo(State.DORMANT_INACTIVE);
    assertThat(state.isDormant()).isTrue();
    assertThat(state.isUsable()).isFalse();
    assertThat(usability.snapshot().deactivationsOf(List.of(user))).isEmpty();
  }

  @Test
  void aFailedLoginLockoutIsNoDeactivation() {
    User user = User.localAccount("vertippt@example.com", "Vertippt");
    localRow(user).lock(LockReason.FAILED_LOGINS, NOW.minusSeconds(60), NOW.plusSeconds(600));

    State state = usability.stateOf(user);

    assertThat(state).isEqualTo(State.LOCKED_OUT);
    assertThat(state.isDeactivated()).isFalse();
    assertThat(state.isUsable()).isFalse();
  }

  /** The expiry is read before the lock: a lockout would otherwise hide it. */
  @Test
  void anExpiredLocalAccountIsDeactivatedEvenDuringALockout() {
    User user = User.localAccount("befristet@example.com", "Befristet");
    LocalCredentials row = localRow(user);
    row.setExpiresAt(NOW.minusSeconds(3600), NOW);
    row.lock(LockReason.FAILED_LOGINS, NOW.minusSeconds(60), NOW.plusSeconds(600));

    assertThat(usability.stateOf(user)).isEqualTo(State.DEACTIVATED);
  }

  @Test
  void anOpenInvitationIsNotYetUsable() {
    User user = User.localAccount("eingeladen@example.com", "Eingeladen");
    LocalCredentials row = new LocalCredentials(user.getId(), "Testkonto", NOW);
    when(credentials.findById(user.getId())).thenReturn(Optional.of(row));

    State state = usability.stateOf(user);

    assertThat(state).isEqualTo(State.INVITED);
    assertThat(state.isDeactivated()).isFalse();
  }

  /** A regular local account rests while the local management is off; an administrator not. */
  @Test
  void theSwitchOfTheLocalManagementLetsRegularLocalAccountsRest() {
    localRow.disable();
    User regular = User.localAccount("regulaer@example.com", "Regulär");
    localRow(regular);
    User admin = User.localAccount("admin@example.com", "Admin");
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    localRow(admin);

    State state = usability.stateOf(regular);
    assertThat(state).isEqualTo(State.DORMANT_LOCAL_ACCOUNTS_DISABLED);
    assertThat(state.isDormant()).isTrue();
    assertThat(state.isDeactivated()).isFalse();
    assertThat(usability.stateOf(admin)).isEqualTo(State.USABLE);
  }

  @Test
  void aLocalAccountWithoutCredentialsIsDeactivated() {
    assertThat(usability.stateOf(User.localAccount("weg@example.com", "Weg")))
        .isEqualTo(State.DEACTIVATED);
  }

  @Test
  void inactivityIsReportedOnlyWhenAThresholdIsAsked() {
    User user = oidcUser(ENABLED_ISSUER);
    user.setLastLoginAt(NOW.minus(Duration.ofDays(91)));

    assertThat(usability.stateOf(user)).isEqualTo(State.USABLE);
    assertThat(usability.snapshot().withInactivityThreshold(Duration.ofDays(90)).stateOf(user))
        .isEqualTo(State.DORMANT_INACTIVE);
    assertThat(usability.snapshot().withInactivityThreshold(Duration.ofDays(120)).stateOf(user))
        .isEqualTo(State.USABLE);
  }

  /** Deactivation and a disabled provider outrank inactivity. */
  @Test
  void inactivityNeverMasksAStrongerState() {
    User dormant = oidcUser(DISABLED_ISSUER);
    dormant.setLastLoginAt(NOW.minus(Duration.ofDays(400)));
    User locked = oidcUser(ENABLED_ISSUER);
    locked.setLastLoginAt(NOW.minus(Duration.ofDays(400)));
    locked.lockFromDirectory(NOW);

    AccountUsability.Snapshot snapshot =
        usability.snapshot().withInactivityThreshold(Duration.ofDays(90));

    assertThat(snapshot.stateOf(dormant)).isEqualTo(State.DORMANT_PROVIDER_DISABLED);
    assertThat(snapshot.stateOf(locked)).isEqualTo(State.DEACTIVATED);
  }

  @Test
  void withoutAProviderItsAccountsAreNoLongerUsable() {
    User user = oidcUser(ENABLED_ISSUER);

    assertThat(usability.snapshotWithoutProvider(enabledProvider.getId()).stateOf(user))
        .isEqualTo(State.DORMANT_PROVIDER_DISABLED);
    assertThat(usability.snapshotWithoutProvider(null).stateOf(user)).isEqualTo(State.USABLE);
  }

  @Test
  void statesOfManyAccountsLoadTheLocalRowsAtOnce() {
    User local = User.localAccount("viele@example.com", "Viele");
    LocalCredentials row = activeRow(local);
    when(credentials.findAllById(any())).thenReturn(List.of(row));
    User oidc = oidcUser(DISABLED_ISSUER);

    assertThat(usability.snapshot().statesOf(List.of(local, oidc)))
        .containsEntry(local.getId(), State.USABLE)
        .containsEntry(oidc.getId(), State.DORMANT_PROVIDER_DISABLED);
  }

  private AccountUsability usability(String mode) {
    return new AccountUsability(
        credentials,
        providers,
        new AuthProperties(mode, null, null, null),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static User oidcUser(String issuer) {
    User user = new User("sub", issuer, null, "Person");
    user.setLastLoginAt(NOW);
    return user;
  }

  private LocalCredentials localRow(User user) {
    LocalCredentials row = activeRow(user);
    when(credentials.findById(user.getId())).thenReturn(Optional.of(row));
    return row;
  }

  private static LocalCredentials activeRow(User user) {
    user.setLastLoginAt(NOW);
    LocalCredentials row = new LocalCredentials(user.getId(), "Testkonto", NOW);
    row.markEmailVerified(NOW);
    row.setPasswordHash("{noop}irrelevant", NOW);
    return row;
  }

  private static OidcProvider provider(String name, String issuer, boolean enabled) {
    OidcProvider provider =
        new OidcProvider(name, issuer, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
    if (enabled) {
      provider.enable();
    } else {
      provider.disable();
    }
    return provider;
  }
}
