package io.opaa.connection;

import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.EffectiveSourceSettings.Purpose;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The core's port, answered by {@link EffectiveSourceSettings} (ADR-0041, Entscheidung 3); whether
 * a library is blocked decides {@link SourceBlocks}, its stored secret {@link ConnectionSecrets}.
 *
 * <p>A lock of the type or the profile blocks {@link #resolve}, which starts a run or fetches an
 * original; {@link #currentCredentials} and {@link #resolveForChange} do not, so a run already
 * going ends regularly and a locked library can still be repaired. A rejected person's secret
 * expires their connected account; a rejected token of a profile's own sign-in is obtained anew.
 */
@Component
public class ProfileSourceConnectionResolver implements SourceConnectionResolver {

  private final EffectiveSourceSettings effective;
  private final SourceBlocks blocks;
  private final ConnectionSecrets secrets;

  /** Looked up per call: the accounts reach the release, and the release the connectors. */
  private final ObjectProvider<ConnectedAccountService> accounts;

  public ProfileSourceConnectionResolver(
      EffectiveSourceSettings effective,
      SourceBlocks blocks,
      ConnectionSecrets secrets,
      ObjectProvider<ConnectedAccountService> accounts) {
    this.effective = effective;
    this.blocks = blocks;
    this.secrets = secrets;
    this.accounts = accounts;
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
  public Secret secretAfterRejection(KnowledgeLibrary library, Secret rejected) {
    return effective.secretAfterRejection(library);
  }

  @Override
  public void credentialsRejected(KnowledgeLibrary library) {
    SecretOwner owner = effective.secretOwnerOf(library);
    if (owner instanceof PersonOwned person) {
      accounts.getObject().rejected(library, person);
    } else {
      secrets.rejected(owner);
    }
  }

  @Override
  public String storedCredentials(KnowledgeLibrary library) {
    return secrets.stored(effective.secretOwnerOf(library));
  }

  @Override
  public boolean holdsCredentials(KnowledgeLibrary library) {
    return secrets.holds(effective.secretOwnerOf(library));
  }

  @Override
  public ConnectorData effectiveSettings(KnowledgeLibrary library) {
    return effective.of(library, Purpose.SETTINGS_ONLY).connectorSettings();
  }
}
