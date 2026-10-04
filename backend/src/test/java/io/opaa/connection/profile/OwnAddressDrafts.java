package io.opaa.connection.profile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The composition over an installation without profiles, for tests of the library services: every
 * library has its own address, and {@code libraries} are the stored ones a draft refers to.
 */
public final class OwnAddressDrafts {

  private OwnAddressDrafts() {}

  public static EffectiveSourceSettings over(
      SourceConnectorRegistry registry, KnowledgeLibraryRepository libraries) {
    return over(
        registry,
        libraries,
        new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()));
  }

  @SuppressWarnings("unchecked")
  public static EffectiveSourceSettings over(
      SourceConnectorRegistry registry,
      KnowledgeLibraryRepository libraries,
      ServiceAccountTokens tokens) {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(registry);
    LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
    ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
    ConnectionSecrets secrets = new ConnectionSecrets(connections, libraries);
    ConnectorTypePolicyRepository policies = mock(ConnectorTypePolicyRepository.class);
    return new EffectiveSourceSettings(
        connections,
        profiles,
        libraries,
        new SourceBlocks(
            policies,
            connections,
            profiles,
            new ProfileRequirements(policies, provider),
            secrets,
            provider),
        secrets,
        provider,
        tokens);
  }
}
