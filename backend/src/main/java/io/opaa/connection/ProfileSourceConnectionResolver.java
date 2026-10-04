package io.opaa.connection;

import io.opaa.connection.profile.ConnectionSecrets;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.EffectiveSourceSettings.Purpose;
import io.opaa.connection.profile.SecretOwner;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import org.springframework.stereotype.Component;

/**
 * The core's port, answered by {@link EffectiveSourceSettings} (ADR-0041, Entscheidung 3); whether
 * a library is blocked decides {@link SourceBlocks}, its stored secret {@link ConnectionSecrets}.
 *
 * <p>A lock of the type or the profile blocks {@link #resolve}, which starts a run or fetches an
 * original; {@link #currentCredentials} and {@link #resolveForChange} do not, so a run already
 * going ends regularly and a locked library can still be repaired.
 */
@Component
public class ProfileSourceConnectionResolver implements SourceConnectionResolver {

  private final EffectiveSourceSettings effective;
  private final SourceBlocks blocks;
  private final ConnectionSecrets secrets;

  public ProfileSourceConnectionResolver(
      EffectiveSourceSettings effective, SourceBlocks blocks, ConnectionSecrets secrets) {
    this.effective = effective;
    this.blocks = blocks;
    this.secrets = secrets;
  }

  @Override
  public SourceSettings resolve(KnowledgeLibrary library) {
    return effective.of(library, Purpose.RUN);
  }

  @Override
  public boolean isLocked(KnowledgeLibrary library) {
    return blocks.blockOf(library, SourceBlocks.LOCKS).isPresent();
  }

  @Override
  public SourceSettings resolveForChange(KnowledgeLibrary library) {
    return effective.of(library, Purpose.CHANGE);
  }

  @Override
  public Secret currentSecret(KnowledgeLibrary library) {
    return effective.currentSecret(library);
  }

  @Override
  public String storedCredentials(KnowledgeLibrary library) {
    SecretOwner owner = SecretOwner.of(library);
    return secrets.stateOf(owner).isPresent()
        ? null
        : secrets.current(owner, library.getSourceUrl()).value();
  }

  @Override
  public boolean holdsCredentials(KnowledgeLibrary library) {
    return secrets.stateOf(SecretOwner.of(library)).isEmpty();
  }

  @Override
  public ConnectorData effectiveSettings(KnowledgeLibrary library) {
    return effective.of(library, Purpose.SETTINGS_ONLY).connectorSettings();
  }
}
