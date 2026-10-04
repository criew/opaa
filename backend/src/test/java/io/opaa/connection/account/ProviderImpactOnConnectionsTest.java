package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.AccountUsability.State;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.ProviderConnectionsImpact.Impact;
import io.opaa.auth.ProviderConnectionsImpact.MaskedCount;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.PersonConnections;
import io.opaa.connection.profile.TestPersonCounts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The numbers the provider administration sees before switching a provider off or deleting it: over
 * the provider's accounts that are not deactivated already, grouped per provider, and only ever "at
 * least 5"; the confirmation is asked wherever persons may have connections at all.
 */
class ProviderImpactOnConnectionsTest {

  private static final String ISSUER = "https://idp.example.org/realms/haus";
  private static final String PARTNER_ISSUER = "https://partner.example.org/realms/p";

  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AccountUsability usability = mock(AccountUsability.class);
  private final AccountUsability.Snapshot snapshot = mock(AccountUsability.Snapshot.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final RecordingTotals totals = new RecordingTotals();
  private final ProviderImpactOnConnections impact =
      new ProviderImpactOnConnections(
          providers, users, usability, profiles, TestPersonCounts.numbersOver(totals));

  private final OidcProvider house = provider("Haus", ISSUER + "/");
  private final OidcProvider partner = provider("Partner", PARTNER_ISSUER);
  private final OidcProvider local = OidcProvider.localProvider("Lokale Konten");
  private final List<User> accounts = new ArrayList<>();
  private final Map<UUID, State> states = new HashMap<>();

  @BeforeEach
  void providersAndAccounts() {
    for (OidcProvider each : List.of(house, partner, local)) {
      when(providers.findById(each.getId())).thenReturn(Optional.of(each));
    }
    when(providers.findAll()).thenReturn(List.of(house, partner, local));
    when(users.findAll()).thenReturn(accounts);
    when(usability.snapshot()).thenReturn(snapshot);
    when(snapshot.statesOf(any())).thenReturn(states);
  }

  @Test
  void onlyTheProvidersAccountsThatAreNotDeactivatedCountForIt() {
    UUID usable = account(ISSUER, State.USABLE, 30);
    UUID resting = account(ISSUER + "/", State.DORMANT_PROVIDER_DISABLED, 0);
    UUID deactivated = account(ISSUER, State.DEACTIVATED, 0);
    UUID partnerAccount = account(PARTNER_ISSUER, State.USABLE, 5);
    UUID regularLocal = account(LocalIssuer.URN, State.USABLE, 6);
    UUID localAdmin = account(LocalIssuer.URN, State.USABLE, 7);
    accounts.getLast().setSystemRole(SystemRole.SYSTEM_ADMIN);

    Impact result = impact.of(house.getId());

    assertThat(totals.asked)
        .contains(
            Set.of(usable, resting),
            Set.of(partnerAccount),
            Set.of(regularLocal),
            Set.of(deactivated, localAdmin));
    assertThat(result.connections()).isEqualTo(new MaskedCount(null, null, 5));
    assertThat(result.privateLibraries()).isNull();
    assertThat(result.confirmationRequired()).isTrue();
  }

  /** The 0-rounding: nothing told, and still a confirmation wherever a profile admits persons. */
  @Test
  void theConfirmationHangsOnProfilesAdmittingPersonsNotOnTheNumbers() {
    account(ISSUER, State.USABLE, 1);
    when(profiles.existsByOwnershipIn(any())).thenReturn(true);

    Impact withPersons = impact.of(house.getId());

    assertThat(withPersons.connections()).isNull();
    assertThat(withPersons.confirmationRequired()).isTrue();

    when(profiles.existsByOwnershipIn(any())).thenReturn(false);
    assertThat(impact.of(house.getId()).confirmationRequired()).isFalse();
  }

  private UUID account(String issuer, State state, long connections) {
    User user = new User(UUID.randomUUID().toString(), issuer, null, "Person");
    UUID id = user.getId();
    accounts.add(user);
    states.put(id, state);
    totals.connections.put(id, connections);
    return id;
  }

  private static OidcProvider provider(String name, String issuer) {
    return new OidcProvider(
        name, issuer, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
  }

  private static final class RecordingTotals implements PersonConnections {
    private final List<Set<UUID>> asked = new ArrayList<>();
    private final Map<UUID, Long> connections = new HashMap<>();

    @Override
    public PersonTotals totalsOf(Collection<UUID> userIds) {
      asked.add(new HashSet<>(userIds));
      return new PersonTotals(
          userIds.stream().mapToLong(id -> connections.getOrDefault(id, 0L)).sum(), 0);
    }

    @Override
    public Map<UUID, StateCounts> countsAmong(Collection<UUID> profileIds) {
      return Map.of();
    }

    @Override
    public void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId) {}
  }
}
