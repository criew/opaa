package io.opaa.connection.account;

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
import io.opaa.connection.profile.PersonCount;
import io.opaa.connection.profile.PersonNumbers;
import io.opaa.connection.profile.PersonNumbers.PersonsCounts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link ProviderConnectionsImpact} over the provider's accounts that are not deactivated
 * already - for the row of the local accounts, the regular ones its switch reaches - through {@link
 * PersonNumbers}, so no answer tells whether a single person is connected.
 */
@Component
class ProviderImpactOnConnections implements ProviderConnectionsImpact {

  private final OidcProviderRepository providers;
  private final UserRepository users;
  private final AccountUsability usability;
  private final PersonNumbers numbers;

  ProviderImpactOnConnections(
      OidcProviderRepository providers,
      UserRepository users,
      AccountUsability usability,
      PersonNumbers numbers) {
    this.providers = providers;
    this.users = users;
    this.usability = usability;
    this.numbers = numbers;
  }

  @Override
  @Transactional(readOnly = true)
  public Impact of(UUID providerId) {
    OidcProvider provider =
        providers
            .findById(providerId)
            .orElseThrow(() -> new NotFoundException("Identitätsanbieter nicht gefunden"));
    List<User> accounts =
        provider.isLocal()
            ? users.findByIssuerAndSystemRoleNot(LocalIssuer.URN, SystemRole.SYSTEM_ADMIN)
            : users.findByNormalizedIssuer(OidcIssuerUris.normalize(provider.getIssuerUri()));
    Map<UUID, AccountUsability.State> states = usability.snapshot().statesOf(accounts);
    List<UUID> concerned =
        accounts.stream().map(User::getId).filter(id -> !states.get(id).isDeactivated()).toList();
    PersonsCounts counts = numbers.ofPersons(concerned);
    return new Impact(masked(counts.connections()), masked(counts.privateLibraries()));
  }

  private static MaskedCount masked(PersonCount count) {
    return new MaskedCount(count.count(), count.fewerThan());
  }
}
