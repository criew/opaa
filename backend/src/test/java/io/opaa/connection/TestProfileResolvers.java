package io.opaa.connection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionSecrets;
import io.opaa.connection.profile.ConnectorTypePolicyRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;

/** The port and its parts wired over repository doubles and the test connectors. */
final class TestProfileResolvers {

  private TestProfileResolvers() {}

  static SourceBlocks blocks(
      ConnectorTypePolicyRepository policies,
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles) {
    return new SourceBlocks(policies, connections, profiles, secrets(connections), registry());
  }

  static ProfileSourceConnectionResolver resolver(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      SourceBlocks blocks) {
    ConnectionSecrets secrets = secrets(connections);
    return new ProfileSourceConnectionResolver(
        new EffectiveSourceSettings(
            connections,
            profiles,
            blocks,
            secrets,
            registry(),
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC())),
        blocks,
        secrets);
  }

  private static ConnectionSecrets secrets(LibraryConnectionRepository connections) {
    return new ConnectionSecrets(connections, mock(KnowledgeLibraryRepository.class));
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SourceConnectorRegistry> registry() {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(TestSourceConnectors.connectors().registry());
    return provider;
  }
}
