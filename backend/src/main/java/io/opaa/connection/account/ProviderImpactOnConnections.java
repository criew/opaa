package io.opaa.connection.account;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcIssuerUris;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.ProviderConnectionsImpact;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.PersonCount;
import io.opaa.connection.profile.PersonNumbers;
import io.opaa.connection.profile.PersonNumbers.PersonsCounts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link ProviderConnectionsImpact} over the accounts of every provider that are not
 * deactivated already - for the row of the local accounts, the regular ones its switch reaches -
 * grouped per provider through {@link PersonNumbers#ofGroups}, so neither one answer nor the
 * answers of all providers together with those of the profiles tell a number below N.
 */
@Component
class ProviderImpactOnConnections implements ProviderConnectionsImpact {

  private static final List<ConnectionOwnership> ADMITTING_PERSONS =
      List.of(ConnectionOwnership.PERSON, ConnectionOwnership.BOTH);

  private final OidcProviderRepository providers;
  private final UserRepository users;
  private final AccountUsability usability;
  private final ConnectionProfileRepository profiles;
  private final PersonNumbers numbers;

  ProviderImpactOnConnections(
      OidcProviderRepository providers,
      UserRepository users,
      AccountUsability usability,
      ConnectionProfileRepository profiles,
      PersonNumbers numbers) {
    this.providers = providers;
    this.users = users;
    this.usability = usability;
    this.profiles = profiles;
    this.numbers = numbers;
  }

  @Override
  @Transactional(readOnly = true)
  public Impact of(UUID providerId) {
    if (providers.findById(providerId).isEmpty()) {
      throw new NotFoundException("Identitätsanbieter nicht gefunden");
    }
    Map<String, UUID> byIssuer = new HashMap<>();
    UUID localRow = null;
    Map<UUID, List<UUID>> groups = new HashMap<>();
    for (OidcProvider provider : providers.findAll()) {
      groups.put(provider.getId(), new ArrayList<>());
      if (provider.isLocal()) {
        localRow = provider.getId();
      } else {
        byIssuer.put(OidcIssuerUris.normalize(provider.getIssuerUri()), provider.getId());
      }
    }
    List<User> accounts = users.findAll();
    Map<UUID, AccountUsability.State> states = usability.snapshot().statesOf(accounts);
    List<UUID> others = new ArrayList<>();
    for (User account : accounts) {
      UUID group = groupOf(account, states.get(account.getId()), byIssuer, localRow);
      (group == null ? others : groups.get(group)).add(account.getId());
    }
    PersonsCounts counts = numbers.ofGroups(groups, others).get(providerId);
    MaskedCount connections = masked(counts.connections());
    MaskedCount libraries = masked(counts.privateLibraries());
    boolean anyTold = connections != null || libraries != null;
    return new Impact(
        anyTold || profiles.existsByOwnershipIn(ADMITTING_PERSONS), connections, libraries);
  }

  /** The provider whose switch reaches the account, {@code null} for none or a deactivated one. */
  private static UUID groupOf(
      User account, AccountUsability.State state, Map<String, UUID> byIssuer, UUID localRow) {
    if (state == null || state.isDeactivated()) {
      return null;
    }
    if (LocalIssuer.URN.equals(account.getIssuer())) {
      return account.getSystemRole() == SystemRole.SYSTEM_ADMIN ? null : localRow;
    }
    return byIssuer.get(OidcIssuerUris.normalize(account.getIssuer()));
  }

  private static MaskedCount masked(PersonCount count) {
    return count == null
        ? null
        : new MaskedCount(count.count(), count.fewerThan(), count.atLeast());
  }
}
