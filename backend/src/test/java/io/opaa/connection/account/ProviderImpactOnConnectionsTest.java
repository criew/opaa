package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.AccountUsability.State;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.ProviderConnectionsImpact.Impact;
import io.opaa.auth.ProviderConnectionsImpact.MaskedCount;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.profile.PersonConnections;
import io.opaa.connection.profile.TestPersonCounts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The numbers the provider administration sees before switching a provider off or deleting it: over
 * the provider's accounts that are not deactivated already, and masked below the minimum group
 * size, zero included.
 */
class ProviderImpactOnConnectionsTest {

  private static final String ISSUER = "https://idp.example.org/realms/haus";

  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AccountUsability usability = mock(AccountUsability.class);
  private final AccountUsability.Snapshot snapshot = mock(AccountUsability.Snapshot.class);
  private final RecordingTotals totals = new RecordingTotals();
  private final ProviderImpactOnConnections impact =
      new ProviderImpactOnConnections(
          providers, users, usability, TestPersonCounts.numbersOver(totals));

  private final UUID providerId = UUID.randomUUID();
  private final Map<UUID, State> states = new LinkedHashMap<>();

  @Test
  void onlyAccountsNotDeactivatedAlreadyCountAndASmallNumberIsMasked() {
    UUID usable = account(State.USABLE);
    UUID resting = account(State.DORMANT_PROVIDER_DISABLED);
    account(State.DEACTIVATED);
    totals.answer = new PersonConnections.PersonTotals(1, 0);

    Impact result = impact.of(providerId);

    assertThat(totals.asked).containsExactlyInAnyOrder(usable, resting);
    assertThat(result.connections()).isEqualTo(new MaskedCount(null, 5));
    assertThat(result.privateLibraries()).isEqualTo(new MaskedCount(null, 5));
  }

  @Test
  void fromTheMinimumGroupSizeOnTheNumbersAreExact() {
    account(State.USABLE);
    totals.answer = new PersonConnections.PersonTotals(12, 5);

    Impact result = impact.of(providerId);

    assertThat(result.connections()).isEqualTo(new MaskedCount(12L, null));
    assertThat(result.privateLibraries()).isEqualTo(new MaskedCount(5L, null));
  }

  private UUID account(State state) {
    UUID id = UUID.randomUUID();
    states.put(id, state);
    OidcProvider provider = mock(OidcProvider.class);
    when(provider.getIssuerUri()).thenReturn(ISSUER + "/");
    when(providers.findById(providerId)).thenReturn(Optional.of(provider));
    List<User> accounts = new ArrayList<>();
    for (UUID each : states.keySet()) {
      User user = mock(User.class);
      when(user.getId()).thenReturn(each);
      accounts.add(user);
    }
    when(users.findByNormalizedIssuer(ISSUER)).thenReturn(accounts);
    when(usability.snapshot()).thenReturn(snapshot);
    when(snapshot.statesOf(any())).thenReturn(states);
    return id;
  }

  private static final class RecordingTotals implements PersonConnections {
    private final List<UUID> asked = new ArrayList<>();
    private PersonTotals answer = new PersonTotals(0, 0);

    @Override
    public PersonTotals totalsOf(Collection<UUID> userIds) {
      asked.addAll(userIds);
      return answer;
    }

    @Override
    public Map<UUID, StateCounts> countsAmong(Collection<UUID> profileIds) {
      return Map.of();
    }

    @Override
    public void endAllUnder(
        UUID profileId, io.opaa.api.types.ConnectionEndCause cause, UUID actorUserId) {}
  }
}
