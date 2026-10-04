package io.opaa.connection.profile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.TestSecrets;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The composition and the transitions over repository doubles, for unit tests of the moves between
 * frames: {@code connections} and {@code profiles} answer which profile a library is on, {@code
 * libraries} the stored rows the secret store reads.
 */
public final class TransitionWiring {

  public final EffectiveSourceSettings effective;
  public final ConnectionSecrets secrets;
  public final SourceTransitions transitions;
  public final AuditEventRecorder audit = mock(AuditEventRecorder.class);

  @SuppressWarnings("unchecked")
  public TransitionWiring(
      SourceConnectorRegistry registry,
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      KnowledgeLibraryRepository libraries) {
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(registry);
    ConnectorTypePolicyRepository policies = mock(ConnectorTypePolicyRepository.class);
    this.secrets = TestSecrets.overLibraries(connections, libraries);
    this.effective =
        new EffectiveSourceSettings(
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
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()));
    this.transitions = new SourceTransitions(effective, secrets, registry, audit);
  }
}
