package io.opaa.connection.profile;

import io.opaa.connection.profile.SecretOwner.LibraryOwned;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place in connections that reads, checks and discards the secret a library's source is
 * reached with, addressed by its {@link SecretOwner}. Every read goes to the stored row, so a
 * secret discarded meanwhile is not handed out from an entity loaded earlier. Discarding erases the
 * column, not only the attribute: without its key the entity reads {@code null} anyway.
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
   * The secret {@code owner} holds now, to be sent to {@code target} through the profile named
   * {@code accessName}.
   *
   * @throws SourceConnectionBlockedException naming {@code accessName} when it holds none
   */
  public Secret current(SecretOwner owner, String target, String accessName) {
    return switch (owner) {
      case LibraryOwned(UUID libraryId) -> {
        Secret secret = Secret.personal(columnOf(libraryId));
        if (secret == null) {
          throw new SourceConnectionBlockedException(SourceBlocks.secretMissing(accessName));
        }
        yield secret;
      }
    };
  }

  /**
   * Why {@code owner} cannot hand out a secret now, empty while it can; see {@link #statesAmong}.
   */
  public Optional<SourceBlock.Reason> stateOf(SecretOwner owner) {
    return Optional.ofNullable(statesAmong(List.of(owner)).get(owner));
  }

  /**
   * {@link #stateOf} for many owners with one query, absent for an owner that can hand one out. It
   * decrypts and renews nothing, so a stored but unreadable secret counts as held here and is
   * refused by {@link #current} only.
   */
  public Map<SecretOwner, SourceBlock.Reason> statesAmong(Collection<SecretOwner> owners) {
    List<UUID> libraryIds = new ArrayList<>();
    for (SecretOwner owner : owners) {
      switch (owner) {
        case LibraryOwned(UUID libraryId) -> libraryIds.add(libraryId);
      }
    }
    Set<UUID> holding =
        libraryIds.isEmpty() ? Set.of() : libraries.findIdsHoldingSourceCredentials(libraryIds);
    Map<SecretOwner, SourceBlock.Reason> states = new HashMap<>();
    for (SecretOwner owner : owners) {
      switch (owner) {
        case LibraryOwned(UUID libraryId) -> {
          if (!holding.contains(libraryId)) {
            states.put(owner, SourceBlock.Reason.NOT_CONNECTED);
          }
        }
      }
    }
    return states;
  }

  /**
   * The secret {@code owner} stores for the library itself, as a change keeps it on the same
   * origin; {@code null} for none or an unreadable one. Never renewed, never a person's secret.
   */
  public String stored(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned(UUID libraryId) -> columnOf(libraryId);
    };
  }

  /** Whether {@link #stored} holds a secret, regardless of whether it may be used now. */
  public boolean holds(SecretOwner owner) {
    return stored(owner) != null;
  }

  /** Discards the secret of {@code owner}, in the attribute of a loaded library and the column. */
  public void discard(SecretOwner owner) {
    switch (owner) {
      case LibraryOwned(UUID libraryId) -> {
        libraries.findById(libraryId).ifPresent(KnowledgeLibrary::dropSourceCredentials);
        libraries.eraseSourceCredentials(libraryId);
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

  /** The library's secret as stored now; inside a transaction its managed entity. */
  private String columnOf(UUID libraryId) {
    return libraries.findById(libraryId).map(KnowledgeLibrary::getSourceCredentials).orElse(null);
  }
}
