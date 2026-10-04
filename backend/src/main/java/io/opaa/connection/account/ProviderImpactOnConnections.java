package io.opaa.connection.account;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.auth.ProviderConnectionsImpact;
import io.opaa.connection.profile.ConnectionProfileRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link ProviderConnectionsImpact} from the profiles alone, so the answer is the same
 * whether no person, one or many are connected.
 */
@Component
class ProviderImpactOnConnections implements ProviderConnectionsImpact {

  private static final List<ConnectionOwnership> ADMITTING_PERSONS =
      List.of(ConnectionOwnership.PERSON, ConnectionOwnership.BOTH);

  private final ConnectionProfileRepository profiles;

  ProviderImpactOnConnections(ConnectionProfileRepository profiles) {
    this.profiles = profiles;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean personsAdmitted() {
    return profiles.existsByOwnershipIn(ADMITTING_PERSONS);
  }
}
