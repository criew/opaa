package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.profile.ConnectionProfileRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The provider administration learns only whether any profile admits persons - the confirmation
 * hangs on that, not on how many are connected.
 */
class ProviderImpactOnConnectionsTest {

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final ProviderImpactOnConnections impact = new ProviderImpactOnConnections(profiles);

  @Test
  void personsAreAdmittedExactlyWhereAProfileAdmitsThem() {
    List<ConnectionOwnership> admitting =
        List.of(ConnectionOwnership.PERSON, ConnectionOwnership.BOTH);
    when(profiles.existsByOwnershipIn(admitting)).thenReturn(true);
    assertThat(impact.personsAdmitted()).isTrue();

    when(profiles.existsByOwnershipIn(admitting)).thenReturn(false);
    assertThat(impact.personsAdmitted()).isFalse();
  }
}
