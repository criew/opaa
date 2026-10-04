package io.opaa.connection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorTypePolicyRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.LibraryRows;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.connection.profile.RunStateResets;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.TestSecrets;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The port and its parts wired over repository doubles and the test connectors; {@code rows} are
 * the stored libraries the secret store reads.
 */
final class TestProfileResolvers {

  private TestProfileResolvers() {}

  static SourceBlocks blocks(
      ConnectorTypePolicyRepository policies,
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      Map<UUID, KnowledgeLibrary> rows) {
    return new SourceBlocks(
        policies,
        connections,
        profiles,
        new ProfileRequirements(policies, registry()),
        secrets(connections, rows),
        registry());
  }

  static ProfileSourceConnectionResolver resolver(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      SourceBlocks blocks,
      Map<UUID, KnowledgeLibrary> rows) {
    ConnectionSecrets secrets = secrets(connections, rows);
    return new ProfileSourceConnectionResolver(
        new EffectiveSourceSettings(
            connections,
            profiles,
            LibraryRows.over(rows),
            blocks,
            secrets,
            registry(),
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC())),
        blocks,
        secrets,
        mock(RunStateResets.class),
        accounts());
  }

  private static ConnectionSecrets secrets(
      LibraryConnectionRepository connections, Map<UUID, KnowledgeLibrary> rows) {
    return TestSecrets.overLibraries(connections, LibraryRows.over(rows));
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<ConnectedAccountService> accounts() {
    ObjectProvider<ConnectedAccountService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(mock(ConnectedAccountService.class));
    return provider;
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SourceConnectorRegistry> registry() {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject())
        .thenReturn(
            TestSourceConnectors.connectors().with(new ProfileProbeSourceConnector()).registry());
    return provider;
  }
}
