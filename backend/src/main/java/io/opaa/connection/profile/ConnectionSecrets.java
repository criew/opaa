package io.opaa.connection.profile;

import io.opaa.connection.profile.SecretOwner.LibraryOwned;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place in connections that reads, checks and discards the secret a library's source is
 * reached with, addressed by its {@link SecretOwner}. Discarding erases the stored column, not only
 * the attribute: without its key the entity reads {@code null} anyway.
 */
@Component
public class ConnectionSecrets {

  private static final Logger log = LoggerFactory.getLogger(ConnectionSecrets.class);

  /** Why every secret under a profile is discarded. */
  public enum DiscardCause {
    ADDRESS_CHANGED,
    REGISTRATION_CHANGED,
    EMERGENCY,
    PROFILE_DELETED
  }

  private final LibraryConnectionRepository connections;
  private final KnowledgeLibraryRepository libraries;

  public ConnectionSecrets(
      LibraryConnectionRepository connections, KnowledgeLibraryRepository libraries) {
    this.connections = connections;
    this.libraries = libraries;
  }

  /**
   * The secret {@code owner} holds now, to be sent to {@code target}.
   *
   * @throws SourceConnectionBlockedException when it holds none
   */
  public Secret current(SecretOwner owner, String target) {
    return switch (owner) {
      case LibraryOwned(KnowledgeLibrary library) -> {
        Secret secret = Secret.personal(library.getSourceCredentials());
        if (secret == null) {
          throw new SourceConnectionBlockedException(SourceBlocks.secretMissing());
        }
        yield secret;
      }
    };
  }

  /** Why {@code owner} cannot hand out a secret now, empty while it can; renews nothing. */
  public Optional<SourceBlock.Reason> stateOf(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned(KnowledgeLibrary library) ->
          library.getSourceCredentials() == null
              ? Optional.of(SourceBlock.Reason.NOT_CONNECTED)
              : Optional.empty();
    };
  }

  /** Discards the secret of {@code owner}. */
  public void discard(SecretOwner owner) {
    switch (owner) {
      case LibraryOwned(KnowledgeLibrary library) -> {
        library.dropSourceCredentials();
        libraries.eraseSourceCredentials(library.getId());
      }
    }
  }

  /** Discards every secret held under {@code profileId}; returns the connections concerned. */
  public int discardAllUnder(UUID profileId, DiscardCause cause) {
    List<LibraryConnection> under = connections.findByProfileId(profileId);
    for (LibraryConnection connection : under) {
      libraries.eraseSourceCredentials(connection.getLibraryId());
    }
    log.info(
        "Discarded the secrets of {} connections under profile {} ({})",
        under.size(),
        profileId,
        cause);
    return under.size();
  }
}
